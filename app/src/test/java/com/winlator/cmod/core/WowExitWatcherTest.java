package com.winlator.cmod.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public class WowExitWatcherTest {
    @Test
    public void seesWowAndItsErrorReporter() {
        assertTrue(WowExitWatcher.isWowRunning(Arrays.asList("wineserver", "WowB-ARM64.exe")));
        assertTrue(WowExitWatcher.isWowRunning(Arrays.asList("explorer.exe", "BlizzardError.e")));
    }

    @Test
    public void agentAndWineAloneDoNotCount() {
        assertFalse(WowExitWatcher.isWowRunning(Arrays.asList("wineserver", "explorer.exe", "Agent.exe", "conhost.exe")));
    }
}
