package androidbridge

import (
	"crypto/subtle"
	"net/http"
	"strings"
)

// AuthMiddleware validates the static bearer token on every request
// (clarified Q1=B: plain HTTP + static bearer token on a trusted LAN).
// Mismatch → 401 {"error":"unauthorized"} with a constant-time token compare.
func AuthMiddleware(token string, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !CheckAuth(token, r) {
			writeJSON(w, http.StatusUnauthorized, ErrorResponse{Error: "unauthorized"})
			return
		}
		next.ServeHTTP(w, r)
	})
}

// CheckAuth returns true when the request carries the expected bearer token.
func CheckAuth(token string, r *http.Request) bool {
	header := r.Header.Get("Authorization")
	const prefix = "Bearer "
	if !strings.HasPrefix(header, prefix) {
		return false
	}

	provided := strings.TrimSpace(header[len(prefix):])
	return subtle.ConstantTimeCompare([]byte(provided), []byte(token)) == 1
}
