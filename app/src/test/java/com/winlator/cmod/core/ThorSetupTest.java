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
}
