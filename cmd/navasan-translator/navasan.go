package main

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/url"
	"strings"

	"github.com/cockroachdb/errors"
)

// maxResponseBytes caps how much of the upstream body is read.
const maxResponseBytes = 5 << 20

// flexNumber accepts a JSON value Navasan may encode either as a quoted string
// (the documented form) or as a bare number.
type flexNumber string

func (f *flexNumber) UnmarshalJSON(data []byte) error {
	*f = flexNumber(strings.Trim(string(data), `"`))

	return nil
}

// navasanItem is a single entry of the Navasan /latest/ response.
type navasanItem struct {
	Value flexNumber `json:"value"`
}

// navasanLatest is the flat item-symbol -> rate map returned by /latest/.
type navasanLatest map[string]navasanItem

type navasanClient struct {
	apiURL     string
	apiKey     string
	httpClient *http.Client
}

func newNavasanClient(apiURL, apiKey string, httpClient *http.Client) *navasanClient {
	return &navasanClient{
		apiURL:     apiURL,
		apiKey:     apiKey,
		httpClient: httpClient,
	}
}

// Latest fetches and decodes the whole Navasan rate feed.
func (c *navasanClient) Latest(ctx context.Context) (navasanLatest, error) {
	endpoint, err := url.Parse(c.apiURL)
	if err != nil {
		return nil, errors.Wrap(err, "failed to parse NAVASAN_API_URL")
	}

	query := endpoint.Query()
	query.Set("api_key", c.apiKey)
	endpoint.RawQuery = query.Encode()

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint.String(), nil)
	if err != nil {
		return nil, errors.Wrap(err, "failed to build navasan request")
	}

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return nil, errors.Wrap(err, "failed to call navasan")
	}

	defer func() {
		_ = resp.Body.Close()
	}()

	body, err := io.ReadAll(io.LimitReader(resp.Body, maxResponseBytes))
	if err != nil {
		return nil, errors.Wrap(err, "failed to read navasan response")
	}

	// Navasan answers with a plain-text body on auth and quota errors, not JSON.
	if resp.StatusCode != http.StatusOK {
		return nil, errors.Errorf("navasan returned status %d: %s", resp.StatusCode, strings.TrimSpace(string(body)))
	}

	var latest navasanLatest
	if err = json.Unmarshal(body, &latest); err != nil {
		return nil, errors.Wrap(err, "failed to decode navasan response")
	}

	if len(latest) == 0 {
		return nil, errors.New("navasan returned an empty rate set")
	}

	return latest, nil
}
