package androidbridge

import (
	"os"
	"time"

	"github.com/rs/zerolog"
)

// BridgeLogger implements the logging rules from contracts/bridge-http-api.md
// (FR-028): records timestamp, endpoint, fingerprint prefix (8 hex), outcome
// and latency ONLY. Never full payloads, description or accountHint.
type BridgeLogger struct {
	log zerolog.Logger
}

// NewBridgeLogger creates a logger writing to stderr.
func NewBridgeLogger() *BridgeLogger {
	out := zerolog.New(os.Stderr).With().Timestamp().Logger()
	return &BridgeLogger{log: out}
}

// FingerprintPrefix returns the first 8 hex chars of a fingerprint.
func FingerprintPrefix(fingerprint string) string {
	if len(fingerprint) < 8 {
		return fingerprint
	}
	return fingerprint[:8]
}

// Request logs one handled request in the sanctioned shape.
func (l *BridgeLogger) Request(endpoint, fingerprint, outcome string, latency time.Duration) {
	l.log.Info().
		Str("endpoint", endpoint).
		Str("fingerprint_prefix", FingerprintPrefix(fingerprint)).
		Str("outcome", outcome).
		Int64("latency_ms", latency.Milliseconds()).
		Msg("request")
}

// Operation logs internal events (dedup scan, mapping save, ping) in the same
// redacted shape.
func (l *BridgeLogger) Operation(name, fingerprint, outcome string, latency time.Duration) {
	l.log.Info().
		Str("operation", name).
		Str("fingerprint_prefix", FingerprintPrefix(fingerprint)).
		Str("outcome", outcome).
		Int64("latency_ms", latency.Milliseconds()).
		Msg("operation")
}
