package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/ft-t/go-money/pkg/androidbridge"
	"github.com/rs/zerolog/log"
)

// Config mirrors the flag/env setup for the bridge (plan.md): listen address,
// Go Money service credentials, dedup db path and mappings file path.
type Config struct {
	Listen       string
	GomoneyURL   string
	GomoneyToken string
	BearerToken  string
	DedupDBPath  string
	MappingsPath string
}

func parseConfig() Config {
	cfg := Config{}

	flag.StringVar(&cfg.Listen, "listen", envOr("BRIDGE_LISTEN", ":8787"), "HTTP listen address")
	flag.StringVar(&cfg.GomoneyURL, "gomoney-url", envOr("GOMONEY_URL", "http://127.0.0.1:8080"), "Go Money server base URL")
	flag.StringVar(&cfg.GomoneyToken, "gomoney-token", envOr("GOMONEY_SERVICE_TOKEN", ""), "Go Money service token")
	flag.StringVar(&cfg.BearerToken, "bearer-token", envOr("BRIDGE_TOKEN", ""), "bridge bearer token (Android → bridge auth)")
	flag.StringVar(&cfg.DedupDBPath, "dedup-db", envOr("BRIDGE_DEDUP_DB", "dedup.db"), "dedup SQLite database path")
	flag.StringVar(&cfg.MappingsPath, "mappings-file", envOr("BRIDGE_MAPPINGS", "mappings.json"), "account-hint mappings JSON file")
	flag.Parse()
	return cfg
}

func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func main() {
	cfg := parseConfig()

	if cfg.GomoneyToken == "" {
		fatal("--gomoney-token (or GOMONEY_SERVICE_TOKEN) is required")
	}
	if cfg.BearerToken == "" {
		fatal("--bearer-token (or BRIDGE_TOKEN) is required")
	}

	dedup, err := androidbridge.OpenDedupStore(cfg.DedupDBPath)
	if err != nil {
		fatal(err.Error())
	}
	defer func() { _ = dedup.Close() }()

	mappings, err := androidbridge.NewMappingStore(cfg.MappingsPath)
	if err != nil {
		fatal(err.Error())
	}

	client := androidbridge.NewGoMoneyConnectClient(cfg.GomoneyURL, cfg.GomoneyToken)
	server := androidbridge.NewServer(cfg.BearerToken, client, dedup, mappings)

	httpServer := &http.Server{
		Addr:              cfg.Listen,
		Handler:           server.Handler(),
		ReadHeaderTimeout: 10 * time.Second,
	}

	ln, err := net.Listen("tcp", cfg.Listen)
	if err != nil {
		fatal(fmt.Sprintf("listen %s: %v", cfg.Listen, err))
	}

	go func() {
		if err = httpServer.Serve(ln); err != nil && err != http.ErrServerClosed {
			fatal(err.Error())
		}
	}()

	printConfigJSON(cfg)
	log.Info().Str("listen", cfg.Listen).Msg("android bridge started")

	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)
	<-sig

	shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_ = httpServer.Shutdown(shutdownCtx)
}

// printConfigJSON prints the effective (non-secret) configuration on startup.
func printConfigJSON(cfg Config) {
	out, _ := json.Marshal(map[string]string{
		"listen":   cfg.Listen,
		"gomoney":  cfg.GomoneyURL,
		"dedup_db": cfg.DedupDBPath,
		"mappings": cfg.MappingsPath,
	})
	fmt.Fprintln(os.Stderr, strings.TrimSpace(string(out)))
}

func fatal(msg string) {
	log.Error().Msg(msg)
	os.Exit(1)
}
