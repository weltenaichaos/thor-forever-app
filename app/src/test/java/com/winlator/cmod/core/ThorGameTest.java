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
}
