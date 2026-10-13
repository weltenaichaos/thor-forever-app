package com.winlator.cmod.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WowStartupAffinityTest {
    @Test
    public void recognisesWowProcessNames() {
        assertTrue(WowStartupAffinity.isWowComm("WowB-ARM64.exe"));
        assertTrue(WowStartupAffinity.isWowComm("Wow.exe"));
        assertTrue(WowStartupAffinity.isWowComm("WowClassic-ARM6")); // cut to 15 characters
        assertFalse(WowStartupAffinity.isWowComm("Battle.net.exe"));
        assertFalse(WowStartupAffinity.isWowComm("wineserver"));
        assertFalse(WowStartupAffinity.isWowComm("Wow"));
    }

    @Test
    public void findsFullExeNameForCutProcessNames() {
        assertEquals("WowB-ARM64.exe", WowStartupAffinity.exeNameFromCmdline("", "WowB-ARM64.exe"));
        assertEquals("WowClassic-ARM64.exe", WowStartupAffinity.exeNameFromCmdline(
                "C:\\Program Files (x86)\\World of Warcraft\\_classic_\\WowClassic-ARM64.exe\0-launcherlogin\0", "WowClassic-ARM6"));
        assertEquals("WowClassic-ARM6", WowStartupAffinity.exeNameFromCmdline("", "WowClassic-ARM6"));
    }

    @Test
    public void startsOnHighestAllowedOnlineCore() {
        assertEquals(0x80, WowStartupAffinity.startupCoreMask(0xff, 0xff));
        assertEquals(0x40, WowStartupAffinity.startupCoreMask(0xff, 0x7f)); // cpu7 switched off
        assertEquals(0x08, WowStartupAffinity.startupCoreMask(0x0f, 0xff)); // container allows cpu0-3
        assertEquals(0, WowStartupAffinity.startupCoreMask(0x0f, 0xf0));
    }

    @Test
    public void parsesCpuLists() {
        assertEquals(0xff, WowStartupAffinity.parseCpuRanges("0-7\n"));
        assertEquals(0xad, WowStartupAffinity.parseCpuRanges("0,2-3,5,7"));
        assertEquals(0, WowStartupAffinity.parseCpuRanges(""));
    }

    @Test
    public void readsCpusAllowedMasks() {
        assertEquals(0xff, WowStartupAffinity.parseHexMask("ff"));
        assertEquals(0x80, WowStartupAffinity.parseHexMask("00000000,00000080"));
        assertEquals(0x7f, WowStartupAffinity.parseHexMask(" 7f\n"));
        assertEquals(0, WowStartupAffinity.parseHexMask(""));
    }
}
