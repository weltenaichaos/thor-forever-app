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
        String env = ThorSetup.thorEnvVars("WINEESYNC=1 TU_DEBUG=noconform,sysmem DXVK_HUD=fps VKD3D_SHADER_MODEL=6_6");
        EnvVars vars = new EnvVars(env);
        assertEquals("1", vars.get("WINEESYNC"));
        assertEquals("6_6", vars.get("VKD3D_SHADER_MODEL"));
        assertFalse(vars.has("TU_DEBUG"));
        assertFalse(vars.has("DXVK_HUD"));
        assertEquals("profiled", vars.get("TU_AUTOTUNE_ALGO"));
        assertEquals("dxvk.enableGraphicsPipelineLibrary=false;dxvk.tilerMode=False", vars.get("DXVK_CONFIG"));
        assertEquals("profiled", new EnvVars(ThorSetup.thorEnvVars(null)).get("TU_AUTOTUNE_ALGO"));
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
