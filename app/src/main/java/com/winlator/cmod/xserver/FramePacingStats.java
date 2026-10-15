package com.winlator.cmod.xserver;

import java.util.Arrays;
import java.util.Locale;

/**
 * Debug build only: the time between the frames a game presents, so the CPU log can show
 * how even the frame pacing is, not just the frame rate.
 */
public final class FramePacingStats {
    private static final int MAX = 4096;
    private static final long[] intervals = new long[MAX];
    private static int count;
    private static long last;

    private FramePacingStats() {}

    public static synchronized void onPresent() {
        long now = System.nanoTime();
        if (last != 0 && count < MAX) intervals[count++] = now - last;
        if (last != 0) com.winlator.cmod.core.HitchProbe.onFrame(now, now - last);
        last = now;
    }

    /** One line about the frame times since the last call, and resets them. */
    public static synchronized String drain() {
        if (count < 2) {
            count = 0;
            return "frame times: too few frames";
        }
        long[] sorted = Arrays.copyOf(intervals, count);
        Arrays.sort(sorted);
        double sum = 0;
        for (long v : sorted) sum += v;
        double avg = sum / count;
        int slow = 0;
        for (long v : sorted) if (v > avg * 1.5) slow++;
        String line = String.format(Locale.US,
                "frame times ms: avg %.1f  median %.1f  p90 %.1f  p99 %.1f  max %.1f  over 1.5x avg: %d of %d",
                avg / 1e6, sorted[count / 2] / 1e6, sorted[count * 9 / 10] / 1e6,
                sorted[Math.min(count - 1, count * 99 / 100)] / 1e6, sorted[count - 1] / 1e6, slow, count);
        count = 0;
        return line;
    }
}
