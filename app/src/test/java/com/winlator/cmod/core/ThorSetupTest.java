package com.winlator.cmod.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public class ThorSetupTest {
    @Test
    public void picksTheArmGameExe() {
        assertEquals("WowB-ARM64.exe", ThorSetup.pickWowExe(new String[] { "WowB.exe", "WowVoiceProxy.exe", "WowB-ARM64.exe", "Data" }));
        assertEquals("Wow.exe", ThorSetup.pickWowExe(new String[] { "WowVoiceProxy.exe", "Wow.exe", "Wow.mfil" }));
        assertNull(ThorSetup.pickWowExe(new String[] { "WowVoiceProxy.exe", "Interface" }));
        assertNull(ThorSetup.pickWowExe(null));
    }

    @Test
    public void setsTheTunedGraphicsSettings() {
        String container = "WINEESYNC=1 TU_DEBUG=noconform,sysmem DXVK_HUD=fps VKD3D_SHADER_MODEL=6_6";
        String env = ThorSetup.thorEnvVars(container);
        // As a game starts: the container's variables, then the shortcut's.
        EnvVars vars = new EnvVars(container);
        vars.putAll(env);
        assertEquals("1", vars.get("WINEESYNC"));
        assertEquals("6_6", vars.get("VKD3D_SHADER_MODEL"));
        assertEquals("", vars.get("TU_DEBUG"));
        assertEquals("", vars.get("DXVK_HUD"));
        assertEquals("profiled", vars.get("TU_AUTOTUNE_ALGO"));
        assertEquals("dxvk.enableGraphicsPipelineLibrary=false;dxvk.tilerMode=False;d3d11.relaxedBarriers=True", vars.get("DXVK_CONFIG"));
        assertEquals("0", vars.get("DXVK_STATE_CACHE"));
        assertEquals("1", vars.get("WRAPPER_NO_PATCH_OPCONSTCOMP"));
        assertEquals("profiled", new EnvVars(ThorSetup.thorEnvVars(null)).get("TU_AUTOTUNE_ALGO"));
    }

    @Test
    public void setsTheFexPresetOfTheWorkingShortcut() {
        EnvVars fex = new EnvVars(ThorSetup.thorFexEnvVars());
        assertEquals("0", fex.get("FEX_TSOENABLED"));
        assertEquals("1", fex.get("FEX_X87REDUCEDPRECISION"));
        assertEquals("5000", fex.get("FEX_MAXINST"));
        assertEquals("mtrack", fex.get("FEX_SMCCHECKS"));
        assertEquals("0", fex.get("FEX_DYNAMICL1CACHE"));
        // A preset is stored as id|name|vars, and presets are split on commas.
        assertFalse(ThorSetup.thorFexEnvVars().contains(",") || ThorSetup.thorFexEnvVars().contains("|"));
    }

    @Test
    public void putsTheGameSettingsInPlaceOnceAndNeverChangesThem() throws java.io.IOException {
        java.io.File wow = java.nio.file.Files.createTempDirectory("wow").toFile();
        assertTrue(ThorSetup.seedWowConfig(wow, new java.io.ByteArrayInputStream("SET portal \"test\"\n".getBytes())));
        java.io.File config = new java.io.File(wow, "WTF/Config-Thor-Forever.wtf");
        assertEquals("SET portal \"test\"\n", new String(java.nio.file.Files.readAllBytes(config.toPath())));
        java.nio.file.Files.write(config.toPath(), "players own".getBytes());
        assertFalse(ThorSetup.seedWowConfig(wow, new java.io.ByteArrayInputStream("new".getBytes())));
        assertEquals("players own", new String(java.nio.file.Files.readAllBytes(config.toPath())));
        assertEquals("-d3d11 -config Config-Thor-Forever.wtf", ThorSetup.WOW_EXEC_ARGS);
    }

    @Test
    public void copiesTheThorDxvkFilesAgainOnce() {
        assertTrue(ThorSetup.needsDxvkRedo("dxvk-2.6.2-thor-arm64x-1;vkd3d-None;None", ""));
        assertFalse(ThorSetup.needsDxvkRedo("dxvk-2.6.2-thor-arm64x-1;vkd3d-None;None", "1"));
        assertFalse(ThorSetup.needsDxvkRedo("dxvk-2.4.1;vkd3d-None;None", ""));
        assertFalse(ThorSetup.needsDxvkRedo("", ""));
    }

    @Test
    public void keepsSpacesInTheShortcutPath() {
        String[] paths = {
                "C:\\Program Files (x86)\\Battle.net\\Battle.net Launcher.exe",
                "C:\\ProgramData\\ThorForever\\Battle.net-Setup.exe",
                "C:\\Program Files (x86)\\World of Warcraft\\_classic_beta_\\WowB-ARM64.exe",
        };
        for (String path : paths) {
            // As Shortcut reads the Exec line back.
            String exec = "env WINEPREFIX=\"/home/xuser/.wine\" wine " + ThorSetup.execPath(path);
            assertEquals(path, StringUtils.unescape(exec.substring(exec.lastIndexOf("wine ") + 4)));
        }
    }

    @Test
    public void treatsTheInstallerAsBattleNet() {
        assertTrue(ThorGame.isBattleNetExe("C:\\ProgramData\\ThorForever\\Battle.net-Setup.exe"));
        assertTrue(ThorGame.isBattleNetSetupExe("D:\\Battle.net-Setup.exe"));
        assertFalse(ThorGame.isBattleNetSetupExe("C:\\Program Files (x86)\\Battle.net\\Battle.net Launcher.exe"));
    }

    @Test
    public void seesTheInstallerRunning() {
        assertTrue(BattleNetWindowWatcher.isInstallerRunning(Arrays.asList("wineserver", "Battle.net-Setu")));
        assertFalse(BattleNetWindowWatcher.isInstallerRunning(Arrays.asList("Battle.net.exe", "Agent.exe")));
    }

    @Test
    public void namesTheShortcutOfAnotherGameVersion() {
        assertEquals("World of Warcraft Forever Live.desktop", ThorSetup.wowShortcutFileName("Live", new String[] { "World of Warcraft Forever.desktop" }));
        assertEquals("World of Warcraft Forever Test 2.desktop", ThorSetup.wowShortcutFileName("Test", new String[] { "world of warcraft forever test.desktop" }));
        assertEquals("World of Warcraft Forever Beta.desktop", ThorSetup.wowShortcutFileName("Beta", null));
        assertTrue(ThorSetup.isThorWowShortcutFile("World of Warcraft Forever.desktop"));
        assertTrue(ThorSetup.isThorWowShortcutFile("World of Warcraft Forever Live.desktop"));
        assertFalse(ThorSetup.isThorWowShortcutFile("World of Warcraft.desktop"));
        assertFalse(ThorSetup.isThorWowShortcutFile("Battle.net.desktop"));
        String exe = "C:\\Program Files (x86)\\World of Warcraft\\_classic_beta_\\WowB-ARM64.exe";
        assertEquals("C:\\Program Files (x86)\\World of Warcraft\\_classic_beta_", ThorSetup.folderOf(exe));
        assertEquals("_classic_beta_", ThorSetup.flavorName(exe));
    }

    @Test
    public void copiesTheSettingsButNotWhatBelongsToOneShortcut() throws java.io.IOException {
        java.io.File file = java.nio.file.Files.createTempFile("wow", ".desktop").toFile();
        java.nio.file.Files.write(file.toPath(), ("[Desktop Entry]\nName=World of Warcraft\nExec=env wine C:\\\\x\\\\Wow.exe\n\n[Extra Data]\n"
                + "envVars=WINEESYNC=1 TU_DEBUG=\nfpsLimit=60\nlastRunAt=1000\nthorChosenAt=5\nuuid=abc\ncustomCoverArtPath=/x.png\n").getBytes());
        java.util.Map<String, String> extras = ThorSetup.copiedExtras(file);
        assertEquals("WINEESYNC=1 TU_DEBUG=", extras.get("envVars"));
        assertEquals("60", extras.get("fpsLimit"));
        assertEquals(2, extras.size());
    }
}
