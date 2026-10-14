package com.winlator.cmod.xserver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class VsyncPacerTest {
    private static final long HZ120 = 8_333_333L;
    private static final long HZ60 = 16_666_667L;

    @Test
    public void picksWholeRefreshesAtOrBelowTheCap() {
        assertEquals(2, VsyncPacer.refreshesPerFrame(HZ120, 60));
        assertEquals(3, VsyncPacer.refreshesPerFrame(HZ120, 45));
        assertEquals(3, VsyncPacer.refreshesPerFrame(HZ120, 40));
        assertEquals(4, VsyncPacer.refreshesPerFrame(HZ120, 30));
        assertEquals(1, VsyncPacer.refreshesPerFrame(HZ60, 60));
        assertEquals(2, VsyncPacer.refreshesPerFrame(HZ60, 45));
        assertEquals(2, VsyncPacer.refreshesPerFrame(HZ60, 30));
        assertEquals(1, VsyncPacer.refreshesPerFrame(16_683_350L, 60));
    }

    @Test
    public void steadyGameGetsEvenlySpacedReleasesOnRefreshes() {
        long vsync = 1_000_000_000L;
        long deadline = VsyncPacer.nextDeadline(0, vsync + 3_000_000L, vsync, HZ120, 60);
        assertOnGrid(deadline, vsync, HZ120);
        for (int i = 0; i < 1000; i++) {
            // The game presents a few ms after each release, before the next one is due.
            long now = deadline + 5_000_000L;
            long next = VsyncPacer.nextDeadline(deadline, now, vsync, HZ120, 60);
            assertEquals(2 * HZ120, next - deadline, 2);
            deadline = next;
        }
    }

    @Test
    public void lateGameIsReleasedOnTheNextRefreshNotBefore() {
        long vsync = 0;
        long previous = 10 * HZ120;
        long now = previous + 3 * HZ120 + 1_000_000L; // a frame and a half late
        long next = VsyncPacer.nextDeadline(previous, now, vsync, HZ120, 60);
        assertTrue(next >= now);
        assertTrue(next - now < HZ120);
        assertOnGrid(next, vsync, HZ120);
    }

    @Test
    public void neverReleasesInThePast() {
        long vsync = 0;
        for (long now = 0; now < 50 * HZ120; now += 777_777L) {
            long next = VsyncPacer.nextDeadline(0, now, vsync, HZ120, 60);
            assertTrue(next >= now);
            assertOnGrid(next, vsync, HZ120);
        }
    }

    @Test
    public void staysOnTheGridWhenTheRefreshTimeIsOld() {
        long vsync = 5 * HZ120;
        long now = vsync + 100_000 * HZ120 + 2_000_000L;
        long next = VsyncPacer.nextDeadline(0, now, vsync, HZ120, 60);
        assertOnGrid(next, vsync, HZ120);
    }

    private static void assertOnGrid(long t, long vsync, long period) {
        long off = Math.floorMod(t - vsync, period);
        assertTrue("off grid by " + off, off <= 2 || period - off <= 2);
    }
}
