package com.gomoney.capture.model

/**
 * Notification-engine enums (data-model.md §1/§7, contracts/source-template.md).
 */

/** How a user-defined source receives messages. */
enum class Channel {
    NOTIFICATION,
    SMS;

    /** Lowercase wire value stored in Room and compared against RawEvent.source. */
    val wire: String get() = name.lowercase()

    companion object {
        fun wire(value: String): Channel? =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }

        fun fromEventSource(source: EventSource): Channel = when (source) {
            EventSource.NOTIFICATION -> NOTIFICATION
            EventSource.SMS -> SMS
        }
    }
}

/** Resolved transaction direction for a user-source capture. */
enum class Direction {
    INCOME,
    EXPENSE;

    val wire: String get() = name.lowercase()
}

/** Assignment sync state for a captured transaction's destination/category. */
enum class AssignmentSyncState {
    PENDING,
    SYNCED;

    val wire: String get() = name.lowercase()

    companion object {
        fun wire(value: String): AssignmentSyncState? =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}
