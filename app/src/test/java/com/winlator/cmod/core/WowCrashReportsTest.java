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

public class WowCrashReportsTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private File write(File file, String text) throws IOException {
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    @Test
    public void copiesNewReportsOnceAndLeavesTheGameFolderAlone() throws IOException {
        File driveC = tmp.newFolder("drive_c");
        File flavor = new File(driveC, "Program Files (x86)/World of Warcraft/_classic_beta_");
        write(new File(flavor, "WowB-ARM64.exe"), "");
        File report = write(new File(flavor, "Errors/Crash-1.txt"), "BC_ASSERT");
        write(new File(flavor, "Errors/Crash-1.log"), "log");
        write(new File(flavor, "Errors/minidump.dmp"), "dump");
        File target = new File(tmp.getRoot(), "logs/wow-errors");

        List<File> first = WowCrashReports.copyNew(driveC, target);
        assertEquals(2, first.size());
        assertEquals("BC_ASSERT", new String(Files.readAllBytes(new File(target, "Crash-1.txt").toPath()), StandardCharsets.UTF_8));
        assertFalse(new File(target, "minidump.dmp").exists());
        assertTrue(report.isFile());

        write(new File(flavor, "Errors/Crash-2.txt"), "second");
        List<File> second = WowCrashReports.copyNew(driveC, target);
        assertEquals(1, second.size());
        assertEquals("Crash-2.txt", second.get(0).getName());
        assertEquals(4, new File(flavor, "Errors").list().length);
    }

    @Test
    public void nothingWithoutAGameOrReports() throws IOException {
        File driveC = tmp.newFolder("empty");
        File target = new File(tmp.getRoot(), "out");
        assertTrue(WowCrashReports.copyNew(driveC, target).isEmpty());
        assertFalse(target.exists());
        File noExe = new File(driveC, "Program Files (x86)/World of Warcraft/_classic_");
        write(new File(noExe, "Errors/Crash.txt"), "x");
        assertTrue(WowCrashReports.copyNew(driveC, target).isEmpty());
    }
}
