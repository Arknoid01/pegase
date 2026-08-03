package com.pegasuscorp.orbe.voice;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.Assert.*;

/**
 * Transitions {@link WakeCoordinator} — fakes purs (Log via Robolectric).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class WakeCoordinatorTest {

    private static final long CRASH_COOLDOWN_MS = 5_000L;
    private static final long POST_STT_REARM_MS = 8_000L;

    private FakeRoutes routes;
    private FakeScoGateway sco;
    private FakeScheduler scheduler;
    private WakeCoordinator coord;

    @Before
    public void setUp() {
        routes = new FakeRoutes(AudioRouteObserver.AudioSource.PHONE_BUILTIN);
        sco = new FakeScoGateway();
        scheduler = new FakeScheduler();
        coord = newCoordinator();
    }

    private WakeCoordinator newCoordinator() {
        return new WakeCoordinator(
                routes,
                source -> WakeCoordinator.WakeBackend.SHERPA,
                sco,
                scheduler,
                CRASH_COOLDOWN_MS,
                POST_STT_REARM_MS);
    }

    @Test
    public void start_fromIdle_goesListeningWake() {
        assertTrue(coord.start());
        WakeCoordinator.Snapshot s = coord.getState();
        assertEquals(WakeCoordinator.WakeState.LISTENING_WAKE, s.state);
        assertEquals(AudioRouteObserver.AudioSource.PHONE_BUILTIN, s.source);
        assertEquals(WakeCoordinator.WakeBackend.SHERPA, s.backend);
    }

    @Test
    public void start_fromListening_isIdempotent() {
        assertTrue(coord.start());
        assertTrue(coord.start());
        assertEquals(WakeCoordinator.WakeState.LISTENING_WAKE, coord.getState().state);
    }

    @Test
    public void start_fromHandingOff_rearmsListening() {
        coord.start();
        coord.onWakeDetected();
        assertTrue(coord.start());
        assertEquals(WakeCoordinator.WakeState.LISTENING_WAKE, coord.getState().state);
    }

    @Test
    public void stop_goesIdle() {
        coord.start();
        coord.stop();
        assertEquals(WakeCoordinator.WakeState.IDLE, coord.getState().state);
        assertFalse(coord.wantsListening());
    }

    @Test
    public void start_freezesSourceAndBackend() {
        assertTrue(coord.start());
        routes.source = AudioRouteObserver.AudioSource.BLUETOOTH_HFP;
        WakeCoordinator.Snapshot s = coord.getState();
        assertEquals(AudioRouteObserver.AudioSource.PHONE_BUILTIN, s.source);
        assertEquals(WakeCoordinator.WakeBackend.SHERPA, s.backend);
    }

    @Test
    public void onWakeDetected_fromListening_goesHandingOff() {
        coord.start();
        assertTrue(coord.onWakeDetected());
        assertEquals(WakeCoordinator.WakeState.HANDING_OFF, coord.getState().state);
    }

    @Test
    public void onWakeDetected_fromIdle_isRejected() {
        assertFalse(coord.onWakeDetected());
        assertEquals(WakeCoordinator.WakeState.IDLE, coord.getState().state);
    }

    @Test
    public void requestSttSession_fromIdle_doesNothing() {
        AtomicBoolean called = new AtomicBoolean(false);
        coord.requestSttSession(ok -> called.set(true));
        assertFalse(called.get());
        assertEquals(0, sco.prepareCalls.get());
        assertEquals(WakeCoordinator.WakeState.IDLE, coord.getState().state);
    }

    @Test
    public void requestSttSession_fromHandingOff_goesSttActive() {
        coord.start();
        coord.onWakeDetected();
        AtomicReference<Boolean> ready = new AtomicReference<>();
        coord.requestSttSession(ready::set);
        assertEquals(Boolean.TRUE, ready.get());
        assertEquals(WakeCoordinator.WakeState.STT_ACTIVE, coord.getState().state);
        assertEquals(1, sco.prepareCalls.get());
    }

    @Test
    public void requestSttSession_fromListening_goesSttActive() {
        coord.start();
        AtomicReference<Boolean> ready = new AtomicReference<>();
        coord.requestSttSession(ready::set);
        assertEquals(Boolean.TRUE, ready.get());
        assertEquals(WakeCoordinator.WakeState.STT_ACTIVE, coord.getState().state);
    }

    @Test
    public void requestSttSession_bluetooth_preparesSco() {
        routes.source = AudioRouteObserver.AudioSource.BLUETOOTH_HFP;
        coord = newCoordinator();
        coord.start();
        coord.onWakeDetected();
        coord.requestSttSession(ok -> {});
        assertEquals(AudioRouteObserver.AudioSource.BLUETOOTH_HFP, sco.lastPrepareSource.get());
        assertTrue(coord.releaseSttSession());
        assertEquals(1, sco.releaseCalls.get());
    }

    @Test
    public void requestSttSession_phone_skipsScoRelease() {
        coord.start();
        coord.onWakeDetected();
        coord.requestSttSession(ok -> {});
        assertEquals(AudioRouteObserver.AudioSource.PHONE_BUILTIN, sco.lastPrepareSource.get());
        assertTrue(coord.releaseSttSession());
        assertEquals(0, sco.releaseCalls.get());
    }

    @Test
    public void releaseSttSession_fromListening_isRejected() {
        coord.start();
        assertFalse(coord.releaseSttSession());
        assertEquals(WakeCoordinator.WakeState.LISTENING_WAKE, coord.getState().state);
    }

    @Test
    public void releaseSttSession_delaysListeningWake_withPostSttConstant() {
        coord.start();
        coord.onWakeDetected();
        coord.requestSttSession(ok -> {});
        assertTrue(coord.releaseSttSession());
        assertTrue(coord.isPostSttRearmPending());
        assertEquals(WakeCoordinator.WakeState.STT_ACTIVE, coord.getState().state);
        assertEquals(1, scheduler.pending.size());
        assertEquals(POST_STT_REARM_MS, scheduler.pending.get(0).delayMs);
        assertEquals(WakeCoordinator.POST_STT_REARM_DELAY_MS, POST_STT_REARM_MS);

        scheduler.pending.get(0).runnable.run();
        assertFalse(coord.isPostSttRearmPending());
        assertEquals(WakeCoordinator.WakeState.LISTENING_WAKE, coord.getState().state);
    }

    @Test
    public void onCrashGuardTripped_usesCrashWindowNotPostSttDelay() {
        coord.start();
        coord.onCrashGuardTripped();
        assertEquals(WakeCoordinator.WakeState.COOLDOWN, coord.getState().state);
        assertEquals(1, scheduler.pending.size());
        assertEquals(CRASH_COOLDOWN_MS, scheduler.pending.get(0).delayMs);
        assertNotEquals(POST_STT_REARM_MS, CRASH_COOLDOWN_MS);

        scheduler.pending.get(0).runnable.run();
        assertEquals(WakeCoordinator.WakeState.LISTENING_WAKE, coord.getState().state);
    }

    @Test
    public void onCrashGuardTripped_fromStt_releasesSco() {
        routes.source = AudioRouteObserver.AudioSource.BLUETOOTH_HFP;
        coord = newCoordinator();
        coord.start();
        coord.onWakeDetected();
        coord.requestSttSession(ok -> {});
        coord.onCrashGuardTripped();
        assertEquals(1, sco.releaseCalls.get());
        assertEquals(WakeCoordinator.WakeState.COOLDOWN, coord.getState().state);
        assertFalse(coord.isPostSttRearmPending());
    }

    @Test
    public void requestSttSession_fromCooldown_isRejected() {
        coord.start();
        coord.onCrashGuardTripped();
        AtomicBoolean called = new AtomicBoolean(false);
        coord.requestSttSession(ok -> called.set(true));
        assertFalse(called.get());
        assertEquals(WakeCoordinator.WakeState.COOLDOWN, coord.getState().state);
    }

    @Test
    public void requestSttSession_fromSttActive_isIdempotent() {
        coord.start();
        coord.requestSttSession(ok -> {});
        AtomicInteger calls = new AtomicInteger();
        coord.requestSttSession(ok -> {
            assertTrue(ok);
            calls.incrementAndGet();
        });
        assertEquals(1, calls.get());
        assertEquals(WakeCoordinator.WakeState.STT_ACTIVE, coord.getState().state);
        // Pas de 2ᵉ prepare SCO.
        assertEquals(1, sco.prepareCalls.get());
    }

    @Test
    public void requestSttSession_duringPostSttRearm_cancelsRearmStaysActive() {
        coord.start();
        coord.requestSttSession(ok -> {});
        assertTrue(coord.releaseSttSession());
        assertTrue(coord.isPostSttRearmPending());
        assertEquals(1, scheduler.pending.size());
        AtomicBoolean called = new AtomicBoolean(false);
        coord.requestSttSession(ok -> {
            assertTrue(ok);
            called.set(true);
        });
        assertTrue(called.get());
        assertFalse(coord.isPostSttRearmPending());
        assertEquals(WakeCoordinator.WakeState.STT_ACTIVE, coord.getState().state);
        assertEquals(1, sco.prepareCalls.get());
        // Timer rearm retiré — plus rien à exécuter.
        assertTrue(scheduler.pending.isEmpty());
    }

    @Test
    public void productionConstants_areDistinctNamedSameValue() {
        assertEquals(8_000L, WakeCoordinator.POST_STT_REARM_DELAY_MS);
        assertEquals(8_000L, KwsCrashGuard.CRASH_WINDOW_MS);
        // Même valeur numérique, constantes et sémantiques séparées.
        assertEquals(WakeCoordinator.POST_STT_REARM_DELAY_MS, KwsCrashGuard.CRASH_WINDOW_MS);
    }

    // --- fakes ---

    private static final class FakeRoutes implements WakeCoordinator.AudioSourceReadable {
        volatile AudioRouteObserver.AudioSource source;

        FakeRoutes(AudioRouteObserver.AudioSource initial) {
            this.source = initial;
        }

        @Override
        public AudioRouteObserver.AudioSource currentSource() {
            return source;
        }
    }

    private static final class FakeScoGateway implements WakeCoordinator.ScoGateway {
        final AtomicInteger prepareCalls = new AtomicInteger();
        final AtomicInteger releaseCalls = new AtomicInteger();
        final AtomicReference<AudioRouteObserver.AudioSource> lastPrepareSource =
                new AtomicReference<>();

        @Override
        public void prepareAsync(AudioRouteObserver.AudioSource source,
                                 Consumer<Boolean> onReady) {
            prepareCalls.incrementAndGet();
            lastPrepareSource.set(source);
            if (onReady != null) onReady.accept(true);
        }

        @Override
        public void release() {
            releaseCalls.incrementAndGet();
        }
    }

    private static final class FakeScheduler implements WakeCoordinator.Scheduler {
        static final class Pending {
            final Runnable runnable;
            final long delayMs;

            Pending(Runnable runnable, long delayMs) {
                this.runnable = runnable;
                this.delayMs = delayMs;
            }
        }

        final List<Pending> pending = new ArrayList<>();

        @Override
        public void postDelayed(Runnable r, long delayMs) {
            pending.add(new Pending(r, delayMs));
        }

        @Override
        public void removeCallbacks(Runnable r) {
            pending.removeIf(p -> p.runnable == r);
        }
    }
}
