package com.gomoney.capture.model

/**
 * Error taxonomy (FR-023, research.md R9) — exactly six categories.
 */
enum class ErrorCategory {
    CAPTURE_ERROR,
    PARSE_ERROR,
    VALIDATION_ERROR,
    NETWORK_ERROR,
    SERVER_ERROR,
    DUPLICATE,
}

/** Capture source kinds (data-model.md §1). */
enum class EventSource {
    NOTIFICATION,
    SMS;

    companion object {
        fun wire(value: String): EventSource? =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

/** Normalized transaction kinds. */
enum class TxType {
    EXPENSE,
    INCOME;

    companion object {
        fun wire(value: String): TxType? =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

/**
 * Delivery lifecycle states (data-model.md §3) — single terminal success `SENT`.
 * `HELD` (005-account-currency-sources): the capture has no currency yet
 * (unbound/stale/blank-currency source); it is never delivered directly —
 * the only legal transition out is `held → queued`, stamped at bind time.
 */
enum class DeliveryState {
    CAPTURED,
    PARSED,
    HELD,
    QUEUED,
    SENDING,
    SENT,
    FAILED,
}

/** How sure a parser is about a parse result. */
enum class Confidence {
    HIGH,
    MEDIUM,
    LOW,
}
