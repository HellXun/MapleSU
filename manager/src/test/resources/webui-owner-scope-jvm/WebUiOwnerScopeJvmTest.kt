package com.maplesu.core.ui.webui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.webkit.WebView
import dev.maplesu.manager.modules.ModuleWebUiLauncher
import dev.maplesu.manager.modules.webui.ModuleWebUiSessions
import dev.maplesu.manager.modules.webui.ModuleWebUiSessions.OwnerScope
import dev.maplesu.manager.modules.webui.WebUiFailureReason
import dev.maplesu.manager.runtime.ultra.UltraEnvironmentProbe
import dev.maplesu.manager.runtime.ultra.UltraEnvironmentProbe.Snapshot
import dev.maplesu.manager.runtime.ultra.UltraEnvironmentProbe.State
import dev.maplesu.manager.runtime.ultra.UltraRuntimeClient.ManagerOwnerIdentity
import dev.maplesu.manager.workmode.ModeActivation
import dev.maplesu.manager.workmode.ModeRuntime
import dev.maplesu.manager.workmode.WorkMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/** Isolated platform fixtures; producer/capture/Intent methods are sliced from live sources. */
class WebUiOwnerScopeJvmTest {
    private val context = Context()
    private val a = OwnerScope(1012345, 41L, "owner-a")
    private val b = a.copy(epoch = 42L)

    @Before fun activate() {
        ModeRuntime.active = WorkMode.ULTRA
        ModeRuntime.activation = ModeActivation()
        observe(a)
    }

    private fun observe(owner: OwnerScope?, state: State = State.Running, root: Boolean = true,
                        activation: ModeActivation = ModeRuntime.activation) {
        UltraEnvironmentProbe.publishSnapshot(Snapshot(state = state, kernelRootVerified = root,
            managerOwnerIdentity = owner?.let { ManagerOwnerIdentity(it.fullUid, it.epoch, it.hostInstance) }), activation)
    }

    private class Page(val state: WebUIState, val activity: Activity, val bridge: WebViewInterface) : AutoCloseable {
        override fun close() { state.dispose(activity); activity.drain() }
    }

    private fun page(id: String, owner: OwnerScope?): Page {
        val state = WebUIState().apply { webView = WebView() }
        val activity = Activity()
        assertTrue(state.bindModuleSession(activity, id, ModuleWebUiSessions.revision(id), owner))
        val bridge = WebViewInterface(state, { error("Owner regression must not open a shell") })
        state.javascriptBridge = bridge
        return Page(state, activity, bridge)
    }

    @Test fun observedChangeRetiresBothModuleKindsBeforeUiCleanup() {
        page("maplesu:scoped", a).use { native ->
            page("script-scoped", a).use { script ->
                page("legacy-unscoped", null).use { legacy ->
                    val nativeIo = native.bridge.io()
                    val scriptIo = script.bridge.io()
                    observe(b)
                    assertFalse(native.state.isDisposed)
                    assertFalse(script.state.isDisposed)
                    assertFalse(nativeIo.canOpenStream())
                    assertFalse(scriptIo.canOpenStream())
                    assertEquals("", native.bridge.exec("ignored"))
                    assertTrue(legacy.bridge.io().canOpenStream())
                    native.activity.drain()
                    script.activity.drain()
                    assertTrue(native.state.isDisposed)
                    assertTrue(script.state.isDisposed)
                    assertFalse(legacy.state.isDisposed)
                }
            }
        }
    }

    @Test fun stateAndRootChangesWithSameOwnerKeepThePage() {
        page("same-owner", a).use {
            observe(a, State.Unknown, false)
            observe(a, State.Fault, false)
            observe(a, State.Running, true)
            assertTrue(it.bridge.io().canOpenStream())
            assertTrue(it.activity.queued.isEmpty())
        }
    }

    @Test fun missingIdentityKeepsLastOwnerForTheNextRealChange() {
        page("missing-owner", a).use {
            observe(null, State.Unknown, false)
            assertNull(UltraEnvironmentProbe.webUiOwnerScope(context))
            assertTrue(it.bridge.io().canOpenStream())
            observe(b)
            assertFalse(it.bridge.io().canOpenStream())
        }
    }

    @Test fun everyOwnerTupleFieldParticipatesInRetirement() {
        listOf(a.copy(fullUid = a.fullUid + 100000), b, a.copy(hostInstance = "owner-b")).forEachIndexed { i, next ->
            observe(a)
            page("tuple-field-$i", a).use {
                observe(next)
                assertFalse("Tuple change $i survived", it.bridge.io().canOpenStream())
            }
        }
    }

    @Test fun capturedOldOwnerIsRejectedAtLateBinding() {
        val captured = UltraEnvironmentProbe.webUiOwnerScope(context)
        assertEquals(a, captured)
        observe(b)
        val state = WebUIState()
        assertFalse(state.bindModuleSession(Activity(), "late-owner", 0L, captured))
        assertTrue(state.isDisposed)
    }

    @Test fun queuedOldOwnerCleanupLeavesTheReboundPageCurrent() {
        page("rebound-owner", a).use {
            val old = it.bridge
            observe(b)
            assertFalse(old.io().canOpenStream())
            assertTrue(it.state.bindModuleSession(it.activity, "rebound-owner", 0L, b))
            val fresh = WebViewInterface(it.state, { error("Unexpected shell") })
            it.state.javascriptBridge = fresh
            it.activity.drain()
            assertFalse(it.state.isDisposed)
            assertTrue(fresh.io().canOpenStream())
            assertFalse(old.io().canOpenStream())
        }
    }

    @Test fun closingAndCommittingRemoveBothSubscriptionIndexes() {
        val closed = AtomicInteger()
        val committed = AtomicInteger()
        val one = ModuleWebUiSessions.subscribe("closed-index", 0L, a) { closed.incrementAndGet() }
        val two = ModuleWebUiSessions.subscribe("committed-index", 0L, a) { committed.incrementAndGet() }
        assertNotNull(one)
        assertNotNull(two)
        one!!.close()
        one.close()
        ModuleWebUiSessions.committed("committed-index")
        observe(b)
        ModuleWebUiSessions.committed("closed-index")
        two!!.close()
        assertEquals(0, closed.get())
        assertEquals(1, committed.get())
    }

    @Test fun commitAndObservedChangeDeliverOneRetirement() {
        repeat(32) { i ->
            observe(a)
            val id = "commit-owner-race-$i"
            val retired = AtomicInteger()
            val subscription = ModuleWebUiSessions.subscribe(id, ModuleWebUiSessions.revision(id), a) { retired.incrementAndGet() }
            assertNotNull(subscription)
            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)
            val commit = Thread { ready.countDown(); start.await(); ModuleWebUiSessions.committed(id) }
            val owner = Thread { ready.countDown(); start.await(); observe(b) }
            commit.start(); owner.start(); ready.await(); start.countDown()
            commit.join(2000); owner.join(2000)
            assertFalse(commit.isAlive)
            assertFalse(owner.isAlive)
            subscription!!.close()
            assertEquals("Race $i", 1, retired.get())
        }
    }

    @Test fun throwingListenerDoesNotBlockOtherRetirements() {
        val retired = AtomicInteger()
        ModuleWebUiSessions.subscribe("throwing-owner", 0L, a) { error("expected callback failure") }!!.use {
            ModuleWebUiSessions.subscribe("following-owner", 0L, a) { retired.incrementAndGet() }!!.use {
                observe(b)
                assertEquals(1, retired.get())
            }
        }
    }

    @Test fun staleActivationDoesNotProduceAnOwnerChange() {
        page("old-activation", a).use {
            observe(b, activation = ModeActivation())
            assertTrue(it.bridge.io().canOpenStream())
            assertNull(UltraEnvironmentProbe.webUiOwnerScope(context))
            observe(a)
            assertTrue(it.bridge.io().canOpenStream())
            observe(b)
            assertFalse(it.bridge.io().canOpenStream())
        }
    }

    @Test fun ksuSnapshotDoesNotProduceOrCaptureUltraOwner() {
        page("ksu-observation", a).use {
            ModeRuntime.active = WorkMode.LITE
            observe(b)
            assertNull(UltraEnvironmentProbe.webUiOwnerScope(context))
            assertTrue(it.bridge.io().canOpenStream())
            ModeRuntime.active = WorkMode.ULTRA
            observe(a)
            assertTrue(it.bridge.io().canOpenStream())
            observe(b)
            assertFalse(it.bridge.io().canOpenStream())
        }
    }

    @Test fun launcherCarriesCapturedOwnerAndActivityRejectsStaleIntent() = runBlocking {
        val captured = UltraEnvironmentProbe.webUiOwnerScope(context)
        observe(b)
        assertNull(ModuleWebUiLauncher.launchCaptured(context, "native-owner", 0L, captured))
        val staleIntent = context.started.single()
        assertEquals(a.epoch, staleIntent.getLongExtra(ModuleWebUIActivity.EXTRA_OWNER_EPOCH, -1L))
        repeat(2) {
            val page = ModuleWebUIActivity().apply { intent = staleIntent; bindRequest("maplesu:native-owner") }
            assertTrue(page.isFinishing)
            assertTrue(page.pageState.isDisposed)
        }
        assertNull(ModuleWebUiLauncher.launchCaptured(context, "native-owner", 0L,
            UltraEnvironmentProbe.webUiOwnerScope(context)))
        val fresh = ModuleWebUIActivity().apply { intent = context.started.last(); bindRequest("maplesu:native-owner") }
        try { assertFalse(fresh.isFinishing); assertTrue(fresh.pageState.isModuleSessionCurrent) }
        finally { fresh.pageState.dispose(fresh) }
    }

    @Test fun partialOwnerExtrasRejectWhileLegacyIntentKeepsCompatibility() {
        val partial = ModuleWebUIActivity().apply {
            intent = Intent().putExtra(ModuleWebUIActivity.EXTRA_OWNER_UID, a.fullUid)
            bindRequest("partial-owner")
        }
        assertTrue(partial.isFinishing)
        assertTrue(partial.pageState.isDisposed)
        val legacy = ModuleWebUIActivity().apply { bindRequest("legacy-intent") }
        try { assertFalse(legacy.isFinishing); assertTrue(legacy.pageState.isModuleSessionCurrent) }
        finally { legacy.pageState.dispose(legacy) }
    }

    @Test fun currentOwnerDoesNotMaskAStaleModuleRevision() {
        val id = "module-revision-owner"
        val revision = ModuleWebUiSessions.revision(id)
        ModuleWebUiSessions.committed(id)
        val state = WebUIState()
        assertFalse(state.bindModuleSession(Activity(), id, revision, a))
        assertTrue(state.isDisposed)
    }

    @Test fun launcherPreservesActivityFailure() = runBlocking {
        context.startFailure = IllegalStateException("fixture activity unavailable")
        val failure = ModuleWebUiLauncher.launchCaptured(context, "failed-launch", 0L, a)
        assertEquals(WebUiFailureReason.ACTIVITY_UNRESOLVED, failure?.reason)
        assertTrue(context.started.isEmpty())
    }
}
