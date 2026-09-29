package currency

import (
	"testing"

	"github.com/ft-t/go-money/pkg/database"
)

func TestPersistedRateModeToProtoRemainsUnspecifiedUntilContractIsPublished(t *testing.T) {
	for _, mode := range []database.CurrencyRateMode{
		database.CurrencyRateModeManual,
		database.CurrencyRateModeAutomatic,
	} {
		if got := persistedRateModeToProto(&mode); got != 0 {
			t.Errorf("persisted mode %q mapped to %d, want protobuf UNSPECIFIED (0)", mode, got)
		}
	}

	if got := persistedRateModeToProto(nil); got != 0 {
		t.Errorf("base currency mapped to %d, want protobuf UNSPECIFIED (0)", got)
	}
}
