package com.gomoney.capture.sync

import com.gomoney.capture.model.ErrorCategory
import com.gomoney.capture.storage.NormalizedTransaction
import com.gomoney.capture.storage.ServerConfiguration
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

/**
 * OkHttp client for the bridge (T030, contracts/bridge-http-api.md):
 * base URL + static bearer token from SettingsRepository;
 * POST /v1/ping, /v1/transactions, /v1/transactions/bulk (used when ≥5 items
 * queued, max 50).
 */
class BridgeClient(
    private val okHttp: OkHttpClient = defaultClient(),
) {

    /**
     * Result of one send: status mapped per FR-014
     * (201→SENT, 200 duplicate→SENT(duplicate), 400→validation_error no
     * auto-retry, 502→network_error retry, 500→server_error retry).
     */
    data class SendResult(
        val status: Status,
        val gomoneyTxnId: String?,
        val errorCategory: ErrorCategory?,
        val errorDetail: String?,
    ) {
        enum class Status { CREATED, DUPLICATE, VALIDATION_ERROR, NETWORK_ERROR, SERVER_ERROR }

        val retryable: Boolean
            get() = status == Status.NETWORK_ERROR || status == Status.SERVER_ERROR

        companion object {
            val created = SendResult(Status.CREATED, null, null, null)
            fun duplicate(gomoneyTxnId: String?) =
                SendResult(Status.DUPLICATE, gomoneyTxnId, ErrorCategory.DUPLICATE, null)

            fun failure(category: ErrorCategory, detail: String?) =
                SendResult(
                    when (category) {
                        ErrorCategory.VALIDATION_ERROR -> Status.VALIDATION_ERROR
                        ErrorCategory.NETWORK_ERROR -> Status.NETWORK_ERROR
                        else -> Status.SERVER_ERROR
                    },
                    null,
                    category,
                    detail,
                )
        }
    }

    data class PingResult(val ok: Boolean, val gomoneyReachable: Boolean, val unauthorized: Boolean)

    data class MemoUpdateResult(val ok: Boolean, val errorDetail: String?)

    // --- public API ---

    suspend fun ping(config: ServerConfiguration): PingResult {
        val request = buildRequest(config, "POST", "/v1/ping", body("{}"))
        return withContext(Dispatchers.IO) {
            runCatching {
                okHttp.newCall(request).execute().use { res ->
                    when {
                        res.code == 401 -> PingResult(false, false, unauthorized = true)
                        res.isSuccessful -> PingResult(true, parsePing(res), false)
                        else -> PingResult(false, false, false)
                    }
                }
            }.getOrElse { PingResult(false, false, false) }
        }
    }

    /** Send one normalized transaction; never throws (network → network_error). */
    suspend fun send(config: ServerConfiguration, tx: NormalizedTransaction): SendResult {
        val request = buildRequest(config, "POST", "/v1/transactions", body(txJson(tx).toString()))
        return executeSend(request)
    }

    /** Bulk drain: used when ≥5 items queued, max 50 per call (T030). */
    suspend fun sendBulk(config: ServerConfiguration, transactions: List<NormalizedTransaction>): List<SendResult> {
        require(transactions.isNotEmpty())
        require(transactions.size <= BULK_MAX_ITEMS) { "bulk limited to $BULK_MAX_ITEMS items" }

        val payload = JSONObject().put("transactions", JSONArray(transactions.map { txJson(it) }))
        val request = buildRequest(config, "POST", "/v1/transactions/bulk", body(payload.toString()))
        return runCatching {
            okHttp.newCall(request).execute().use { res ->
                if (!res.isSuccessful) {
                    // Whole-batch failure (401/502/500) → callers fall back to
                    // single sends with backoff.
                    val category = categoryForCode(res.code)
                    return transactions.map { SendResult.failure(category, null) }
                }
                parseBulkResponse(res)
            }
        }.getOrElse {
            transactions.map { SendResult.failure(ErrorCategory.NETWORK_ERROR, "network unreachable") }
        }
    }

    /** Update the title note for an already-recorded Go Money transaction. */
    suspend fun updateMemo(config: ServerConfiguration, tx: NormalizedTransaction): MemoUpdateResult {
        val payload = JSONObject()
            .put("fingerprint", tx.fingerprint)
            .put("gomoneyTxnId", tx.gomoneyTxnId)
            .put("bank", tx.bank)
            .put("accountHint", tx.accountHint)
            .put("type", tx.type)
            .put("amount", bridgeAmount(tx))
            .put("txAt", tx.txAt)
            .put("description", tx.description)
            .put("memo", tx.userMemo.orEmpty())
        val request = buildRequest(config, "PUT", "/v1/transactions/memo", body(payload.toString()))
        return withContext(Dispatchers.IO) {
            runCatching {
                okHttp.newCall(request).execute().use { res ->
                    if (res.isSuccessful) {
                        MemoUpdateResult(true, null)
                    } else {
                        MemoUpdateResult(false, "memo update rejected (${res.code})")
                    }
                }
            }.getOrElse {
                MemoUpdateResult(false, "network unreachable")
            }
        }
    }

    // --- internals ---

    private fun executeSend(request: Request): SendResult = runCatching {
        okHttp.newCall(request).execute().use { res ->
            when {
                res.code == 201 -> SendResult.created
                res.code == 200 -> SendResult.duplicate(parseGomoneyId(res))
                res.code == 400 -> SendResult.failure(ErrorCategory.VALIDATION_ERROR, readError(res))
                res.code == 401 -> SendResult.failure(ErrorCategory.SERVER_ERROR, "unauthorized")
                res.code == 502 -> SendResult.failure(ErrorCategory.NETWORK_ERROR, "gomoney_unreachable")
                else -> SendResult.failure(ErrorCategory.SERVER_ERROR, "gomoney_error")
            }
        }
    }.getOrElse {
        SendResult.failure(ErrorCategory.NETWORK_ERROR, "network unreachable")
    }

    private fun buildRequest(config: ServerConfiguration, method: String, path: String, body: RequestBody? = null): Request =
        Request.Builder()
            .url(config.serverUrl.trimEnd('/') + path)
            .method(method, body)
            .header("Authorization", "Bearer ${config.bearerToken}")
            .header("Content-Type", "application/json; charset=utf-8")
            .build()

    private fun body(json: String): RequestBody = json.toRequestBody(JSON_MEDIA_TYPE)

    private fun parsePing(res: Response): Boolean {
        val body = res.body?.string() ?: return false
        return runCatching { JSONObject(body).optBoolean("gomoneyReachable", false) }.getOrDefault(false)
    }

    private fun parseGomoneyId(res: Response): String? {
        val body = res.body?.string() ?: return null
        return runCatching {
            val json = JSONObject(body)
            json.opt("gomoneyTxnId")?.toString()
        }.getOrNull()
    }

    private fun readError(res: Response): String {
        val body = res.body?.string().orEmpty()
        return runCatching {
            val json = JSONObject(body)
            val error = json.optString("error", "validation")
            val details = json.optJSONArray("details")
                ?.let { arr -> (0 until arr.length()).joinToString("; ") { arr.optString(it) } }
                ?: ""
            "$error $details".trim()
        }.getOrDefault("validation")
    }

    private fun parseBulkResponse(res: Response): List<SendResult> {
        val body = res.body?.string().orEmpty()
        return runCatching {
            val results = JSONObject(body).optJSONArray("results") ?: return@runCatching emptyList()
            (0 until results.length()).map { i ->
                val item = results.getJSONObject(i)
                when (item.optString("status")) {
                    "created" -> SendResult.created
                    "duplicate" -> SendResult.duplicate(item.opt("gomoneyTxnId")?.toString())
                    else -> SendResult.failure(
                        ErrorCategory.entries.firstOrNull { it.name.lowercase() == item.optString("error") }
                            ?: ErrorCategory.SERVER_ERROR,
                        item.optString("error", "bulk item error"),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun categoryForCode(code: Int): ErrorCategory = when (code) {
        400 -> ErrorCategory.VALIDATION_ERROR
        401 -> ErrorCategory.SERVER_ERROR
        502 -> ErrorCategory.NETWORK_ERROR
        else -> ErrorCategory.SERVER_ERROR
    }

    companion object {
        const val BULK_MIN_ITEMS = 5
        const val BULK_MAX_ITEMS = 50

        private const val IRR_CURRENCY = "IRR"
        private const val IRR_AMOUNT_DIVISOR = 10L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        fun txJson(tx: NormalizedTransaction): JSONObject = JSONObject()
            .put("id", tx.id)
            .put("sourceEventId", tx.sourceEventId)
            .put("source", tx.source)
            .put("bank", tx.bank)
            .put("accountHint", tx.accountHint)
            .put("type", tx.type)
            .put("amount", bridgeAmount(tx))
            .put("currency", tx.currency)
            .put("txAt", tx.txAt)
            .put("description", tx.description)
            .put("memo", tx.userMemo.orEmpty())
            .put("fingerprint", tx.fingerprint)

        // Preserve the captured amount locally; apply the IRR scale adjustment
        // only at the bridge boundary (shared by single and bulk requests).
        private fun bridgeAmount(tx: NormalizedTransaction): Long =
            if (tx.currency == IRR_CURRENCY) tx.amountMinor / IRR_AMOUNT_DIVISOR else tx.amountMinor
    }
}
