package com.winlator.cmod.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WowVoiceCloserTest {
    @Test
    public void findsTheVoiceProxyByItsShortName() {
        assertTrue(WowVoiceCloser.isVoiceProxy("WowVoiceProxy.e"));
        assertTrue(WowVoiceCloser.isVoiceProxy("WowVoiceProxy.exe"));
    }

    @Test
    public void leavesTheGameAndBlizzardToolsAlone() {
        assertFalse(WowVoiceCloser.isVoiceProxy("WowB-ARM64.exe"));
        assertFalse(WowVoiceCloser.isVoiceProxy("Agent.exe"));
        assertFalse(WowVoiceCloser.isVoiceProxy("Battle.net.exe"));
        assertFalse(WowVoiceCloser.isVoiceProxy("BlizzardError.e"));
    }
}
