package reconnect

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"sstpflash/internal/ppp"
	"sstpflash/tunnel"
)

// eventLog records the manager's callbacks in order, for asserting the
// sequence a caller would see.
type eventLog struct {
	mu     sync.Mutex
	events []string
}

func (l *eventLog) add(s string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.events = append(l.events, s)
}

func (l *eventLog) snapshot() []string {
	l.mu.Lock()
	defer l.mu.Unlock()
	return append([]string(nil), l.events...)
}

// TestConnectSuccess drives a dialer that succeeds on the first attempt: the
// session is handed over, and once it ends the manager redials immediately (no
// backoff after a link that ran) until stopped.
func TestConnectSuccess(t *testing.T) {
	var mu sync.Mutex
	dials := 0
	redialed := make(chan struct{})
	m := NewManager(func(ctx context.Context) (*tunnel.Session, tunnel.Result, error) {
		mu.Lock()
		dials++
		n := dials
		mu.Unlock()
		if n == 1 {
			return tunnel.NewStubSession(), tunnel.Result{MTU: 1400}, nil
		}
		// The redial after the session ended: park until Stop cancels it.
		close(redialed)
		<-ctx.Done()
		return nil, tunnel.Result{}, ctx.Err()
	})

	log := &eventLog{}
	var first atomic.Pointer[tunnel.Session]
	connected := make(chan struct{})
	m.OnConnected = func(sess *tunnel.Session, res tunnel.Result) {
		log.add("connected")
		first.CompareAndSwap(nil, sess)
		close(connected)
	}
	m.OnClosed = func(err error) { log.add("closed") }
	var attempts []time.Duration
	var attemptMu sync.Mutex
	m.OnAttempt = func(n int, err error, delay time.Duration) {
		attemptMu.Lock()
		attempts = append(attempts, delay)
		attemptMu.Unlock()
		log.add(fmt.Sprintf("attempt %d", n))
	}

	done := make(chan error, 1)
	go func() { done <- m.Connect(context.Background()) }()

	<-connected
	// Let the session end; the loop must redial at once (zero backoff).
	first.Load().Close()
	select {
	case <-redialed:
	case <-time.After(5 * time.Second):
		t.Fatal("manager did not redial after the session ended")
	}
	m.Stop()

	select {
	case err := <-done:
		if err != nil {
			t.Fatalf("Connect returned %v, want nil after Stop", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("Connect did not return after Stop")
	}

	mu.Lock()
	gotDials := dials
	mu.Unlock()
	if gotDials != 2 {
		t.Errorf("dials = %d, want 2 (initial success + redial after close)", gotDials)
	}
	attemptMu.Lock()
	got := append([]time.Duration(nil), attempts...)
	attemptMu.Unlock()
	if len(got) != 2 || got[0] != 0 || got[1] != 0 {
		t.Errorf("attempt delays = %v, want [0 0] (no backoff around a live session)", got)
	}
	wantEvents := []string{"attempt 1", "connected", "closed", "attempt 1"}
	if got := log.snapshot(); len(got) != len(wantEvents) {
		t.Errorf("events = %v, want %v", got, wantEvents)
	} else {
		for i := range wantEvents {
			if got[i] != wantEvents[i] {
				t.Errorf("events = %v, want %v", got, wantEvents)
			}
		}
	}
}

// TestBackoffTransientFailure: two failed dials wait initial and 2x initial,
// then a success resets everything — the schema's doubling-then-reset policy.
func TestBackoffTransientFailure(t *testing.T) {
	const initial, max = 10 * time.Millisecond, 40 * time.Millisecond
	transient := errors.New("tunnel: dial: connection refused")

	var mu sync.Mutex
	dials := 0
	m := NewManager(func(ctx context.Context) (*tunnel.Session, tunnel.Result, error) {
		mu.Lock()
		defer mu.Unlock()
		dials++
		switch dials {
		case 1, 2:
			return nil, tunnel.Result{}, transient
		default:
			return tunnel.NewStubSession(), tunnel.Result{}, nil
		}
	})
	m.InitialBackoff, m.MaxBackoff = initial, max

	type attempt struct {
		n     int
		err   error
		delay time.Duration
	}
	var attempts []attempt
	var attemptMu sync.Mutex
	m.OnAttempt = func(n int, err error, delay time.Duration) {
		attemptMu.Lock()
		attempts = append(attempts, attempt{n, err, delay})
		attemptMu.Unlock()
	}
	connected := make(chan struct{})
	m.OnConnected = func(sess *tunnel.Session, res tunnel.Result) {
		close(connected)
		go func() {
			time.Sleep(10 * time.Millisecond)
			sess.Close()
		}()
	}
	m.OnClosed = func(err error) { m.Stop() }

	start := time.Now()
	done := make(chan error, 1)
	go func() { done <- m.Connect(context.Background()) }()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("Connect did not finish")
	}
	elapsed := time.Since(start)

	attemptMu.Lock()
	got := append([]attempt(nil), attempts...)
	attemptMu.Unlock()
	if len(got) != 3 {
		t.Fatalf("attempts = %v, want 3", got)
	}
	if got[0].n != 1 || got[0].err != nil || got[0].delay != 0 {
		t.Errorf("first attempt = %+v, want {n:1 err:<nil> delay:0}", got[0])
	}
	if got[1].n != 2 || !errors.Is(got[1].err, transient) || got[1].delay != initial {
		t.Errorf("second attempt = %+v, want {n:2 err:%v delay:%v}", got[1], transient, initial)
	}
	if got[2].n != 3 || !errors.Is(got[2].err, transient) || got[2].delay != 2*initial {
		t.Errorf("third attempt = %+v, want {n:3 err:%v delay:%v}", got[2], transient, 2*initial)
	}
	if min := 3 * initial; elapsed < min {
		t.Errorf("elapsed = %v, want at least the %v of backoff waited", elapsed, min)
	}
	select {
	case <-connected:
	default:
		t.Error("OnConnected never fired after the transient failures")
	}
}

// TestBackoffCapsAtMax: past the cap every wait is MaxBackoff, not more.
func TestBackoffCapsAtMax(t *testing.T) {
	const initial, max = 10 * time.Millisecond, 25 * time.Millisecond
	transient := errors.New("tunnel: dial: boom")

	var mu sync.Mutex
	dials := 0
	m := NewManager(func(ctx context.Context) (*tunnel.Session, tunnel.Result, error) {
		mu.Lock()
		defer mu.Unlock()
		dials++
		if dials <= 4 {
			return nil, tunnel.Result{}, transient
		}
		return tunnel.NewStubSession(), tunnel.Result{}, nil
	})
	m.InitialBackoff, m.MaxBackoff = initial, max

	var delays []time.Duration
	var attemptMu sync.Mutex
	m.OnAttempt = func(n int, err error, delay time.Duration) {
		attemptMu.Lock()
		delays = append(delays, delay)
		attemptMu.Unlock()
	}
	m.OnConnected = func(sess *tunnel.Session, res tunnel.Result) {
		go sess.Close()
	}
	m.OnClosed = func(err error) { m.Stop() }

	done := make(chan error, 1)
	go func() { done <- m.Connect(context.Background()) }()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("Connect did not finish")
	}

	attemptMu.Lock()
	got := append([]time.Duration(nil), delays...)
	attemptMu.Unlock()
	want := []time.Duration{0, initial, 2 * initial, max, max}
	if len(got) != len(want) {
		t.Fatalf("delays = %v, want %v", got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Errorf("delay[%d] = %v, want %v (backoff doubles to max and stops there)", i, got[i], want[i])
		}
	}
}

// TestAuthFailureIsPermanent: a rejected credential stops the manager for good
// — one dial, one OnAuthFailed, no backoff, no session.
func TestAuthFailureIsPermanent(t *testing.T) {
	authErr := fmt.Errorf("tunnel: ppp handshake: %w", ppp.ErrAuth)

	var mu sync.Mutex
	dials := 0
	m := NewManager(func(ctx context.Context) (*tunnel.Session, tunnel.Result, error) {
		mu.Lock()
		defer mu.Unlock()
		dials++
		return nil, tunnel.Result{}, authErr
	})
	m.InitialBackoff = time.Millisecond

	log := &eventLog{}
	var authFired int
	var authMu sync.Mutex
	m.OnAuthFailed = func(err error) {
		authMu.Lock()
		authFired++
		authMu.Unlock()
		log.add("auth-failed")
		if !errors.Is(err, ppp.ErrAuth) {
			t.Errorf("OnAuthFailed got %v, want an ErrAuth error", err)
		}
	}
	m.OnConnected = func(sess *tunnel.Session, res tunnel.Result) {
		t.Error("OnConnected fired on an auth failure")
	}
	m.OnAttempt = func(n int, err error, delay time.Duration) {
		log.add(fmt.Sprintf("attempt %d", n))
	}

	err := m.Connect(context.Background())
	if !errors.Is(err, ppp.ErrAuth) {
		t.Fatalf("Connect returned %v, want the auth error itself", err)
	}
	mu.Lock()
	gotDials := dials
	mu.Unlock()
	if gotDials != 1 {
		t.Errorf("dials = %d, want 1 — a credential failure must not be retried", gotDials)
	}
	authMu.Lock()
	if authFired != 1 {
		t.Errorf("OnAuthFailed fired %d times, want 1", authFired)
	}
	authMu.Unlock()
	if got := log.snapshot(); len(got) != 2 || got[0] != "attempt 1" || got[1] != "auth-failed" {
		t.Errorf("events = %v, want [attempt 1 auth-failed]", got)
	}
}

// TestStopCancelsBackoffWait: Stop ends a pending backoff sleep at once and
// Connect returns nil.
func TestStopCancelsBackoffWait(t *testing.T) {
	m := NewManager(func(ctx context.Context) (*tunnel.Session, tunnel.Result, error) {
		return nil, tunnel.Result{}, errors.New("tunnel: dial: connection refused")
	})
	m.InitialBackoff = time.Hour // long enough that only Stop can end the wait

	attempted := make(chan struct{}, 1)
	m.OnAttempt = func(n int, err error, delay time.Duration) {
		select {
		case attempted <- struct{}{}:
		default:
		}
	}

	done := make(chan error, 1)
	go func() { done <- m.Connect(context.Background()) }()
	<-attempted
	m.Stop()

	select {
	case err := <-done:
		if err != nil {
			t.Fatalf("Connect returned %v, want nil after Stop", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("Stop did not end the backoff wait")
	}
}

// TestStopCancelsInFlightDial: Stop cancels a dial that is already running,
// not only the waits between dials.
func TestStopCancelsInFlightDial(t *testing.T) {
	m := NewManager(func(ctx context.Context) (*tunnel.Session, tunnel.Result, error) {
		<-ctx.Done()
		return nil, tunnel.Result{}, ctx.Err()
	})

	started := make(chan struct{}, 1)
	m.OnAttempt = func(n int, err error, delay time.Duration) {
		select {
		case started <- struct{}{}:
		default:
		}
	}

	done := make(chan error, 1)
	go func() { done <- m.Connect(context.Background()) }()
	<-started
	time.Sleep(10 * time.Millisecond) // let the dial begin blocking on ctx
	m.Stop()

	select {
	case err := <-done:
		if err != nil {
			t.Fatalf("Connect returned %v, want nil after Stop", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("Stop did not cancel the in-flight dial")
	}
}

// TestConnectHonorsCallerContext: the caller's context ending ends Connect
// with its error rather than retrying forever.
func TestConnectHonorsCallerContext(t *testing.T) {
	m := NewManager(func(ctx context.Context) (*tunnel.Session, tunnel.Result, error) {
		return nil, tunnel.Result{}, errors.New("tunnel: dial: no route to host")
	})
	m.InitialBackoff = time.Hour

	ctx, cancel := context.WithCancel(context.Background())
	attempted := make(chan struct{}, 1)
	m.OnAttempt = func(n int, err error, delay time.Duration) {
		select {
		case attempted <- struct{}{}:
		default:
		}
	}
	done := make(chan error, 1)
	go func() { done <- m.Connect(ctx) }()
	<-attempted
	cancel()

	select {
	case err := <-done:
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("Connect returned %v, want context.Canceled", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("canceled context did not end Connect")
	}
}
