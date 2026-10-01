package androidbridge

import (
	"context"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

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

// handleUpdateMemo updates only the title of a recorded transaction. It
// reuses the current Go Money financial fields and a stable base title, so
// this cannot change its amount, accounts, date, or tags.
func (s *Server) handleUpdateMemo(w http.ResponseWriter, r *http.Request) {
	start := s.now()

	var req MemoUpdateRequest
	if !readJSON(w, r, &req) {
		return
	}
	if details := validateMemoUpdate(&req); len(details) > 0 {
		writeJSON(w, http.StatusBadRequest, ErrorResponse{Error: WireValidationError, Details: details})
		return
	}

	// Serialize against create/dedup writes and other title updates.
	s.dedup.DeliverLock().Lock()
	defer s.dedup.DeliverLock().Unlock()

	entry, err := s.lookupMemoEntry(r, &req)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, ErrorResponse{Error: WireGoMoneyError, Details: []string{"memo lookup failed"}})
		return
	}
	if entry == nil || entry.GomoneyTxnId == "" {
		writeJSON(w, http.StatusNotFound, ErrorResponse{Error: WireValidationError, Details: []string{"recorded transaction not found"}})
		return
	}

	gomoneyTxnID, err := strconv.ParseInt(entry.GomoneyTxnId, 10, 64)
	if err != nil || gomoneyTxnID <= 0 {
		writeJSON(w, http.StatusInternalServerError, ErrorResponse{Error: WireGoMoneyError, Details: []string{"invalid Go Money transaction id"}})
		return
	}

	existing, err := s.client.GetTransactionByID(r.Context(), gomoneyTxnID)
	if err != nil {
		status, body := classifyGoMoneyError(err)
		writeJSON(w, status, body)
		s.log.Request("PUT /v1/transactions/memo", req.Fingerprint, fmt.Sprintf("http=%d", status), s.now().Sub(start))
		return
	}
	baseRequest, err := createRequestFromExisting(existing)
	if err != nil {
		status, body := classifyGoMoneyError(err)
		writeJSON(w, status, body)
		return
	}
	baseTitle := entry.MemoBaseTitle
	currentTitle := existing.GetTitle()
	saveBaseTitle := false
	if baseTitle == "" {
		baseTitle = currentTitle
		saveBaseTitle = true
	} else if currentTitle != baseTitle && !strings.HasPrefix(currentTitle, baseTitle+" — ") {
		// Respect a title manually edited in Go Money since capture.
		baseTitle = currentTitle
		saveBaseTitle = true
	}
	if saveBaseTitle {
		if err = s.dedup.SaveMemoBaseTitle(r.Context(), entry.Fingerprint, baseTitle); err != nil {
			writeJSON(w, http.StatusInternalServerError, ErrorResponse{Error: WireGoMoneyError, Details: []string{"memo metadata save failed"}})
			return
		}
	}

	baseRequest.Title = titleWithMemo(baseTitle, req.Memo)
	_, err = s.client.UpdateTransaction(r.Context(), &transactionsv1.UpdateTransactionRequest{
		Id:          gomoneyTxnID,
		Transaction: baseRequest,
	})
	if err != nil {
		status, body := classifyGoMoneyError(err)
		writeJSON(w, status, body)
		s.log.Request("PUT /v1/transactions/memo", req.Fingerprint, fmt.Sprintf("http=%d", status), s.now().Sub(start))
		return
	}

	writeJSON(w, http.StatusOK, MemoUpdateResponse{Status: "updated", GomoneyTxnId: entry.GomoneyTxnId})
	s.log.Request("PUT /v1/transactions/memo", req.Fingerprint, "updated", s.now().Sub(start))
}

// handleListAccounts implements GET /v1/accounts (notification-engine
// bridge-api.md). Reads Go Money ListAccounts with the bridge service token and
// returns a minimal projection for the app's source-binding and
// destination-account selectors.
func (s *Server) handleListAccounts(w http.ResponseWriter, r *http.Request) {
	start := s.now()
	accounts, err := s.client.ListAccounts(r.Context())
	if err != nil {
		status, body := classifyGoMoneyError(err)
		writeJSON(w, status, body)
		s.log.Request("GET /v1/accounts", "", fmt.Sprintf("http=%d", status), s.now().Sub(start))
		return
	}
	summaries := make([]AccountSummary, 0, len(accounts))
	for _, account := range accounts {
		summaries = append(summaries, AccountSummary{
			Id:        account.ID,
			Label:     account.Label,
			Currency:  account.Currency,
			Type:      account.TypeName,
			IsDefault: account.IsDefault,
		})
	}
	writeJSON(w, http.StatusOK, AccountListResponse{Accounts: summaries})
	s.log.Request("GET /v1/accounts", "", fmt.Sprintf("count=%d", len(summaries)), s.now().Sub(start))
}

// handleListCategories implements GET /v1/categories (notification-engine
// bridge-api.md) for the transaction category selector.
func (s *Server) handleListCategories(w http.ResponseWriter, r *http.Request) {
	start := s.now()
	categories, err := s.client.ListCategories(r.Context())
	if err != nil {
		status, body := classifyGoMoneyError(err)
		writeJSON(w, status, body)
		s.log.Request("GET /v1/categories", "", fmt.Sprintf("http=%d", status), s.now().Sub(start))
		return
	}
	summaries := make([]CategorySummary, 0, len(categories))
	for _, category := range categories {
		summaries = append(summaries, CategorySummary{Id: category.ID, Label: category.Label})
	}
	writeJSON(w, http.StatusOK, CategoryListResponse{Categories: summaries})
	s.log.Request("GET /v1/categories", "", fmt.Sprintf("count=%d", len(summaries)), s.now().Sub(start))
}

// handleUpdateAssignment implements PUT /v1/transactions/assignment
// (bridge-api.md): set/change the destination account and/or category of a
// transaction already recorded in Go Money, updating the SAME transaction and
// never creating a second one (FR-020, SC-005).
func (s *Server) handleUpdateAssignment(w http.ResponseWriter, r *http.Request) {
	start := s.now()

	var req AssignmentUpdateRequest
	if !readJSON(w, r, &req) {
		return
	}
	if details := validateAssignmentUpdate(&req); len(details) > 0 {
		writeJSON(w, http.StatusBadRequest, ErrorResponse{Error: WireValidationError, Details: details})
		return
	}

	// Serialize against create/dedup writes and other assignment updates.
	s.dedup.DeliverLock().Lock()
	defer s.dedup.DeliverLock().Unlock()

	entry, err := s.lookupAssignmentEntry(r, &req)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, ErrorResponse{Error: WireGoMoneyError, Details: []string{"assignment lookup failed"}})
		return
	}
	if entry == nil || entry.GomoneyTxnId == "" {
		writeJSON(w, http.StatusNotFound, ErrorResponse{Error: WireValidationError, Details: []string{"recorded transaction not found"}})
		return
	}

	gomoneyTxnID, err := strconv.ParseInt(entry.GomoneyTxnId, 10, 64)
	if err != nil || gomoneyTxnID <= 0 {
		writeJSON(w, http.StatusInternalServerError, ErrorResponse{Error: WireGoMoneyError, Details: []string{"invalid Go Money transaction id"}})
		return
	}

	existing, err := s.client.GetTransactionByID(r.Context(), gomoneyTxnID)
	if err != nil {
		status, body := classifyGoMoneyError(err)
		writeJSON(w, status, body)
		s.log.Request("PUT /v1/transactions/assignment", req.Fingerprint, fmt.Sprintf("http=%d", status), s.now().Sub(start))
		return
	}
	baseRequest, err := createRequestFromExisting(existing)
	if err != nil {
		status, body := classifyGoMoneyError(err)
		writeJSON(w, status, body)
		return
	}

	if req.DestinationAccountId != nil {
		accounts, accountsErr := s.client.ListAccounts(r.Context())
		if accountsErr != nil {
			status, body := classifyGoMoneyError(accountsErr)
			writeJSON(w, status, body)
			s.log.Request("PUT /v1/transactions/assignment", req.Fingerprint, fmt.Sprintf("http=%d", status), s.now().Sub(start))
			return
		}
		if _, found := findGoMoneyAccount(accounts, *req.DestinationAccountId); !found {
			writeJSON(w, http.StatusBadRequest, ErrorResponse{
				Error:   WireValidationError,
				Details: []string{"destination Go Money account was not found"},
			})
			return
		}
		applyDestinationAccount(baseRequest, *req.DestinationAccountId)
	}
	if req.CategoryId != nil {
		categories, categoryErr := s.client.ListCategories(r.Context())
		if categoryErr != nil {
			status, body := classifyGoMoneyError(categoryErr)
			writeJSON(w, status, body)
			s.log.Request("PUT /v1/transactions/assignment", req.Fingerprint, fmt.Sprintf("http=%d", status), s.now().Sub(start))
			return
		}
		if !categoryExists(categories, *req.CategoryId) {
			writeJSON(w, http.StatusBadRequest, ErrorResponse{
				Error:   WireValidationError,
				Details: []string{"Go Money category was not found"},
			})
			return
		}
		baseRequest.CategoryId = req.CategoryId
	}

	_, err = s.client.UpdateTransaction(r.Context(), &transactionsv1.UpdateTransactionRequest{
		Id:          gomoneyTxnID,
		Transaction: baseRequest,
	})
	if err != nil {
		status, body := classifyGoMoneyError(err)
		writeJSON(w, status, body)
		s.log.Request("PUT /v1/transactions/assignment", req.Fingerprint, fmt.Sprintf("http=%d", status), s.now().Sub(start))
		return
	}

	writeJSON(w, http.StatusOK, AssignmentUpdateResponse{Status: "updated", GomoneyTxnId: entry.GomoneyTxnId})
	s.log.Request("PUT /v1/transactions/assignment", req.Fingerprint, "updated", s.now().Sub(start))
}

func validateAssignmentUpdate(req *AssignmentUpdateRequest) []string {
	var details []string
	if len(req.Fingerprint) != 64 {
		details = append(details, "fingerprint must be 64 hexadecimal characters")
	} else if _, err := hex.DecodeString(req.Fingerprint); err != nil {
		details = append(details, "fingerprint must be 64 hexadecimal characters")
	}
	if req.DestinationAccountId != nil && *req.DestinationAccountId <= 0 {
		details = append(details, "destinationAccountId must be a positive account id")
	}
	if req.CategoryId != nil && *req.CategoryId <= 0 {
		details = append(details, "categoryId must be a positive category id")
	}
	return details
}

// lookupAssignmentEntry reuses the memo-update lookup semantics so exact,
// cross-source, and duplicate-acked captures all resolve to one recorded
// transaction (bridge-api.md).
func (s *Server) lookupAssignmentEntry(r *http.Request, req *AssignmentUpdateRequest) (*DedupEntry, error) {
	if req.GomoneyTxnId != "" {
		entry, err := s.dedup.LookupGoMoneyTxnID(r.Context(), req.GomoneyTxnId)
		if err != nil {
			return entry, err
		}
		if entry != nil && (entry.Fingerprint == req.Fingerprint || assignmentIdentityMatches(entry, req)) {
			return entry, nil
		}
	}
	if entry, err := s.dedup.LookupExact(r.Context(), req.Fingerprint); err != nil || entry != nil {
		return entry, err
	}
	txAt, err := time.Parse(time.RFC3339, req.TxAt)
	if err != nil || req.Bank == "" || req.AccountHint == "" || req.Amount <= 0 {
		return nil, nil
	}
	return s.dedup.LookupBucketWindow(
		r.Context(),
		req.Bank,
		req.AccountHint,
		req.Type,
		normalizeDesc(req.Description),
		req.Amount,
		txAt.Unix(),
	)
}

func assignmentIdentityMatches(entry *DedupEntry, req *AssignmentUpdateRequest) bool {
	txAt, err := time.Parse(time.RFC3339, req.TxAt)
	if err != nil {
		return false
	}
	return entry.Bank == req.Bank &&
		entry.AccountHint == req.AccountHint &&
		entry.Type == req.Type &&
		entry.Amount == req.Amount &&
		entry.Desc == normalizeDesc(req.Description) &&
		abs64(entry.TxUnix-txAt.Unix()) <= 120
}

// applyDestinationAccount sets the chosen Go Money destination account on the
// create/update request for either transaction shape.
func applyDestinationAccount(req *transactionsv1.CreateTransactionRequest, id int32) {
	if expense := req.GetExpense(); expense != nil {
		expense.DestinationAccountId = id
	}
	if income := req.GetIncome(); income != nil {
		income.DestinationAccountId = id
	}
}

func categoryExists(categories []GoMoneyCategory, id int32) bool {
	for _, category := range categories {
		if category.ID == id {
			return true
		}
	}
	return false
}

func validateMemoUpdate(req *MemoUpdateRequest) []string {
	var details []string
	if len(req.Fingerprint) != 64 {
		details = append(details, "fingerprint must be 64 hexadecimal characters")
	} else if _, err := hex.DecodeString(req.Fingerprint); err != nil {
		details = append(details, "fingerprint must be 64 hexadecimal characters")
	}
	if utf8.RuneCountInString(req.Memo) > 200 {
		details = append(details, "memo must be 200 characters or fewer")
	}
	return details
}

func (s *Server) lookupMemoEntry(r *http.Request, req *MemoUpdateRequest) (*DedupEntry, error) {
	if req.GomoneyTxnId != "" {
		entry, err := s.dedup.LookupGoMoneyTxnID(r.Context(), req.GomoneyTxnId)
		if err != nil {
			return entry, err
		}
		if entry != nil && (entry.Fingerprint == req.Fingerprint || memoIdentityMatches(entry, req)) {
			return entry, nil
		}
	}
	if entry, err := s.dedup.LookupExact(r.Context(), req.Fingerprint); err != nil || entry != nil {
		return entry, err
	}
	txAt, err := time.Parse(time.RFC3339, req.TxAt)
	if err != nil || req.Bank == "" || req.AccountHint == "" || req.Amount <= 0 {
		return nil, nil
	}
	return s.dedup.LookupBucketWindow(
		r.Context(),
		req.Bank,
		req.AccountHint,
		req.Type,
		normalizeDesc(req.Description),
		req.Amount,
		txAt.Unix(),
	)
}

func memoIdentityMatches(entry *DedupEntry, req *MemoUpdateRequest) bool {
	txAt, err := time.Parse(time.RFC3339, req.TxAt)
	if err != nil {
		return false
	}
	return entry.Bank == req.Bank &&
		entry.AccountHint == req.AccountHint &&
		entry.Type == req.Type &&
		entry.Amount == req.Amount &&
		entry.Desc == normalizeDesc(req.Description) &&
		abs64(entry.TxUnix-txAt.Unix()) <= 120
}

func createRequestFromExisting(txn *gomoneypbv1.Transaction) (*transactionsv1.CreateTransactionRequest, error) {
	if txn == nil || txn.GetTransactionDate() == nil {
		return nil, errors.New("Go Money transaction is missing its date")
	}
	request := &transactionsv1.CreateTransactionRequest{
		Title:                    txn.GetTitle(),
		Notes:                    txn.GetNotes(),
		Extra:                    txn.GetExtra(),
		TagIds:                   txn.GetTagIds(),
		TransactionDate:          txn.GetTransactionDate(),
		ReferenceNumber:          txn.ReferenceNumber,
		InternalReferenceNumbers: txn.GetInternalReferenceNumbers(),
		CategoryId:               txn.CategoryId,
		GroupKey:                 txn.GroupKey,
		SkipRules:                true,
	}
	switch txn.GetType() {
	case gomoneypbv1.TransactionType_TRANSACTION_TYPE_EXPENSE:
		expense := &transactionsv1.Expense{
			SourceAmount:         txn.GetSourceAmount(),
			SourceCurrency:       txn.GetSourceCurrency(),
			SourceAccountId:      txn.GetSourceAccountId(),
			FxSourceAmount:       txn.FxSourceAmount,
			FxSourceCurrency:     txn.FxSourceCurrency,
			DestinationAccountId: txn.GetDestinationAccountId(),
			DestinationAmount:    txn.GetDestinationAmount(),
			DestinationCurrency:  txn.GetDestinationCurrency(),
		}
		request.Transaction = &transactionsv1.CreateTransactionRequest_Expense{Expense: expense}
	case gomoneypbv1.TransactionType_TRANSACTION_TYPE_INCOME:
		income := &transactionsv1.Income{
			SourceAccountId:      txn.GetSourceAccountId(),
			DestinationAccountId: txn.GetDestinationAccountId(),
			SourceAmount:         txn.GetSourceAmount(),
			DestinationAmount:    txn.GetDestinationAmount(),
			SourceCurrency:       txn.GetSourceCurrency(),
			DestinationCurrency:  txn.GetDestinationCurrency(),
		}
		request.Transaction = &transactionsv1.CreateTransactionRequest_Income{Income: income}
	default:
		return nil, errors.New("Go Money transaction type is not supported for memo updates")
	}
	return request, nil
}

func titleWithMemo(baseTitle, memo string) string {
	memo = strings.Join(strings.Fields(strings.TrimSpace(memo)), " ")
	if memo == "" {
		return baseTitle
	}
	return baseTitle + " — " + memo
}

// deliver is the single delivery path (contracts/bridge-http-api.md):
// validate → mapping/account resolve → dedup lookup (exact then ±2-min window)
// → reserve → Go Money create → commit; rolled back on Go Money failure.
// Returns (httpStatus, responseBody).
func (s *Server) deliver(ctx context.Context, txn *NormalizedTransaction) (int, any) {
	start := s.now()

	// 005: currency is validated after trimming; keep the trimmed value so the
	// mapped-account currency comparison sees the same code.
	txn.Currency = strings.TrimSpace(txn.Currency)

	if details := validateTransaction(txn); len(details) > 0 {
		return http.StatusBadRequest, ErrorResponse{Error: WireValidationError, Details: details}
	}

	// Account mapping must resolve BEFORE any Go Money call
	// (contracts/gomoney-integration.md — unmatched → 400 without calling).
	// A user-defined source sends an explicit accountId, which takes
	// precedence over the (bank, accountHint) mapping (R7, FR-015).
	mappedAccountID, mapped := s.mappings.Resolve(txn.Bank, txn.AccountHint)
	if !mapped && txn.AccountId == nil {
		return http.StatusBadRequest, ErrorResponse{
			Error:   WireValidationError,
			Details: []string{fmt.Sprintf("unmapped account: %s/%s", txn.Bank, txn.AccountHint)},
		}
	}
	if txn.AccountId != nil && *txn.AccountId <= 0 {
		return http.StatusBadRequest, ErrorResponse{
			Error:   WireValidationError,
			Details: []string{"accountId must be a positive account id"},
		}
	}
	if txn.DestinationAccountId != nil && *txn.DestinationAccountId <= 0 {
		return http.StatusBadRequest, ErrorResponse{
			Error:   WireValidationError,
			Details: []string{"destinationAccountId must be a positive account id"},
		}
	}
	if txn.CategoryId != nil && *txn.CategoryId <= 0 {
		return http.StatusBadRequest, ErrorResponse{
			Error:   WireValidationError,
			Details: []string{"categoryId must be a positive category id"},
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
	if txn.AccountId != nil {
		// The bound source account takes precedence over the static mapping.
		mappedAccount, ok = findGoMoneyAccount(accounts, *txn.AccountId)
	}
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

	req, err := s.buildCreateRequest(txn, mappedAccount, defaultAccount, counterpartAmount)
	if err != nil {
		return http.StatusBadRequest, ErrorResponse{Error: WireValidationError, Details: []string{err.Error()}}
	}

	// Optional destination account / category chosen by the user (FR-017/018).
	// Both must resolve in the server-provided catalogs, else 400.
	if txn.DestinationAccountId != nil {
		if _, found := findGoMoneyAccount(accounts, *txn.DestinationAccountId); !found {
			return http.StatusBadRequest, ErrorResponse{
				Error:   WireValidationError,
				Details: []string{"destination Go Money account was not found"},
			}
		}
		applyDestinationAccount(req, *txn.DestinationAccountId)
	}
	if txn.CategoryId != nil {
		categories, categoryErr := s.client.ListCategories(ctx)
		if categoryErr != nil {
			s.log.Operation("gomoney.categories.list", txn.Fingerprint, "error", s.now().Sub(start))
			return classifyGoMoneyError(categoryErr)
		}
		if !categoryExists(categories, *txn.CategoryId) {
			return http.StatusBadRequest, ErrorResponse{
				Error:   WireValidationError,
				Details: []string{"Go Money category was not found"},
			}
		}
		req.CategoryId = txn.CategoryId
	}

	baseTitle := req.Title
	req.Title = titleWithMemo(req.Title, txn.Memo)

	// Reserve the fingerprint row before calling Go Money so a concurrent
	// identical delivery hits the registry instead of double-creating. Store
	// the stable title before appending the user memo for later edit/clear.
	if err = s.dedup.Reserve(ctx, &DedupEntry{
		Fingerprint:   txn.Fingerprint,
		BucketKey:     BucketKey(txn.Bank, txn.AccountHint, txn.Type, txn.Amount, txAt),
		Bank:          txn.Bank,
		AccountHint:   txn.AccountHint,
		Type:          txn.Type,
		Amount:        txn.Amount,
		Desc:          normalizeDesc(txn.Description),
		TxUnix:        txAt.Unix(),
		RecordedAt:    s.now().Format(time.RFC3339),
		MemoBaseTitle: baseTitle,
	}); err != nil {
		return http.StatusInternalServerError, ErrorResponse{Error: WireGoMoneyError, Details: []string{"dedup reserve failed"}}
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
// (005 contracts/bridge-api.md): amount > 0, currency non-empty with 1–16
// letters/digits (NOT IRR-only — non-rial bound accounts send their own code;
// the mapped-account currency-match check stays in deliver), fingerprint
// 64-hex, enums, ISO-8601 txAt.
func validateTransaction(txn *NormalizedTransaction) []string {
	var details []string

	if txn.Amount <= 0 {
		details = append(details, "amount must be > 0")
	}
	trimmedCurrency := strings.TrimSpace(txn.Currency)
	switch {
	case trimmedCurrency == "":
		details = append(details, "currency is required")
	case len(trimmedCurrency) > 16 || !isLettersDigits(trimmedCurrency):
		details = append(details, "currency is invalid")
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
	if utf8.RuneCountInString(txn.Memo) > 200 {
		details = append(details, "memo must be 200 characters or fewer")
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

// isLettersDigits accepts ISO-like currency codes: 1–16 letters/digits.
func isLettersDigits(s string) bool {
	for _, r := range s {
		isLetter := (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z')
		isDigit := r >= '0' && r <= '9'
		if !isLetter && !isDigit {
			return false
		}
	}
	return len(s) > 0
}
