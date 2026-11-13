package com.gomoney.capture.sync

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gomoney.capture.storage.NormalizedTransaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MemoPromptNotifierTest {

    private lateinit var context: Context
    private lateinit var notificationManager: NotificationManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val app = context as android.app.Application
        org.robolectric.Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    @Test
    fun `show no-ops without POST_NOTIFICATIONS permission without crash`() {
        val app = context as android.app.Application
        org.robolectric.Shadows.shadowOf(app).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val tx = sampleTx(id = "tx-unperm", userMemo = null)
        MemoPromptNotifier.show(context, tx)
        assertEquals(0, notificationManager.activeNotifications.size)
    }

    private fun sampleTx(id: String, userMemo: String?): NormalizedTransaction =
        NormalizedTransaction(
            id = id,
            sourceEventId = "event-$id",
            source = "notification",
            bank = "mellat",
            accountHint = "****1234",
            type = "expense",
            amountMinor = 100_000L,
            currency = "IRR",
            txAt = "2026-11-12T10:00:00Z",
            description = "Groceries",
            rawTextRef = "ref-$id",
            fingerprint = "fp-$id",
            parserName = "MellatParser",
            confidence = "HIGH",
            userMemo = userMemo,
        )

    @Test
    fun `show skips posting when userMemo is non-blank`() {
        val tx = sampleTx(id = "tx-noted", userMemo = "lunch with friend")
        MemoPromptNotifier.show(context, tx)

        assertEquals(0, notificationManager.activeNotifications.size)
    }

    @Test
    fun `show posts notification when userMemo is null or blank`() {
        val txNull = sampleTx(id = "tx-null-memo", userMemo = null)
        MemoPromptNotifier.show(context, txNull)

        assertEquals(1, notificationManager.activeNotifications.size)
        val notif = notificationManager.activeNotifications[0]
        assertEquals(txNull.id.hashCode() and 0x7fffffff, notif.id)
        assertNotNull(notif.notification.contentIntent)

        val txBlank = sampleTx(id = "tx-blank-memo", userMemo = "   ")
        MemoPromptNotifier.show(context, txBlank)

        assertEquals(2, notificationManager.activeNotifications.size)
    }

    @Test
    fun `cancel removes specific notification by txId`() {
        val tx1 = sampleTx(id = "tx-1", userMemo = null)
        val tx2 = sampleTx(id = "tx-2", userMemo = null)
        MemoPromptNotifier.show(context, tx1)
        MemoPromptNotifier.show(context, tx2)
        assertEquals(2, notificationManager.activeNotifications.size)

        MemoPromptNotifier.cancel(context, "tx-1")
        val active = notificationManager.activeNotifications
        assertEquals(1, active.size)
        assertEquals(tx2.id.hashCode() and 0x7fffffff, active[0].id)
    }
}
