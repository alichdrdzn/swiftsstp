package engine

import (
	"context"
	"log/slog"
)

// slogAdapter bridges the engine's line logger to tunnel.Config.Logger, which
// takes a *slog.Logger. All records are forwarded to logf so the UI log tail
// and the tunnel package's internal logs interleave in one stream.
func newSlogAdapter(logf func(string, ...any)) *slog.Logger {
	if logf == nil {
		return nil
	}
	return slog.New(&slogHandler{logf: logf})
}

type slogHandler struct {
	logf func(string, ...any)
	attrs []slog.Attr
	group string
}

func (h *slogHandler) Enabled(context.Context, slog.Level) bool { return true }

func (h *slogHandler) Handle(_ context.Context, r slog.Record) error {
	msg := r.Message
	if h.group != "" {
		msg = h.group + "." + msg
	}
	if len(h.attrs) > 0 {
		for _, a := range h.attrs {
			msg += " " + a.Key + "=" + a.Value.String()
		}
	}
	r.Attrs(func(a slog.Attr) bool {
		msg += " " + a.Key + "=" + a.Value.String()
		return true
	})
	h.logf("%s", msg)
	return nil
}

func (h *slogHandler) WithAttrs(attrs []slog.Attr) slog.Handler {
	cp := *h
	cp.attrs = append(append([]slog.Attr{}, h.attrs...), attrs...)
	return &cp
}

func (h *slogHandler) WithGroup(name string) slog.Handler {
	cp := *h
	if cp.group != "" {
		cp.group += "."
	}
	cp.group += name
	return &cp
}
