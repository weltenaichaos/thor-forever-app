package com.winlator.cmod.xserver;

import android.os.Handler;
import android.os.HandlerThread;
import android.view.Choreographer;
import android.view.Display;

/**
 * Knows when the screen refreshes, so the FPS cap can release frames on a refresh
 * (see VsyncPacer). Listens to Android's frame clock on its own thread while a game runs.
 */
public final class VsyncClock implements Choreographer.FrameCallback {
    private static VsyncClock current;

    private final HandlerThread thread = new HandlerThread("VsyncClock");
    private final Display display;
    private volatile boolean stopped;
    private volatile long lastVsyncNs;
    private volatile long periodNs;
    private int framesSinceRateCheck;

    private VsyncClock(Display display) {
        this.display = display;
    }

    public static synchronized void start(Display display) {
        if (current != null || display == null) return;
        current = new VsyncClock(display);
        current.thread.start();
        VsyncClock clock = current;
        new Handler(clock.thread.getLooper()).post(() -> Choreographer.getInstance().postFrameCallback(clock));
    }

    public static synchronized void stop() {
        if (current == null) return;
        current.stopped = true;
        current.thread.quitSafely();
        current = null;
    }

    /** The running clock, or null when the FPS cap should use its plain timer. */
    public static synchronized VsyncClock get() {
        return current;
    }

    /** System.nanoTime() of a recent refresh, or 0 before the first one. */
    public long lastVsyncNs() {
        return lastVsyncNs;
    }

    /** Time between refreshes, or 0 before it is known. */
    public long periodNs() {
        return periodNs;
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (stopped) return;
        lastVsyncNs = frameTimeNanos;
        // The rate can change while the game runs (60 Hz switch, Android power saving).
        if (periodNs == 0 || ++framesSinceRateCheck >= 60) {
            framesSinceRateCheck = 0;
            float hz = display.getRefreshRate();
            if (hz > 1f) periodNs = Math.round(1_000_000_000.0 / hz);
        }
        Choreographer.getInstance().postFrameCallback(this);
    }
}
