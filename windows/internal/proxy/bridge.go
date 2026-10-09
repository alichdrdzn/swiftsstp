package proxy

import (
	"bufio"
	"io"
	"net"
)

// bridge copies bytes between the local client (with its buffered reader, so
// pipelined bytes are never lost) and the virtual connection through the
// tunnel. It returns when both directions are done and closes both ends.
func bridge(local net.Conn, lbr *bufio.Reader, remote net.Conn) {
	doneA := make(chan struct{}) // client -> tunnel
	doneB := make(chan struct{}) // tunnel -> client

	go func() {
		defer close(doneA)
		_, _ = io.Copy(remote, lbr)
		// Client finished sending: half-close so the server sees EOF but
		// responses can keep flowing.
		if cw, ok := remote.(interface{ CloseWrite() error }); ok {
			_ = cw.CloseWrite()
		}
	}()
	go func() {
		defer close(doneB)
		_, _ = io.Copy(local, remote)
	}()

	<-doneB // server side finished (or errored)
	_ = local.Close()
	_ = remote.Close()
	<-doneA // unblocked by local.Close()
}
