package com.winlator.cmod.xserver;

/**
 * Picks when the FPS cap lets the game draw its next frame, on the screen's refresh grid.
 * A plain timer drifts against the screen, so on a 120 Hz screen a 60 fps game shows some
 * frames for 8 ms and others for 25 ms. Releasing every frame on a refresh, every n-th one,
 * keeps each frame on screen for the same time. All times are System.nanoTime() values.
 */
public final class VsyncPacer {
    private VsyncPacer() {}

    /**
     * Screen refreshes per game frame: the fewest that keep the game at or below the cap.
     * 60 fps on 120 Hz is 2, 45 fps on 120 Hz is 3 (40 fps), 45 fps on 60 Hz is 2 (30 fps).
     */
    public static int refreshesPerFrame(long refreshPeriodNs, int targetFps) {
        if (refreshPeriodNs <= 0 || targetFps <= 0) return 1;
        double frames = (1_000_000_000.0 / targetFps) / refreshPeriodNs;
        // Allow 1% slack so 60 fps on a 59.9 Hz screen stays one refresh per frame.
        return Math.max(1, (int)Math.ceil(frames - 0.01));
    }

    /**
     * The release time for the next frame: n refreshes after the previous release, or the next
     * refresh from now when the game fell behind. Always on a refresh and never before now.
     *
     * @param previousDeadline the last release time, or 0 when there was none
     * @param vsyncNs the time of any recent screen refresh
     */
    public static long nextDeadline(long previousDeadline, long now, long vsyncNs,
                                    long refreshPeriodNs, int targetFps) {
        long frameNs = refreshesPerFrame(refreshPeriodNs, targetFps) * refreshPeriodNs;
        long wanted = previousDeadline != 0 && previousDeadline + frameNs >= now
                ? previousDeadline + frameNs : now;
        long deadline = snap(wanted, vsyncNs, refreshPeriodNs);
        if (deadline < now) deadline += ceilDiv(now - deadline, refreshPeriodNs) * refreshPeriodNs;
        return deadline;
    }

    /** The refresh nearest to time. */
    static long snap(long time, long vsyncNs, long periodNs) {
        return vsyncNs + Math.round((double)(time - vsyncNs) / periodNs) * periodNs;
    }

    private static long ceilDiv(long a, long b) {
        return (a + b - 1) / b;
    }
}
