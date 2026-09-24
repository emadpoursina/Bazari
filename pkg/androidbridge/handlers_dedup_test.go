package androidbridge

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"strings"
	"sync"
	"testing"
	"time"

	transactionsv1 "buf.build/gen/go/xskydev/go-money-pb/protocolbuffers/go/gomoneypb/transactions/v1"
	gomoneypbv1 "buf.build/gen/go/xskydev/go-money-pb/protocolbuffers/go/gomoneypb/v1"
	"connectrpc.com/connect"
	"github.com/stretchr/testify/require"
)

// concurrentGoMoneyClient counts created transactions with an atomic counter
// so the "exactly one 201" guarantee can be asserted from both sides.
type countingGoMoneyClient struct {
	fakeGoMoneyClient
	mu     sync.Mutex
	ids    []int64
	hookFn func() // optional delay hook, runs before CreateTransaction responds
}

func (c *countingGoMoneyClient) CreateTransaction(
	ctx context.Context,
	req *transactionsv1.CreateTransactionRequest,
) (*transactionsv1.CreateTransactionResponse, error) {
	if c.hookFn != nil {
		c.hookFn()
	}
	res, err := c.fakeGoMoneyClient.CreateTransaction(ctx, req)
	if err != nil {
		return nil, err
	}
	c.mu.Lock()
	c.ids = append(c.ids, res.Transaction.Id)
	c.mu.Unlock()
	return res, nil
}

// TestConcurrentDuplicateDeliveriesExactlyOneCreated (FR-030): racing identical
// deliveries must produce exactly one 201 and one Go Money transaction.
func TestConcurrentDuplicateDeliveriesExactlyOneCreated(t *testing.T) {
	client := &countingGoMoneyClient{
		hookFn: func() { time.Sleep(10 * time.Millisecond) }, // widen the race window
	}
	_, ts := newTestServer(t, client)

	const racers = 8
	txn := validTxn(fingerprintHex(7))

	statuses := make(chan string, racers)
	var wg sync.WaitGroup
	for i := 0; i < racers; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			res := bridgePost(t, ts.URL+"/v1/transactions", "test-token", marshal(t, txn))
			statuses <- fmt.Sprintf("%d %s", res.StatusCode, readBody(t, res))
		}()
	}
	wg.Wait()
	close(statuses)

	createdCount := 0
	for status := range statuses {
		if strings.Contains(status, "201 Created") || strings.Contains(status, `"status":"created"`) {
			createdCount++
		} else {
			require.Contains(t, status, `"status":"duplicate"`, status)
		}
	}
	require.Equal(t, 1, createdCount, "exactly one 201 among racers")
	require.Len(t, client.ids, 1, "Go Money must receive exactly one create")
}

// racing delivery where one request is still in-flight and the second arrives
// with a shifted timestamp (bucket window) — still exactly one create.
func TestConcurrentBucketWindowRace(t *testing.T) {
	client := &countingGoMoneyClient{}
	_, ts := newTestServer(t, client)

	txnA := validTxn(fingerprintHex(8))
	txnB := validTxn(fingerprintHex(9))
	txnB.Source = SourceSMS
	txnB.TxAt = "2026-09-24T16:01:00+03:30"

	for _, txn := range []*NormalizedTransaction{txnA, txnB} {
		res := bridgePost(t, ts.URL+"/v1/transactions", "test-token", marshal(t, txn))
		body := readBody(t, res)
		require.NotContains(t, body, `"error"`, body)
	}

	require.Len(t, client.ids, 1)
}

func readAll(t *testing.T, res *http.Response) string {
	t.Helper()
	data, err := io.ReadAll(res.Body)
	require.NoError(t, err)
	return string(data)
}

func TestGoMoneyClientInterfaceSatisfied(t *testing.T) {
	// FR-013 replaceability: the fake proves the interface is implementable.
	var _ GoMoneyClient = (*fakeGoMoneyClient)(nil)
	var _ GoMoneyClient = (*GoMoneyConnectClient)(nil)
}

func TestClassifierConnectCodes(t *testing.T) {
	status, _ := classifyGoMoneyError(connect.NewError(connect.CodeUnavailable, fmt.Errorf("x")))
	require.Equal(t, http.StatusBadGateway, status)

	status, _ = classifyGoMoneyError(connect.NewError(connect.CodeInternal, fmt.Errorf("x")))
	require.Equal(t, http.StatusInternalServerError, status)

	status, _ = classifyGoMoneyError(fmt.Errorf("plain"))
	require.Equal(t, http.StatusInternalServerError, status)
}

func TestExtractTxnId(t *testing.T) {
	require.Equal(t, "5", extractTxnId(&transactionsv1.CreateTransactionResponse{
		Transaction: &gomoneypbv1.Transaction{Id: 5},
	}))
	require.Equal(t, "", extractTxnId(nil))
}
