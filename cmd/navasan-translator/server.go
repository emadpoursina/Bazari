package main

import (
	"context"
	"encoding/json"
	"net/http"
	"sync"
	"time"

	"github.com/rs/zerolog"
)

// rateServer serves the translated feed the go-money server polls through
// EXCHANGE_RATES_URL.
type rateServer struct {
	client     *navasanClient
	translator *translator
	log        zerolog.Logger
	ttl        time.Duration
	now        func() time.Time

	mu     sync.Mutex
	cached *cachedRates
}

type cachedRates struct {
	payload []byte
	at      time.Time
}

func (s *rateServer) routes() *http.ServeMux {
	mux := http.NewServeMux()
	mux.HandleFunc("/latest.json", s.handleRates)
	mux.HandleFunc("/healthz", s.handleHealth)
	mux.HandleFunc("/", s.handleRates)

	return mux
}

func (s *rateServer) handleHealth(w http.ResponseWriter, _ *http.Request) {
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte("ok"))
}

func (s *rateServer) handleRates(w http.ResponseWriter, r *http.Request) {
	payload, err := s.payload(r.Context())
	if err != nil {
		s.log.Error().Err(err).Msg("failed to build exchange rates")
		http.Error(w, "failed to build exchange rates", http.StatusBadGateway)

		return
	}

	w.Header().Set("Content-Type", "application/json")
	_, _ = w.Write(payload)
}

// payload returns the translated feed, refreshing it at most once per TTL. A
// transient upstream failure serves the last good payload instead of failing
// the caller, so a Navasan outage does not abort the go-money sync.
func (s *rateServer) payload(ctx context.Context) ([]byte, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	now := s.now()
	if s.cached != nil && now.Sub(s.cached.at) < s.ttl {
		return s.cached.payload, nil
	}

	payload, err := s.fetch(ctx, now)
	if err != nil {
		if s.cached != nil {
			s.log.Warn().Err(err).Time("cached_at", s.cached.at).Msg("serving stale exchange rates")

			return s.cached.payload, nil
		}

		return nil, err
	}

	s.cached = &cachedRates{payload: payload, at: now}

	return payload, nil
}

func (s *rateServer) fetch(ctx context.Context, now time.Time) ([]byte, error) {
	latest, err := s.client.Latest(ctx)
	if err != nil {
		return nil, err
	}

	rates, err := s.translator.Translate(latest, now)
	if err != nil {
		return nil, err
	}

	// A pinned symbol that yields nothing is almost always a typo or an item
	// Navasan stopped publishing, so surface it loudly rather than silently
	// dropping the currency.
	if missing := s.translator.MissingPinned(rates); len(missing) > 0 {
		s.log.Warn().
			Strs("currencies", missing).
			Msg("pinned currencies are missing from the navasan feed")
	}

	s.log.Info().Int("rates", len(rates.Rates)).Msg("translated exchange rates")

	return json.Marshal(rates)
}
