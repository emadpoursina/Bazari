package main

import (
	"context"
	"errors"
	"net/http"
	"testing"

	"github.com/jarcoal/httpmock"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestNavasanClientLatest(t *testing.T) {
	const (
		apiURL = "http://api.navasan.tech/latest/"
		apiKey = "secret-key"
	)

	t.Run("success", func(t *testing.T) {
		httpmock.Activate(t)
		defer httpmock.DeactivateAndReset()

		httpmock.RegisterResponder(http.MethodGet, apiURL+"?api_key="+apiKey,
			httpmock.NewStringResponder(http.StatusOK, `{"usd_sell":{"value":"11270"}}`))

		client := newNavasanClient(apiURL, apiKey, http.DefaultClient)

		latest, err := client.Latest(context.Background())
		require.NoError(t, err)

		assert.Equal(t, flexNumber("11270"), latest["usd_sell"].Value)
	})

	t.Run("preserves existing query parameters", func(t *testing.T) {
		httpmock.Activate(t)
		defer httpmock.DeactivateAndReset()

		httpmock.RegisterResponder(http.MethodGet, apiURL+"?api_key="+apiKey+"&extra=1",
			httpmock.NewStringResponder(http.StatusOK, `{"usd_sell":{"value":"1"}}`))

		client := newNavasanClient(apiURL+"?extra=1", apiKey, http.DefaultClient)

		_, err := client.Latest(context.Background())
		require.NoError(t, err)
	})

	t.Run("plain text auth error is surfaced", func(t *testing.T) {
		httpmock.Activate(t)
		defer httpmock.DeactivateAndReset()

		httpmock.RegisterResponder(http.MethodGet, apiURL+"?api_key="+apiKey,
			httpmock.NewStringResponder(http.StatusUnauthorized, "Invalid api_key"))

		client := newNavasanClient(apiURL, apiKey, http.DefaultClient)

		latest, err := client.Latest(context.Background())
		require.Error(t, err)
		assert.Nil(t, latest)
		assert.Contains(t, err.Error(), "401")
		assert.Contains(t, err.Error(), "Invalid api_key")
	})

	t.Run("transport error", func(t *testing.T) {
		httpmock.Activate(t)
		defer httpmock.DeactivateAndReset()

		httpmock.RegisterResponder(http.MethodGet, apiURL+"?api_key="+apiKey,
			httpmock.NewErrorResponder(errors.New("boom")))

		client := newNavasanClient(apiURL, apiKey, http.DefaultClient)

		latest, err := client.Latest(context.Background())
		require.Error(t, err)
		assert.Nil(t, latest)
	})

	t.Run("invalid json", func(t *testing.T) {
		httpmock.Activate(t)
		defer httpmock.DeactivateAndReset()

		httpmock.RegisterResponder(http.MethodGet, apiURL+"?api_key="+apiKey,
			httpmock.NewStringResponder(http.StatusOK, "not json"))

		client := newNavasanClient(apiURL, apiKey, http.DefaultClient)

		latest, err := client.Latest(context.Background())
		require.Error(t, err)
		assert.Nil(t, latest)
	})

	t.Run("empty feed", func(t *testing.T) {
		httpmock.Activate(t)
		defer httpmock.DeactivateAndReset()

		httpmock.RegisterResponder(http.MethodGet, apiURL+"?api_key="+apiKey,
			httpmock.NewStringResponder(http.StatusOK, "{}"))

		client := newNavasanClient(apiURL, apiKey, http.DefaultClient)

		latest, err := client.Latest(context.Background())
		require.Error(t, err)
		assert.Nil(t, latest)
	})

	t.Run("invalid api url", func(t *testing.T) {
		client := newNavasanClient("://\n", apiKey, http.DefaultClient)

		latest, err := client.Latest(context.Background())
		require.Error(t, err)
		assert.Nil(t, latest)
	})
}
