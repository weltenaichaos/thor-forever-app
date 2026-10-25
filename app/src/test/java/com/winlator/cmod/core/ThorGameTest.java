package com.winlator.cmod.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ThorGameTest {
    private static final String BUILD_INFO =
            "Branch!STRING:0|Active!DEC:1|Build Key!HEX:16|Version!STRING:0|Product!STRING:0\r\n"
            + "eu|1|abc|11.2.5.63506|wow\r\n"
            + "eu|1|def|5.0.1.70009|wow_classic_beta\r\n";

    @Test
    public void picksTheVersionOfTheInstalledFlavor() {
        assertEquals("5.0.1.70009", ThorGame.versionFromBuildInfo(BUILD_INFO, "wow_classic_beta"));
        assertEquals("11.2.5.63506", ThorGame.versionFromBuildInfo(BUILD_INFO, "wow"));
    }

    @Test
    public void withoutAFlavorTakesTheFirstRow() {
        assertEquals("11.2.5.63506", ThorGame.versionFromBuildInfo(BUILD_INFO, ""));
        assertEquals("", ThorGame.versionFromBuildInfo("", ""));
    }

    @Test
    public void readsTheProductFromFlavorInfo() {
        assertEquals("wow_classic_beta", ThorGame.flavorProduct("Product Flavor!STRING:0\nwow_classic_beta\n"));
        assertEquals("", ThorGame.flavorProduct(""));
    }

    @Test
    public void findsWowButNotItsTools() {
        assertTrue(ThorGame.isWowExe("E:\\Program Files (x86)\\World of Warcraft\\_classic_beta_\\WowB-ARM64.exe"));
        assertTrue(ThorGame.isWowExe("C:\\Games\\World of Warcraft\\_retail_\\Wow.exe -d3d11"));
        assertFalse(ThorGame.isWowExe("C:\\Games\\World of Warcraft\\_retail_\\Utils\\WowVoiceProxy.exe"));
        assertFalse(ThorGame.isWowExe("C:\\Program Files (x86)\\Battle.net\\Battle.net Launcher.exe"));
        assertFalse(ThorGame.isWowExe(null));
    }

    @Test
    public void findsBattleNet() {
        assertTrue(ThorGame.isBattleNetExe("C:\\Program Files (x86)\\Battle.net\\Battle.net Launcher.exe"));
        assertTrue(ThorGame.isBattleNetExe("C:\\Program Files (x86)\\Battle.net\\Battle.net.exe"));
        assertFalse(ThorGame.isBattleNetExe("E:\\World of Warcraft\\_classic_beta_\\WowB-ARM64.exe"));
        assertFalse(ThorGame.isBattleNetExe(null));
    }

    @Test
    public void namesTheKindOfGameVersion() {
        assertEquals("Beta", ThorGame.kindOf("wow_classic_beta", "_classic_beta_"));
        assertEquals("Beta", ThorGame.kindOf("", "_beta_"));
        assertEquals("Test", ThorGame.kindOf("wow_classic_ptr", "_classic_ptr_"));
        assertEquals("Test", ThorGame.kindOf("wowt", "_ptr_"));
        assertEquals("Test", ThorGame.kindOf("wowxptr", "_xptr_"));
        assertEquals("Test", ThorGame.kindOf("wowlivetest", "_other_"));
        assertEquals("Live", ThorGame.kindOf("wowf", "_forever_"));
        assertEquals("Live", ThorGame.kindOf("wow", "_retail_"));
        assertEquals("Live", ThorGame.kindOf(null, null));
    }

    @Test
    public void namesTheGameFromItsVersion() {
        assertEquals("Forever", ThorGame.titleOf("wow_classic_beta", "1.60.1.70374"));
        assertEquals("Forever", ThorGame.titleOf("wowf", ""));
        assertEquals("Classic Era", ThorGame.titleOf("wow_classic_era", "1.15.9.70003"));
        assertEquals("Anniversary", ThorGame.titleOf("wow_anniversary", "2.5.6.70006"));
        assertEquals("MoP Classic", ThorGame.titleOf("wow_classic", "5.5.4.70579"));
        assertEquals("Cata Classic", ThorGame.titleOf("wow_classic", "4.4.2.60000"));
        assertEquals("Retail", ThorGame.titleOf("wow", "12.1.0.66000"));
        // Without a readable version, the product name decides.
        assertEquals("Classic Era", ThorGame.titleOf("wow_classic_era", ""));
        assertEquals("Anniversary", ThorGame.titleOf("wow_anniversary", null));
        assertEquals("WoW", ThorGame.titleOf("", ""));
    }
}
