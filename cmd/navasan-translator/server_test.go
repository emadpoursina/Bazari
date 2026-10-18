package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"

	"github.com/jarcoal/httpmock"
	"github.com/rs/zerolog"
	"github.com/shopspring/decimal"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

const (
	testUpstreamURL = "http://api.navasan.tech/latest/"
	testUpstreamKey = "key"
)

func newTestServer(t *testing.T, now func() time.Time, ttl time.Duration) *rateServer {
	t.Helper()

	tr, err := newTranslator(testConfig())
	require.NoError(t, err)

	return &rateServer{
		client:     newNavasanClient(testUpstreamURL, testUpstreamKey, http.DefaultClient),
		translator: tr,
		log:        zerolog.Nop(),
		ttl:        ttl,
		now:        now,
	}
}

func doRequest(t *testing.T, srv *rateServer, path string) *httptest.ResponseRecorder {
	t.Helper()

	rec := httptest.NewRecorder()
	srv.routes().ServeHTTP(rec, httptest.NewRequest(http.MethodGet, path, nil))

	return rec
}

func TestRateServerServesTranslatedFeed(t *testing.T) {
	httpmock.Activate(t)
	defer httpmock.DeactivateAndReset()

	httpmock.RegisterResponder(http.MethodGet, testUpstreamURL+"?api_key="+testUpstreamKey,
		httpmock.NewStringResponder(http.StatusOK, string(latestFixture)))

	now := time.Date(2026, 10, 18, 12, 0, 0, 0, time.UTC)
	srv := newTestServer(t, func() time.Time { return now }, time.Minute)

	rec := doRequest(t, srv, "/latest.json")

	require.Equal(t, http.StatusOK, rec.Code)
	assert.Equal(t, "application/json", rec.Header().Get("Content-Type"))

	var payload struct {
		Base  string                     `json:"b"`
		Rates map[string]decimal.Decimal `json:"r"`
	}
	require.NoError(t, json.Unmarshal(rec.Body.Bytes(), &payload))

	assert.Equal(t, "USD", payload.Base)
	assert.True(t, decimal.NewFromInt(1).Equal(payload.Rates["USD"]))
	assert.True(t, decimal.NewFromInt(11270).Equal(payload.Rates["IRT"]))
	assert.True(t, decimal.NewFromInt(112700).Equal(payload.Rates["IRR"]))
}

func TestRateServerCachesWithinTTL(t *testing.T) {
	httpmock.Activate(t)
	defer httpmock.DeactivateAndReset()

	var calls int64

	httpmock.RegisterResponder(http.MethodGet, testUpstreamURL+"?api_key="+testUpstreamKey,
		func(_ *http.Request) (*http.Response, error) {
			atomic.AddInt64(&calls, 1)

			return httpmock.NewStringResponse(http.StatusOK, string(latestFixture)), nil
		})

	current := time.Date(2026, 10, 18, 12, 0, 0, 0, time.UTC)
	srv := newTestServer(t, func() time.Time { return current }, 5*time.Minute)

	require.Equal(t, http.StatusOK, doRequest(t, srv, "/latest.json").Code)

	current = current.Add(time.Minute)
	require.Equal(t, http.StatusOK, doRequest(t, srv, "/").Code)

	assert.Equal(t, int64(1), atomic.LoadInt64(&calls), "second request is served from cache")

	current = current.Add(10 * time.Minute)
	require.Equal(t, http.StatusOK, doRequest(t, srv, "/latest.json").Code)

	assert.Equal(t, int64(2), atomic.LoadInt64(&calls), "cache expires after the TTL")
}

func TestRateServerServesStaleOnUpstreamFailure(t *testing.T) {
	httpmock.Activate(t)
	defer httpmock.DeactivateAndReset()

	var failing atomic.Bool

	httpmock.RegisterResponder(http.MethodGet, testUpstreamURL+"?api_key="+testUpstreamKey,
		func(_ *http.Request) (*http.Response, error) {
			if failing.Load() {
				return httpmock.NewStringResponse(http.StatusServiceUnavailable, "Service Unavailable"), nil
			}

			return httpmock.NewStringResponse(http.StatusOK, string(latestFixture)), nil
		})

	current := time.Date(2026, 10, 18, 12, 0, 0, 0, time.UTC)
	srv := newTestServer(t, func() time.Time { return current }, time.Minute)

	first := doRequest(t, srv, "/latest.json")
	require.Equal(t, http.StatusOK, first.Code)

	failing.Store(true)
	current = current.Add(time.Hour)

	stale := doRequest(t, srv, "/latest.json")
	require.Equal(t, http.StatusOK, stale.Code)
	assert.Equal(t, first.Body.String(), stale.Body.String())
}

func TestRateServerFailsWithoutCachedPayload(t *testing.T) {
	httpmock.Activate(t)
	defer httpmock.DeactivateAndReset()

	httpmock.RegisterResponder(http.MethodGet, testUpstreamURL+"?api_key="+testUpstreamKey,
		httpmock.NewStringResponder(http.StatusUnauthorized, "Invalid api_key"))

	srv := newTestServer(t, time.Now, time.Minute)

	rec := doRequest(t, srv, "/latest.json")

	assert.Equal(t, http.StatusBadGateway, rec.Code)
}

func TestRateServerHealth(t *testing.T) {
	httpmock.Activate(t)
	defer httpmock.DeactivateAndReset()

	srv := newTestServer(t, time.Now, time.Minute)

	rec := doRequest(t, srv, "/healthz")

	assert.Equal(t, http.StatusOK, rec.Code)
	assert.Equal(t, "ok", rec.Body.String())
}
