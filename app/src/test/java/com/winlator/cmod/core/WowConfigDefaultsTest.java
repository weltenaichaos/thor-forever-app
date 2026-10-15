package com.winlator.cmod.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class WowConfigDefaultsTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void replacesExistingValuesAndKeepsOtherLines() {
        String in = "SET graphicsQuality \"6\"\nSET textToSpeech \"1\"\nSET gxMaximize \"1\"\n";
        String out = WowConfigDefaults.applyCvars(in);
        assertEquals("SET graphicsQuality \"6\"\nSET textToSpeech \"0\"\nSET gxMaximize \"1\"\n"
                + "SET showScreenNarrationDialog \"0\"\nSET accessibilityScreenNarrationEnabled \"0\"\n", out);
    }

    @Test
    public void keepsWindowsLineEndingsAndAddsMissingNewline() {
        String out = WowConfigDefaults.applyCvars("SET agentUID \"wow_classic_beta\"");
        assertEquals("SET agentUID \"wow_classic_beta\"\nSET showScreenNarrationDialog \"0\"\n"
                + "SET accessibilityScreenNarrationEnabled \"0\"\nSET textToSpeech \"0\"\n", out);
        String crlf = WowConfigDefaults.applyCvars("SET a \"1\"\r\n");
        assertTrue(crlf.endsWith("SET textToSpeech \"0\"\r\n"));
        assertFalse(crlf.replace("\r\n", "").contains("\n"));
    }

    @Test
    public void isUnchangedWhenAlreadyApplied() {
        String once = WowConfigDefaults.applyCvars("SET gxApi \"D3D11\"\n");
        assertEquals(once, WowConfigDefaults.applyCvars(once));
    }

    @Test
    public void writesOnlyIntoWowFlavorFolders() throws IOException {
        File driveC = tmp.newFolder("drive_c");
        File wow = new File(driveC, "Program Files (x86)/World of Warcraft");
        File beta = new File(wow, "_classic_beta_");
        assertTrue(new File(beta, "WTF").mkdirs());
        Files.write(new File(beta, "WowB-ARM64.exe").toPath(), new byte[0]);
        Files.write(new File(beta, "WTF/Config.wtf").toPath(), "SET textToSpeech \"1\"\n".getBytes(StandardCharsets.UTF_8));
        File noExe = new File(wow, "_retail_");
        assertTrue(noExe.mkdirs());
        File other = new File(wow, "Data");
        assertTrue(other.mkdirs());
        Files.write(new File(other, "Wow.exe").toPath(), new byte[0]);

        List<File> written = WowConfigDefaults.apply(driveC);

        assertEquals(1, written.size());
        String config = new String(Files.readAllBytes(new File(beta, "WTF/Config.wtf").toPath()), StandardCharsets.UTF_8);
        assertTrue(config.startsWith("SET textToSpeech \"0\"\n"));
        assertFalse(new File(noExe, "WTF").exists());
        assertFalse(new File(other, "WTF").exists());
        assertTrue(WowConfigDefaults.apply(driveC).isEmpty());
    }

    @Test
    public void doesNothingWithoutWow() throws IOException {
        assertTrue(WowConfigDefaults.apply(tmp.newFolder("empty")).isEmpty());
    }
}
