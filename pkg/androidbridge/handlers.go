package androidbridge

import (
	"context"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"time"

	transactionsv1 "buf.build/gen/go/xskydev/go-money-pb/protocolbuffers/go/gomoneypb/transactions/v1"
	gomoneypbv1 "buf.build/gen/go/xskydev/go-money-pb/protocolbuffers/go/gomoneypb/v1"
	"connectrpc.com/connect"
	"github.com/shopspring/decimal"
)

const bulkMaxItems = 50

// handlePing implements POST /v1/ping (FR-019).
func (s *Server) handlePing(w http.ResponseWriter, r *http.Request) {
	start := s.now()
	reachable := s.client.Ping(r.Context()) == nil

	writeJSON(w, http.StatusOK, PingResponse{
		Ok:               reachable,
		GomoneyReachable: reachable,
		ServerTime:       s.now().Format(time.RFC3339),
	})
	s.log.Request("POST /v1/ping", "", fmt.Sprintf("reachable=%v", reachable), s.now().Sub(start))
}

// handleCreate implements POST /v1/transactions.
func (s *Server) handleCreate(w http.ResponseWriter, r *http.Request) {
	start := s.now()

	var txn NormalizedTransaction
	if !readJSON(w, r, &txn) {
		return
	}

	status, body := s.deliver(r.Context(), &txn)
	writeJSON(w, status, body)
	s.log.Request("POST /v1/transactions", txn.Fingerprint, fmt.Sprintf("http=%d", status), s.now().Sub(start))
}

// handleBulk implements POST /v1/transactions/bulk (max 50 items, per-item results).
func (s *Server) handleBulk(w http.ResponseWriter, r *http.Request) {
	start := s.now()

	var req BulkTransactionRequest
	if !readJSON(w, r, &req) {
		return
	}

	if len(req.Transactions) > bulkMaxItems {
		writeJSON(w, http.StatusBadRequest, ErrorResponse{
			Error:   WireValidationError,
			Details: []string{fmt.Sprintf("bulk limited to %d items", bulkMaxItems)},
		})
		s.log.Request("POST /v1/transactions/bulk", "", "validation", s.now().Sub(start))
		return
	}

	res := &BulkTransactionResponse{Results: make([]*BulkItemResult, 0, len(req.Transactions))}
	for _, txn := range req.Transactions {
		_, body := s.deliver(r.Context(), txn)

		item := &BulkItemResult{Id: txn.Id}
		switch resp := body.(type) {
		case *CreateTransactionResponse:
			item.Status = resp.Status
			item.GomoneyTxnId = resp.GomoneyTxnId
		case *ErrorResponse:
			item.Status = "error"
			item.Error = resp.Error
		case ErrorResponse:
			item.Status = "error"
			item.Error = resp.Error
		}
		res.Results = append(res.Results, item)
	}

	writeJSON(w, http.StatusOK, res)
	s.log.Request("POST /v1/transactions/bulk", "", fmt.Sprintf("items=%d", len(res.Results)), s.now().Sub(start))
}

// deliver is the single delivery path (contracts/bridge-http-api.md):
// validate → mapping/account resolve → dedup lookup (exact then ±2-min window)
// → reserve → Go Money create → commit; rolled back on Go Money failure.
// Returns (httpStatus, responseBody).
func (s *Server) deliver(ctx context.Context, txn *NormalizedTransaction) (int, any) {
	start := s.now()

	if details := validateTransaction(txn); len(details) > 0 {
		return http.StatusBadRequest, ErrorResponse{Error: WireValidationError, Details: details}
	}

	// Account mapping must resolve BEFORE any Go Money call
	// (contracts/gomoney-integration.md — unmatched → 400 without calling).
	mappedAccountID, ok := s.mappings.Resolve(txn.Bank, txn.AccountHint)
	if !ok {
		return http.StatusBadRequest, ErrorResponse{
			Error:   WireValidationError,
			Details: []string{fmt.Sprintf("unmapped account: %s/%s", txn.Bank, txn.AccountHint)},
		}
	}

	txAt, err := txn.TxAtTime()
	if err != nil {
		return http.StatusBadRequest, ErrorResponse{
			Error:   WireValidationError,
			Details: []string{"txAt is not a valid ISO-8601 timestamp"},
		}
	}

	// Serialized dedup-check → reserve → create flow: concurrent duplicate
	// deliveries must produce exactly one Go Money transaction (FR-030).
	s.dedup.DeliverLock().Lock()
	defer s.dedup.DeliverLock().Unlock()

	if hit, err := s.dedup.LookupExact(ctx, txn.Fingerprint); err != nil {
		return http.StatusInternalServerError, ErrorResponse{Error: WireGoMoneyError, Details: []string{"dedup lookup failed"}}
	} else if hit != nil {
		s.log.Operation("dedup.exact", txn.Fingerprint, "duplicate", s.now().Sub(start))
		return http.StatusOK, &CreateTransactionResponse{
			Status:       "duplicate",
			GomoneyTxnId: hit.GomoneyTxnId,
		}
	}

	if hit, err := s.dedup.LookupBucketWindow(ctx, txn.Bank, txn.AccountHint, txn.Type, normalizeDesc(txn.Description), txn.Amount, txAt.Unix()); err != nil {
		return http.StatusInternalServerError, ErrorResponse{Error: WireGoMoneyError, Details: []string{"dedup lookup failed"}}
	} else if hit != nil {
		s.log.Operation("dedup.window", txn.Fingerprint, "duplicate", s.now().Sub(start))
		return http.StatusOK, &CreateTransactionResponse{
			Status:       "duplicate",
			GomoneyTxnId: hit.GomoneyTxnId,
		}
	}

	accountLookupStart := s.now()
	accounts, err := s.client.ListAccounts(ctx)
	accountLookupLatency := s.now().Sub(accountLookupStart)
	if err != nil {
		s.log.Operation("gomoney.accounts.list", txn.Fingerprint, "error", accountLookupLatency)
		return classifyGoMoneyError(err)
	}

	mappedAccount, ok := findGoMoneyAccount(accounts, mappedAccountID)
	if !ok {
		return http.StatusBadRequest, ErrorResponse{
			Error:   WireValidationError,
			Details: []string{"mapped Go Money account was not found"},
		}
	}
	if mappedAccount.Currency != txn.Currency {
		return http.StatusBadRequest, ErrorResponse{
			Error:   WireValidationError,
			Details: []string{"mapped Go Money account currency does not match transaction currency"},
		}
	}

	defaultAccountType := gomoneypbv1.AccountType_ACCOUNT_TYPE_EXPENSE
	defaultAccountName := "expense"
	if txn.Type == TypeIncome {
		defaultAccountType = gomoneypbv1.AccountType_ACCOUNT_TYPE_INCOME
		defaultAccountName = "income"
	}
	defaultAccount, ok := findDefaultGoMoneyAccount(accounts, defaultAccountType)
	if !ok {
		return http.StatusBadRequest, ErrorResponse{
			Error:   WireValidationError,
			Details: []string{fmt.Sprintf("Go Money default %s account was not found", defaultAccountName)},
		}
	}
	// The mapped bank account stays in the captured transaction currency. If
	// the default expense/income account uses another currency, convert that
	// counterpart amount using Go Money's configured rates.
	counterpartAmount := formatIRR(txn.Amount)
	if defaultAccount.Currency != txn.Currency {
		currencyLookupStart := s.now()
		currencies, currencyErr := s.client.ListCurrencies(ctx, []string{txn.Currency, defaultAccount.Currency})
		currencyLookupLatency := s.now().Sub(currencyLookupStart)
		if currencyErr != nil {
			s.log.Operation("gomoney.currencies.list", txn.Fingerprint, "error", currencyLookupLatency)
			return classifyGoMoneyError(currencyErr)
		}

		counterpartAmount, err = convertCurrencyAmount(txn.Amount, txn.Currency, defaultAccount.Currency, currencies)
		if err != nil {
			return http.StatusBadRequest, ErrorResponse{Error: WireValidationError, Details: []string{err.Error()}}
		}
		s.log.Operation("gomoney.currencies.list", txn.Fingerprint, "converted", currencyLookupLatency)
	}

	// Reserve the fingerprint row before calling Go Money so a concurrent
	// identical delivery hits the registry instead of double-creating.
	if err = s.dedup.Reserve(ctx, &DedupEntry{
		Fingerprint: txn.Fingerprint,
		BucketKey:   BucketKey(txn.Bank, txn.AccountHint, txn.Type, txn.Amount, txAt),
		Bank:        txn.Bank,
		AccountHint: txn.AccountHint,
		Type:        txn.Type,
		Amount:      txn.Amount,
		Desc:        normalizeDesc(txn.Description),
		TxUnix:      txAt.Unix(),
		RecordedAt:  s.now().Format(time.RFC3339),
	}); err != nil {
		return http.StatusInternalServerError, ErrorResponse{Error: WireGoMoneyError, Details: []string{"dedup reserve failed"}}
	}

	req, err := s.buildCreateRequest(txn, mappedAccount, defaultAccount, counterpartAmount)
	if err != nil {
		_ = s.dedup.Delete(ctx, txn.Fingerprint)
		return http.StatusBadRequest, ErrorResponse{Error: WireValidationError, Details: []string{err.Error()}}
	}

	startCreate := s.now()
	res, err := s.client.CreateTransaction(ctx, req)
	createLatency := s.now().Sub(startCreate)

	if err != nil {
		_ = s.dedup.Delete(ctx, txn.Fingerprint)
		s.log.Operation("gomoney.create", txn.Fingerprint, "error", createLatency)
		return classifyGoMoneyError(err)
	}

	gomoneyTxnId := extractTxnId(res)
	if err = s.dedup.Commit(ctx, txn.Fingerprint, gomoneyTxnId); err != nil {
		// The transaction exists in Go Money; keep the registry row so a
		// retry is answered as duplicate rather than double-recorded.
		s.log.Operation("gomoney.record", txn.Fingerprint, "recorded", createLatency)
	}

	s.log.Operation("gomoney.create", txn.Fingerprint, "created", createLatency)
	return http.StatusCreated, &CreateTransactionResponse{
		Status:       "created",
		GomoneyTxnId: gomoneyTxnId,
	}
}

// handleGetMappings implements GET /v1/mappings.
func (s *Server) handleGetMappings(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, s.mappings.All())
}

// handlePutMappings implements PUT /v1/mappings (atomic file write).
func (s *Server) handlePutMappings(w http.ResponseWriter, r *http.Request) {
	var mappings map[string]int32
	if err := json.NewDecoder(r.Body).Decode(&mappings); err != nil {
		writeJSON(w, http.StatusBadRequest, ErrorResponse{
			Error:   WireValidationError,
			Details: []string{"invalid mappings JSON"},
		})
		return
	}

	if err := s.mappings.Save(mappings); err != nil {
		writeJSON(w, http.StatusInternalServerError, ErrorResponse{Error: WireGoMoneyError})
		return
	}
	writeJSON(w, http.StatusOK, s.mappings.All())
}

// buildCreateRequest maps a NormalizedTransaction to Go Money's
// CreateTransactionRequest (contracts/gomoney-integration.md). The type is
// expressed through the pb oneof (expense/income) —
// expense → CreateTransactionRequest_Expense, income → _Income.
func (s *Server) buildCreateRequest(
	txn *NormalizedTransaction,
	mappedAccount, defaultAccount GoMoneyAccount,
	counterpartAmount string,
) (*transactionsv1.CreateTransactionRequest, error) {
	txAt, err := txn.TxAtTime()
	if err != nil {
		return nil, errors.New("txAt is not a valid ISO-8601 timestamp")
	}

	amount := formatIRR(txn.Amount)

	req := &transactionsv1.CreateTransactionRequest{
		Title:           fmt.Sprintf("%s [%s/%s]", txn.Description, txn.Bank, txn.AccountHint),
		TransactionDate: newProtoTimestamp(txAt),
		TagIds:          []int32{},
		SkipRules:       true,
	}

	if txn.Type == TypeExpense {
		// Go Money expenses are negative on their source and positive on their
		// destination. The mapped bank account is the source; Go Money's
		// default expense account is the destination. The latter may use a
		// different currency and is converted at the configured Go Money rate.
		req.Transaction = &transactionsv1.CreateTransactionRequest_Expense{
			Expense: &transactionsv1.Expense{
				SourceAmount:         "-" + amount,
				SourceCurrency:       mappedAccount.Currency,
				SourceAccountId:      mappedAccount.ID,
				DestinationAmount:    counterpartAmount,
				DestinationCurrency:  defaultAccount.Currency,
				DestinationAccountId: defaultAccount.ID,
			},
		}
	} else {
		// Income reverses the account roles: Go Money's default income account
		// is the negative source and the mapped bank account is the positive
		// destination. Convert the income source to the default account's
		// currency when it differs from the bank-side transaction currency.
		req.Transaction = &transactionsv1.CreateTransactionRequest_Income{
			Income: &transactionsv1.Income{
				SourceAmount:         "-" + counterpartAmount,
				SourceCurrency:       defaultAccount.Currency,
				SourceAccountId:      defaultAccount.ID,
				DestinationAmount:    amount,
				DestinationCurrency:  mappedAccount.Currency,
				DestinationAccountId: mappedAccount.ID,
			},
		}
	}

	return req, nil
}

func findGoMoneyAccount(accounts []GoMoneyAccount, id int32) (GoMoneyAccount, bool) {
	for _, account := range accounts {
		if account.ID == id {
			return account, true
		}
	}
	return GoMoneyAccount{}, false
}

func findDefaultGoMoneyAccount(accounts []GoMoneyAccount, typ gomoneypbv1.AccountType) (GoMoneyAccount, bool) {
	for _, account := range accounts {
		if account.Type == typ && account.IsDefault {
			return account, true
		}
	}
	return GoMoneyAccount{}, false
}

// normalizeDesc applies the shared description normalization used by both the
// fingerprint inputs (data-model.md §Fingerprint) and the dedup window scan.
func normalizeDesc(description string) string {
	return strings.Join(strings.Fields(strings.ToLower(description)), " ")
}

// formatIRR renders an IRR amount as a decimal string.
func formatIRR(amount int64) string {
	return fmt.Sprintf("%d", amount)
}

// convertCurrencyAmount converts a positive captured amount using Go Money's
// convention: rate = units of currency per one base-currency unit. The result
// is rounded to the destination currency's configured precision, except when
// rounding a small positive amount would produce zero.
func convertCurrencyAmount(amount int64, fromCurrency, toCurrency string, currencies []GoMoneyCurrency) (string, error) {
	if amount <= 0 {
		return "", errors.New("amount must be > 0 for currency conversion")
	}
	if fromCurrency == toCurrency {
		return formatIRR(amount), nil
	}

	byID := make(map[string]GoMoneyCurrency, len(currencies))
	for _, currency := range currencies {
		byID[currency.ID] = currency
	}

	from, ok := byID[fromCurrency]
	if !ok {
		return "", fmt.Errorf("Go Money currency rate is missing for %s", fromCurrency)
	}
	to, ok := byID[toCurrency]
	if !ok {
		return "", fmt.Errorf("Go Money currency rate is missing for %s", toCurrency)
	}
	if to.DecimalPlaces < 0 {
		return "", fmt.Errorf("Go Money currency precision is invalid for %s", toCurrency)
	}

	fromRate, err := decimal.NewFromString(from.Rate)
	if err != nil || !fromRate.IsPositive() {
		return "", fmt.Errorf("Go Money currency rate is invalid for %s", fromCurrency)
	}
	toRate, err := decimal.NewFromString(to.Rate)
	if err != nil || !toRate.IsPositive() {
		return "", fmt.Errorf("Go Money currency rate is invalid for %s", toCurrency)
	}

	converted := decimal.NewFromInt(amount).Div(fromRate).Mul(toRate)
	rounded := converted.Round(to.DecimalPlaces)
	if rounded.IsZero() && converted.IsPositive() {
		return converted.String(), nil
	}
	return rounded.StringFixed(to.DecimalPlaces), nil
}

// classifyGoMoneyError maps a Go Money failure to the contract's 502/500 pair.
func classifyGoMoneyError(err error) (int, any) {
	var connectErr *connect.Error
	if errors.As(err, &connectErr) {
		switch connectErr.Code() {
		case connect.CodeUnavailable, connect.CodeDeadlineExceeded, connect.CodeAborted, connect.CodeCanceled:
			return http.StatusBadGateway, ErrorResponse{
				Error:   ErrorCategoryNetwork,
				Details: []string{WireUnreachable},
			}
		}
	}
	return http.StatusInternalServerError, ErrorResponse{
		Error:   WireGoMoneyError,
		Details: []string{WireGoMoneyError},
	}
}

// extractTxnId pulls the recorded transaction id from the response.
func extractTxnId(res *transactionsv1.CreateTransactionResponse) string {
	if res == nil || res.Transaction == nil {
		return ""
	}
	return fmt.Sprintf("%d", res.Transaction.Id)
}

// validateTransaction enforces the /v1/transactions payload rules
// (contracts/bridge-http-api.md): amount > 0, currency IRR, fingerprint 64-hex,
// enums, ISO-8601 txAt.
func validateTransaction(txn *NormalizedTransaction) []string {
	var details []string

	if txn.Amount <= 0 {
		details = append(details, "amount must be > 0")
	}
	if txn.Currency != "IRR" {
		details = append(details, "currency must be IRR")
	}
	if txn.Type != TypeExpense && txn.Type != TypeIncome {
		details = append(details, "type must be expense or income")
	}
	if txn.Source != SourceNotification && txn.Source != SourceSMS {
		details = append(details, "source must be notification or sms")
	}
	if len(txn.Fingerprint) != 64 || !isHex(txn.Fingerprint) {
		details = append(details, "fingerprint must be 64 hex chars")
	}
	if txn.Bank == "" {
		details = append(details, "bank is required")
	}
	if txn.AccountHint == "" {
		details = append(details, "accountHint is required")
	}
	if _, err := txn.TxAtTime(); err != nil {
		details = append(details, "txAt is not a valid ISO-8601 timestamp")
	}

	return details
}

func isHex(s string) bool {
	_, err := hex.DecodeString(s)
	return err == nil
}
