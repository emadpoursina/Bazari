package com.gomoney.capture.ui.theme

enum class StatusTone {
    Success,
    Warning,
    Error,
}

data class StatusPresentation(
    val label: String,
    val tone: StatusTone,
) {
    companion object {
        fun from(
            state: String?,
            errorCategory: String? = null,
            hasNormalizedTx: Boolean = true,
        ): StatusPresentation {
            if (!hasNormalizedTx ||
                errorCategory.equals("parse", ignoreCase = true) ||
                state.equals("parse_error", ignoreCase = true)
            ) {
                return StatusPresentation("Parse problem", StatusTone.Error)
            }
            val normalizedState = state?.lowercase()?.trim().orEmpty()
            return when (normalizedState) {
                "sent" -> StatusPresentation("Sent", StatusTone.Success)
                "queued", "sending", "held", "captured", "parsed" -> StatusPresentation("Waiting", StatusTone.Warning)
                "failed" -> StatusPresentation("Couldn't send", StatusTone.Error)
                else -> StatusPresentation("Waiting", StatusTone.Warning)
            }
        }
    }
}
