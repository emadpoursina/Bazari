package androidbridge

import (
	"context"
	"time"

	transactionsv1 "buf.build/gen/go/xskydev/go-money-pb/protocolbuffers/go/gomoneypb/transactions/v1"
	gomoneypbv1 "buf.build/gen/go/xskydev/go-money-pb/protocolbuffers/go/gomoneypb/v1"
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
// so there is no rawText field here. The three optional id fields are additive
// (notification-engine bridge-api.md); when absent, behavior is unchanged.
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
	Memo          string `json:"memo,omitempty"`
	Fingerprint   string `json:"fingerprint"`

	// AccountId, when present, is the server account the transaction is
	// recorded against (a bound user source). It takes precedence over the
	// (bank, accountHint) mapping lookup.
	AccountId *int32 `json:"accountId,omitempty"`
	// DestinationAccountId sets the Go Money destination account on creation.
	DestinationAccountId *int32 `json:"destinationAccountId,omitempty"`
	// CategoryId sets the Go Money category on creation.
	CategoryId *int32 `json:"categoryId,omitempty"`
}

// GoMoneyAccount contains the account fields the Android bridge needs for
// account resolution plus the label/type projection used by the
// `GET /v1/accounts` selector (notification-engine bridge-api.md). It still
// excludes balances, notes, IBANs and every other user data field.
type GoMoneyAccount struct {
	ID        int32
	Type      gomoneypbv1.AccountType
	TypeName  string // lowercased Go Money account type, e.g. "expense"
	Label     string // human-readable account name
	Currency  string
	IsDefault bool
}

// GoMoneyCategory contains only the category fields the selector needs.
type GoMoneyCategory struct {
	ID    int32
	Label string
}

// AccountSummary is the minimal `GET /v1/accounts` projection.
type AccountSummary struct {
	Id        int32  `json:"id"`
	Label     string `json:"label"`
	Currency  string `json:"currency"`
	Type      string `json:"type"`
	IsDefault bool   `json:"isDefault"`
}

// AccountListResponse is the response body of `GET /v1/accounts`.
type AccountListResponse struct {
	Accounts []AccountSummary `json:"accounts"`
}

// CategorySummary is the minimal `GET /v1/categories` projection.
type CategorySummary struct {
	Id    int32  `json:"id"`
	Label string `json:"label"`
}

// CategoryListResponse is the response body of `GET /v1/categories`.
type CategoryListResponse struct {
	Categories []CategorySummary `json:"categories"`
}

// AssignmentUpdateRequest sets/changes the destination account and/or category
// of a transaction already recorded in Go Money (bridge-api.md
// `PUT /v1/transactions/assignment`). Identity fields match the memo-update
// lookup so exact/cross-source/duplicate-acked captures all resolve.
type AssignmentUpdateRequest struct {
	Fingerprint          string `json:"fingerprint"`
	GomoneyTxnId         string `json:"gomoneyTxnId,omitempty"`
	Bank                 string `json:"bank"`
	AccountHint          string `json:"accountHint"`
	Type                 string `json:"type"`
	Amount               int64  `json:"amount"`
	TxAt                 string `json:"txAt"`
	Description          string `json:"description"`
	DestinationAccountId *int32 `json:"destinationAccountId"`
	CategoryId           *int32 `json:"categoryId"`
}

// AssignmentUpdateResponse confirms the assignment reached Go Money.
type AssignmentUpdateResponse struct {
	Status       string `json:"status"` // updated
	GomoneyTxnId string `json:"gomoneyTxnId,omitempty"`
}

// GoMoneyCurrency contains the configured exchange-rate data the bridge needs
// when a bank account and its default counterpart use different currencies.
type GoMoneyCurrency struct {
	ID            string
	Rate          string
	DecimalPlaces int32
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

// MemoUpdateRequest updates the user note on a transaction already recorded in Go Money.
type MemoUpdateRequest struct {
	Fingerprint  string `json:"fingerprint"`
	GomoneyTxnId string `json:"gomoneyTxnId,omitempty"`
	Bank         string `json:"bank"`
	AccountHint  string `json:"accountHint"`
	Type         string `json:"type"`
	Amount       int64  `json:"amount"`
	TxAt         string `json:"txAt"`
	Description  string `json:"description"`
	Memo         string `json:"memo"`
}

// MemoUpdateResponse confirms the note was written to Go Money.
type MemoUpdateResponse struct {
	Status       string `json:"status"` // updated
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
	ListAccounts(ctx context.Context) ([]GoMoneyAccount, error)
	ListCurrencies(ctx context.Context, ids []string) ([]GoMoneyCurrency, error)
	ListCategories(ctx context.Context) ([]GoMoneyCategory, error)

	CreateTransaction(
		ctx context.Context,
		req *transactionsv1.CreateTransactionRequest,
	) (*transactionsv1.CreateTransactionResponse, error)
	GetTransactionByID(ctx context.Context, id int64) (*gomoneypbv1.Transaction, error)
	UpdateTransaction(
		ctx context.Context,
		req *transactionsv1.UpdateTransactionRequest,
	) (*transactionsv1.UpdateTransactionResponse, error)

	// Ping reports whether the Go Money backend is currently reachable.
	Ping(ctx context.Context) error
}
