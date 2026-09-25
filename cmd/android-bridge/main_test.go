package main

import (
	"bytes"
	"flag"
	"os"
	"strings"
	"testing"
)

func TestParseConfigLoadsSecretEnvironmentVariablesWithoutPrintingThem(t *testing.T) {
	t.Setenv("GOMONEY_SERVICE_TOKEN", "service-token-from-env")
	t.Setenv("BRIDGE_TOKEN", "bridge-token-from-env")

	cfg, help := parseConfigForTest(t)

	if cfg.GomoneyToken != "service-token-from-env" {
		t.Errorf("GomoneyToken = %q, want token from environment", cfg.GomoneyToken)
	}
	if cfg.BearerToken != "bridge-token-from-env" {
		t.Errorf("BearerToken = %q, want token from environment", cfg.BearerToken)
	}
	if strings.Contains(help, "service-token-from-env") || strings.Contains(help, "bridge-token-from-env") {
		t.Fatalf("help output leaked a secret token:\n%s", help)
	}
}

func TestParseConfigSecretFlagsOverrideEnvironment(t *testing.T) {
	t.Setenv("GOMONEY_SERVICE_TOKEN", "service-token-from-env")
	t.Setenv("BRIDGE_TOKEN", "bridge-token-from-env")

	cfg, _ := parseConfigForTest(t, "--gomoney-token=service-token-from-flag", "--bearer-token=bridge-token-from-flag")

	if cfg.GomoneyToken != "service-token-from-flag" {
		t.Errorf("GomoneyToken = %q, want command-line value", cfg.GomoneyToken)
	}
	if cfg.BearerToken != "bridge-token-from-flag" {
		t.Errorf("BearerToken = %q, want command-line value", cfg.BearerToken)
	}
}

func parseConfigForTest(t *testing.T, args ...string) (Config, string) {
	t.Helper()

	previousCommandLine := flag.CommandLine
	previousArgs := os.Args
	commandLine := flag.NewFlagSet("android-bridge", flag.ContinueOnError)
	var output bytes.Buffer
	commandLine.SetOutput(&output)
	flag.CommandLine = commandLine
	os.Args = append([]string{"android-bridge"}, args...)
	t.Cleanup(func() {
		flag.CommandLine = previousCommandLine
		os.Args = previousArgs
	})

	cfg := parseConfig()
	commandLine.Usage()
	return cfg, output.String()
}
