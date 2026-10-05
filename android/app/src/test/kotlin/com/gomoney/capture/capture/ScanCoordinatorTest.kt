package com.gomoney.capture.capture

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 006 scan-coordination tests (T020/T027, contracts/scan-coordination.md §1/§3,
 * plan.md Testing): request path (permission → single-flight → handle →
 * startService fallback) and the transient banner lifecycle (FR-005/006/007).
 */
@RunWith(RobolectricTestRunner::class)
class ScanCoordinatorTest {

    private class FakePermission(var granted: Boolean) : CaptureGate.CapturePermission {
        override fun isNotificationListenerAccessGranted(): Boolean = granted
        override fun isSmsPermissionGranted(): Boolean = true
    }

    private class RecordingHandle : ScanPerformer {
        var scans = 0
        override fun performScan() {
            scans++
        }
    }

    /** A context whose startService is refused the way the platform may refuse it (research R1). */
    private class RefusingContext(base: Context) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun startService(service: Intent): ComponentName? =
            throw SecurityException("startService refused")
    }

    private val permission = FakePermission(granted = true)
    private val handle = RecordingHandle()

    @Before
    fun setUp() {
        ScanCoordinator.reset()
    }

    @After
    fun tearDown() {
        ScanCoordinator.reset()
    }

    // --- T020: request path ---

    @Test
    fun `permission granted and handle present runs the scan on the service`() {
        ScanCoordinator.registerHandle(handle)

        ScanCoordinator.requestScan(permission)

        assertEquals(1, handle.scans)
        assertEquals(ScanUiState.Running, ScanCoordinator.state.value)
    }

    @Test
    fun `missing permission blocks the press and never starts a scan`() {
        ScanCoordinator.registerHandle(handle)
        permission.granted = false

        ScanCoordinator.requestScan(permission)

        assertEquals(ScanUiState.Blocked, ScanCoordinator.state.value)
        assertEquals(0, handle.scans)
    }

    @Test
    fun `startService refusal is caught and reports unavailable instead of crashing`() {
        ScanCoordinator.attach(RefusingContext(ApplicationProvider.getApplicationContext()))

        // Must not throw (research R1 / FR-006: guidance, never a crash).
        ScanCoordinator.requestScan(permission)

        assertEquals(ScanUiState.Unavailable, ScanCoordinator.state.value)
        assertEquals(0, handle.scans)
    }

    @Test
    fun `no handle and no context degrades to unavailable`() {
        ScanCoordinator.requestScan(permission)

        assertEquals(ScanUiState.Unavailable, ScanCoordinator.state.value)
    }

    // --- T027: single-flight + banner lifecycle ---

    @Test
    fun `press while a scan is running is a no-op (single flight, FR-007)`() {
        ScanCoordinator.registerHandle(handle)
        ScanCoordinator.publishRunning()

        ScanCoordinator.requestScan(permission)

        assertEquals(0, handle.scans)
        assertEquals(ScanUiState.Running, ScanCoordinator.state.value)
    }

    @Test
    fun `a new scan replaces the previous result banner (FR-005)`() {
        ScanCoordinator.registerHandle(handle)
        ScanCoordinator.publishResult(ScanCounts(examined = 3, added = 2, skipped = 1))
        assertEquals(
            ScanUiState.Result(examined = 3, added = 2, skipped = 1),
            ScanCoordinator.state.value,
        )

        ScanCoordinator.requestScan(permission)

        assertEquals(ScanUiState.Running, ScanCoordinator.state.value)
        assertEquals(1, handle.scans)
    }

    @Test
    fun `leaving the events tab consumes a shown banner (FR-005)`() {
        ScanCoordinator.publishResult(ScanCounts(examined = 2, added = 0, skipped = 2))
        assertEquals(
            ScanUiState.Result(examined = 2, added = 0, skipped = 2),
            ScanCoordinator.state.value,
        )

        ScanCoordinator.eventsTabVisible = false

        // Consumed — returning to the tab shows nothing stale.
        assertEquals(ScanUiState.Idle, ScanCoordinator.state.value)
        ScanCoordinator.eventsTabVisible = true
        assertEquals(ScanUiState.Idle, ScanCoordinator.state.value)
    }

    @Test
    fun `a result completing while the tab is hidden is consumed as idle`() {
        ScanCoordinator.eventsTabVisible = false

        ScanCoordinator.publishResult(ScanCounts(examined = 1, added = 1, skipped = 0))

        assertEquals(ScanUiState.Idle, ScanCoordinator.state.value)
    }

    @Test
    fun `a running scan keeps its state when the tab is left and resolves while hidden`() {
        ScanCoordinator.publishRunning()
        ScanCoordinator.eventsTabVisible = false

        // The scan continues (state still Running while the tab is away)…
        assertEquals(ScanUiState.Running, ScanCoordinator.state.value)

        // …and completes into Idle instead of a stale banner.
        ScanCoordinator.publishResult(ScanCounts(examined = 1, added = 1, skipped = 0))
        assertEquals(ScanUiState.Idle, ScanCoordinator.state.value)
    }

    @Test
    fun `blocked guidance survives until a new scan replaces it`() {
        permission.granted = false
        ScanCoordinator.requestScan(permission)
        assertEquals(ScanUiState.Blocked, ScanCoordinator.state.value)

        // Re-grant and press again: the banner is replaced by the new scan.
        permission.granted = true
        ScanCoordinator.registerHandle(handle)
        ScanCoordinator.requestScan(permission)
        assertEquals(ScanUiState.Running, ScanCoordinator.state.value)
        assertEquals(1, handle.scans)
    }
}
