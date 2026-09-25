package androidbridge

import (
	"encoding/json"
	"net/http"
	"time"
)

// Server is the bridge HTTP surface (contracts/bridge-http-api.md).
type Server struct {
	token    string
	client   GoMoneyClient
	dedup    *FingerprintRegistry
	mappings *MappingStore
	log      *BridgeLogger
	now      func() time.Time
}

// ServerOption customizes the server (test injection point).
type ServerOption func(*Server)

// WithClock overrides the clock (tests).
func WithClock(now func() time.Time) ServerOption {
	return func(s *Server) { s.now = now }
}

// NewServer wires the HTTP server to its dependencies.
func NewServer(token string, client GoMoneyClient, dedup *FingerprintRegistry, mappings *MappingStore, opts ...ServerOption) *Server {
	s := &Server{
		token:    token,
		client:   client,
		dedup:    dedup,
		mappings: mappings,
		log:      NewBridgeLogger(),
		now:      time.Now,
	}
	for _, opt := range opts {
		opt(s)
	}
	return s
}

// Handler returns the fully-routed http.Handler (auth applied).
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("POST /v1/ping", s.handlePing)
	mux.HandleFunc("POST /v1/transactions", s.handleCreate)
	mux.HandleFunc("POST /v1/transactions/bulk", s.handleBulk)
	mux.HandleFunc("PUT /v1/transactions/memo", s.handleUpdateMemo)
	mux.HandleFunc("GET /v1/mappings", s.handleGetMappings)
	mux.HandleFunc("PUT /v1/mappings", s.handlePutMappings)
	return AuthMiddleware(s.token, mux)
}

func writeJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}

func readJSON(w http.ResponseWriter, r *http.Request, dst any) bool {
	dec := json.NewDecoder(r.Body)
	dec.DisallowUnknownFields()
	if err := dec.Decode(dst); err != nil {
		writeJSON(w, http.StatusBadRequest, ErrorResponse{
			Error:   WireValidationError,
			Details: []string{"invalid JSON body"},
		})
		return false
	}
	return true
}
