package androidbridge

import (
	"context"
	"time"

	transactionsv1 "buf.build/gen/go/xskydev/go-money-pb/protocolbuffers/go/gomoneypb/transactions/v1"
)

// Source kinds as sent by the Android capture app.
const (
	SourceNotification = "notification"
	SourceSMS          = "sms"
)

// Transaction type values (app-side taxonomy, data-model.md §2).
const (
	TypeExpense = "expense"
	TypeIncome  = "income"
)

// ErrorCategory values — the app-side taxonomy (research.md R9). Wire-level
// error tokens are narrower: see Wire* constants below
// (contracts/bridge-http-api.md error model).
const (
	ErrorCategoryCapture    = "capture_error"
	ErrorCategoryParse      = "parse_error"
	ErrorCategoryValidation = "validation_error"
	ErrorCategoryNetwork    = "network_error"
	ErrorCategoryServer     = "server_error"
	ErrorCategoryDuplicate  = "duplicate"
)

// Wire-level error tokens as emitted in bridge JSON error bodies
// (contracts/bridge-http-api.md): the app maps these 1:1 onto the taxonomy
// (validation→validation_error, gomoney_unreachable→network_error,
// gomoney_error→server_error).
const (
	WireValidationError = "validation"
	WireUnauthorized    = "unauthorized"
	WireUnreachable     = "gomoney_unreachable"
	WireGoMoneyError    = "gomoney_error"
)

// NormalizedTransaction mirrors the payload of `POST /v1/transactions`
// (contracts/bridge-http-api.md). Raw message text never leaves the phone,
// so there is no rawText field here.
type NormalizedTransaction struct {
	Id            string `json:"id"`
	SourceEventId string `json:"sourceEventId"`
	Source        string `json:"source"`
	Bank          string `json:"bank"`
	AccountHint   string `json:"accountHint"`
	Type          string `json:"type"`
	Amount        int64  `json:"amount"`
	Currency      string `json:"currency"`
	TxAt          string `json:"txAt"` // ISO-8601 with offset
	Description   string `json:"description"`
	Fingerprint   string `json:"fingerprint"`
}

// TxAtTime parses the TxAt field as an ISO-8601 timestamp.
func (t *NormalizedTransaction) TxAtTime() (time.Time, error) {
	return time.Parse(time.RFC3339, t.TxAt)
}

// CreateTransactionResponse is the response body for `POST /v1/transactions`.
type CreateTransactionResponse struct {
	Status       string `json:"status"` // created | duplicate
	GomoneyTxnId string `json:"gomoneyTxnId,omitempty"`
}

// BulkTransactionRequest is the payload of `POST /v1/transactions/bulk`.
type BulkTransactionRequest struct {
	Transactions []*NormalizedTransaction `json:"transactions"`
}

// BulkItemResult is the per-item outcome inside the bulk response.
type BulkItemResult struct {
	Id           string `json:"id"`
	Status       string `json:"status"` // created | duplicate | error
	GomoneyTxnId string `json:"gomoneyTxnId,omitempty"`
	Error        string `json:"error,omitempty"`
}

// BulkTransactionResponse is the response body of the bulk endpoint.
type BulkTransactionResponse struct {
	Results []*BulkItemResult `json:"results"`
}

// PingResponse is the response body of `POST /v1/ping`.
type PingResponse struct {
	Ok               bool   `json:"ok"`
	GomoneyReachable bool   `json:"gomoneyReachable"`
	ServerTime       string `json:"serverTime"`
}

// ErrorResponse is the shared JSON error model (contracts/bridge-http-api.md).
type ErrorResponse struct {
	Error   string   `json:"error"`
	Details []string `json:"details,omitempty"`
}

// GoMoneyClient is the replaceable adapter boundary (FR-013). The bridge talks
// only to this interface; pointing it at a different finance backend means
// implementing this interface.
type GoMoneyClient interface {
	CreateTransaction(
		ctx context.Context,
		req *transactionsv1.CreateTransactionRequest,
	) (*transactionsv1.CreateTransactionResponse, error)

	// Ping reports whether the Go Money backend is currently reachable.
	Ping(ctx context.Context) error
}
