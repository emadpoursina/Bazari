package main

import (
	"context"
	"errors"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"syscall"
	"time"

	"github.com/rs/zerolog"
)

func main() {
	zerolog.TimeFieldFormat = time.RFC3339Nano
	log := zerolog.New(os.Stdout).With().Timestamp().Str("service", "navasan-translator").Logger()

	if err := run(log); err != nil {
		log.Fatal().Err(err).Msg("navasan translator stopped")
	}
}

func run(log zerolog.Logger) error {
	cfg, err := LoadConfig(context.Background())
	if err != nil {
		return err
	}

	rateTranslator, err := newTranslator(cfg)
	if err != nil {
		return err
	}

	srv := &rateServer{
		client:     newNavasanClient(cfg.NavasanAPIURL, cfg.NavasanAPIKey, &http.Client{Timeout: cfg.HTTPTimeout}),
		translator: rateTranslator,
		log:        log,
		ttl:        cfg.CacheTTL,
		now:        time.Now,
	}

	httpServer := &http.Server{
		Addr:              net.JoinHostPort("", strconv.Itoa(cfg.Port)),
		Handler:           srv.routes(),
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       30 * time.Second,
		WriteTimeout:      60 * time.Second,
		IdleTimeout:       120 * time.Second,
	}

	errCh := make(chan error, 1)

	go func() {
		log.Info().
			Str("addr", httpServer.Addr).
			Str("upstream", cfg.NavasanAPIURL).
			Str("pivot", cfg.PivotCurrency+"/"+rateTranslator.symbols[cfg.PivotCurrency]).
			Int("symbols", len(rateTranslator.symbols)).
			Str("derived", cfg.LocalCurrencies).
			Msg("serving exchange rates")

		if listenErr := httpServer.ListenAndServe(); listenErr != nil && !errors.Is(listenErr, http.ErrServerClosed) {
			errCh <- listenErr
		}
	}()

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, syscall.SIGINT, syscall.SIGTERM)

	select {
	case err = <-errCh:
		return err
	case <-stop:
		log.Info().Msg("shutting down")
	}

	shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	return httpServer.Shutdown(shutdownCtx)
}
