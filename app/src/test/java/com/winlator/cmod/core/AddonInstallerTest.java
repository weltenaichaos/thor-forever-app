package com.winlator.cmod.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class AddonInstallerTest {
    @Test
    public void findsTheAddonFoldersButNotTheirLibraries() {
        Map<String, String> roots = AddonInstaller.addonRoots(Arrays.asList(
                "DBM-Core/", "DBM-Core/DBM-Core.toc", "DBM-Core/Libs/LibStub/LibStub.toc",
                "DBM-StatusBarTimers/DBM-StatusBarTimers_Vanilla.toc", "README.md"));
        assertEquals(2, roots.size());
        assertEquals("DBM-Core/", roots.get("DBM-Core"));
        assertEquals("DBM-StatusBarTimers/", roots.get("DBM-StatusBarTimers"));
    }

    @Test
    public void findsAnAddonInsideAWrapperFolder() {
        Map<String, String> roots = AddonInstaller.addonRoots(Arrays.asList(
                "Questie-v10/Questie/Questie-Classic.toc", "Questie-v10/Questie/Questie.lua"));
        assertEquals("Questie-v10/Questie/", roots.get("Questie"));
    }

    @Test
    public void aSourceCodeZipIsNoAddon() {
        assertTrue(AddonInstaller.addonRoots(Arrays.asList("Questie-main/Questie.toc", "Questie-main/Questie.lua")).isEmpty());
    }

    @Test
    public void readsTheTitleWithoutColours() {
        assertEquals("Deadly Boss Mods", AddonInstaller.titleFromToc("﻿## Interface: 11507\r\n## Title:|cffffd200Deadly Boss Mods|r\r\n"));
        assertEquals("", AddonInstaller.titleFromToc("## Interface: 11507\n"));
        assertTrue(AddonInstaller.isTocOf("Details", "Details-Classic.toc"));
        assertFalse(AddonInstaller.isTocOf("Details", "DetailsFramework.toc"));
    }

    @Test
    public void installsIntoTheAddonsFolderAndListsIt() throws IOException {
        File dir = Files.createTempDirectory("addons").toFile();
        File addons = new File(dir, "Interface/AddOns");
        File zip = zip(dir, "Questie-v10/Questie/Questie.toc", "## Title: Questie\n", "Questie-v10/Questie/Modules/Q.lua", "print()", "Questie-v10/CHANGELOG.md", "x");
        List<String> installed = AddonInstaller.install(zip, addons);
        assertEquals(Arrays.asList("Questie"), installed);
        assertTrue(new File(addons, "Questie/Modules/Q.lua").isFile());
        assertFalse(new File(addons, "CHANGELOG.md").exists());
        List<AddonInstaller.Addon> list = AddonInstaller.installed(addons);
        assertEquals(1, list.size());
        assertEquals("Questie", list.get(0).title);
    }

    @Test
    public void neverOverwritesAnInstalledAddon() throws IOException {
        File dir = Files.createTempDirectory("addons").toFile();
        File addons = new File(dir, "AddOns");
        File old = new File(addons, "Questie/Questie.lua");
        old.getParentFile().mkdirs();
        Files.write(old.toPath(), "old".getBytes(StandardCharsets.UTF_8));
        File zip = zip(dir, "Questie/Questie.toc", "## Title: Questie\n", "Questie/Questie.lua", "new");
        try {
            AddonInstaller.install(zip, addons);
            fail();
        } catch (AddonInstaller.AlreadyInstalledException e) {
            assertEquals(Arrays.asList("Questie"), e.folders);
        }
        assertEquals("old", new String(Files.readAllBytes(old.toPath()), StandardCharsets.UTF_8));
        assertFalse(new File(addons, "Questie/Questie.toc").exists());
    }

    @Test
    public void refusesPathsOutsideTheAddonsFolderAndWritesNothing() throws IOException {
        File dir = Files.createTempDirectory("addons").toFile();
        File addons = new File(dir, "AddOns");
        File zip = zip(dir, "Evil/Evil.toc", "## Title: Evil\n", "Evil/../../escaped.txt", "x");
        try {
            AddonInstaller.install(zip, addons);
            fail();
        } catch (IOException expected) {}
        assertFalse(new File(dir, "escaped.txt").exists());
        assertFalse(new File(addons, "Evil").exists());
    }

    private static File zip(File dir, String... nameThenText) throws IOException {
        File zip = new File(dir, "addon.zip");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            for (int i = 0; i < nameThenText.length; i += 2) {
                out.putNextEntry(new ZipEntry(nameThenText[i]));
                out.write(nameThenText[i + 1].getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return zip;
    }
}
