// Package reconnect keeps an SSTP tunnel up: it wraps a tunnel dialer with
// exponential backoff, reporting the connection lifecycle through callbacks so
// a UI or a supervision loop can mirror what is happening without owning the
// retry policy.
//
// The backoff shape follows the auto_reconnect block of docs/PROFILE_SCHEMA.md:
// an initial delay that doubles after every failed attempt up to a maximum,
// reset by a successful connect — so a link that holds for a day and drops
// retries immediately, while a server that is down does not get hammered.
//
// A rejected credential is not an outage and is never retried: tunnel.Dial's
// error satisfies tunnel.IsAuthError, the manager fires OnAuthFailed and stops.
// The caller prompts for a new password and builds a new Manager with it.
package reconnect

import (
	"context"
	"errors"
	"sync"
	"time"

	"slipstream/tunnel"
)

// DialFunc dials a tunnel; tunnel.Dial satisfies it unchanged. The dialer is
// injected so tests (and alternative transports) can stand in for a server.
type DialFunc func(ctx context.Context) (*tunnel.Session, tunnel.Result, error)

// Backoff bounds matching docs/PROFILE_SCHEMA.md's auto_reconnect:
// initial_backoff_ms defaults to 1000, max_backoff_ms to 30000.
const (
	DefaultInitialBackoff = time.Second
	DefaultMaxBackoff     = 30 * time.Second
)

// Manager runs the reconnect loop around one dialer. Configure the fields,
// then call Connect; the zero-value callbacks are simply not called. One
// Manager serves one Connect at a time.
type Manager struct {
	// InitialBackoff is the delay before the first retry after a failed dial;
	// 0 means DefaultInitialBackoff.
	InitialBackoff time.Duration
	// MaxBackoff caps the doubling delay; below InitialBackoff it is raised to
	// InitialBackoff; 0 means DefaultMaxBackoff.
	MaxBackoff time.Duration

	// OnAttempt is called before every dial attempt: the 1-based attempt
	// number since the last successful connect, the failure that caused this
	// wait (nil when there is none), and how long the manager waited (and will
	// wait, after returning, before dialing) — 0 means dial now.
	OnAttempt func(attempt int, err error, delay time.Duration)
	// OnConnected is called once a dial succeeds, with the live session. The
	// manager then waits on the session; when it ends, OnClosed reports why.
	OnConnected func(sess *tunnel.Session, res tunnel.Result)
	// OnClosed is called after a session that came up ends.
	OnClosed func(err error)
	// OnAuthFailed is called when a dial (or a session's close) is a rejected
	// credential. The manager stops for good; retrying would just relock the
	// account.
	OnAuthFailed func(err error)

	dial     DialFunc
	stop     chan struct{}
	stopOnce sync.Once
}

// NewManager returns a Manager around dial with the schema-default backoff.
func NewManager(dial DialFunc) *Manager {
	return &Manager{
		InitialBackoff: DefaultInitialBackoff,
		MaxBackoff:     DefaultMaxBackoff,
		dial:           dial,
		stop:           make(chan struct{}),
	}
}

// Stop cancels the loop: an in-flight dial is canceled, a pending backoff wait
// ends, and Connect returns nil. It is safe to call from any goroutine and safe
// to call more than once.
func (m *Manager) Stop() {
	m.stopOnce.Do(func() { close(m.stop) })
}

// Connect runs the reconnect loop until stopped, until ctx ends, or until a
// dial fails with an auth error. It returns nil after Stop, ctx.Err() if the
// caller's context ended, and the auth error itself on a rejected credential.
//
// Each iteration dials; a success is handed to OnConnected and held until the
// session ends (OnClosed), after which the manager redials immediately — a
// dropped link wants reconnecting now, not after a backoff. Backoff applies to
// failed dials: after the first failure the delay doubles each time, capped at
// MaxBackoff, and resets when a dial succeeds.
func (m *Manager) Connect(ctx context.Context) error {
	initial := m.InitialBackoff
	if initial <= 0 {
		initial = DefaultInitialBackoff
	}
	max := m.MaxBackoff
	if max < initial {
		max = initial
	}

	// Stop() cancels the dial in flight, not just the waits around it.
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	go func() {
		select {
		case <-m.stop:
			cancel()
		case <-ctx.Done():
		}
	}()

	// stopped reports an explicit Stop(). It is checked before the context at
	// every exit, because Stop cancels that context itself: with both ready a
	// plain select would report a cancellation the caller never asked for.
	stopped := func() bool {
		select {
		case <-m.stop:
			return true
		default:
			return false
		}
	}

	attempt := 1
	var lastErr error
	delay := time.Duration(0)
	for {
		if stopped() {
			return nil
		}
		if err := ctx.Err(); err != nil {
			return err
		}

		if m.OnAttempt != nil {
			m.OnAttempt(attempt, lastErr, delay)
		}
		if delay > 0 {
			t := time.NewTimer(delay)
			select {
			case <-t.C:
			case <-m.stop:
				t.Stop()
				return nil
			case <-ctx.Done():
				t.Stop()
				if stopped() {
					return nil
				}
				return ctx.Err()
			}
		}

		sess, res, err := m.dial(ctx)
		if err == nil && sess == nil {
			// A dialer that reports success must return a session; treat a
			// nil one as a transient failure rather than panicking on Wait.
			err = errors.New("reconnect: dialer returned a nil session")
		}
		if err != nil {
			if tunnel.IsAuthError(err) {
				if m.OnAuthFailed != nil {
					m.OnAuthFailed(err)
				}
				return err
			}
			lastErr = err
			// The first retry waits initial; every further failure doubles the
			// wait, capped at max.
			if delay == 0 {
				delay = initial
			} else if delay *= 2; delay > max {
				delay = max
			}
			attempt++
			continue
		}

		if m.OnConnected != nil {
			m.OnConnected(sess, res)
		}
		closeErr := sess.Wait()
		if m.OnClosed != nil {
			m.OnClosed(closeErr)
		}
		if tunnel.IsAuthError(closeErr) {
			// An established link cannot fail auth, but if the transport ever
			// hands one back it is still a credential problem, not an outage.
			if m.OnAuthFailed != nil {
				m.OnAuthFailed(closeErr)
			}
			return closeErr
		}
		if stopped() {
			return nil
		}
		if err := ctx.Err(); err != nil {
			return err
		}
		// The session ran and ended: redial at once, with the attempt counter
		// and the backoff back at their starting values.
		lastErr = closeErr
		delay = 0
		attempt = 1
	}
}
