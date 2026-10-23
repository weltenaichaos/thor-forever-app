package com.winlator.cmod.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

public class CompanionAddonTest {
    private static CompanionAddon.Source source(Map<String, String> files) {
        return name -> {
            String text = files.get(name);
            if (text == null) throw new IOException("no " + name);
            return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
        };
    }

    private static Map<String, String> addon(String version) {
        Map<String, String> files = new HashMap<>();
        files.put("ThorCompanion.toc", "## Interface: 11508\n## Version: " + version + "\n\nStrip.lua\n");
        files.put("Strip.lua", "-- strip " + version);
        return files;
    }

    private static File tempAddons() throws IOException {
        File dir = Files.createTempDirectory("addons").toFile();
        return dir;
    }

    @Test
    public void installsOnceAndAgainOnlyForANewVersion() throws IOException {
        File addons = tempAddons();
        Map<String, String> v1 = addon("0.24.2");
        assertTrue(CompanionAddon.install(addons, v1.keySet().toArray(new String[0]), source(v1)));
        assertEquals("0.24.2", CompanionAddon.installedVersion(addons));
        assertEquals("-- strip 0.24.2", new String(Files.readAllBytes(new File(addons, "ThorCompanion/Strip.lua").toPath()), StandardCharsets.UTF_8));
        // The same version again: nothing is written.
        assertFalse(CompanionAddon.install(addons, v1.keySet().toArray(new String[0]), source(v1)));
        Map<String, String> v2 = addon("0.25.0");
        assertTrue(CompanionAddon.install(addons, v2.keySet().toArray(new String[0]), source(v2)));
        assertEquals("0.25.0", CompanionAddon.installedVersion(addons));
        assertEquals("-- strip 0.25.0", new String(Files.readAllBytes(new File(addons, "ThorCompanion/Strip.lua").toPath()), StandardCharsets.UTF_8));
        // No .part files are left behind.
        String[] left = new File(addons, "ThorCompanion").list();
        for (String name : left) assertFalse(name, name.endsWith(".part"));
    }

    @Test
    public void installKeepsTheAddonsOwnOtherFilesAndOtherAddons() throws IOException {
        File addons = tempAddons();
        File other = new File(addons, "Questie");
        assertTrue(other.mkdirs());
        Files.write(new File(other, "Questie.toc").toPath(), "## Version: 1".getBytes(StandardCharsets.UTF_8));
        Map<String, String> v1 = addon("0.24.2");
        CompanionAddon.install(addons, v1.keySet().toArray(new String[0]), source(v1));
        assertTrue(new File(other, "Questie.toc").isFile());
    }

    @Test
    public void removeTakesOutOnlyThorCompanion() throws IOException {
        File addons = tempAddons();
        File other = new File(addons, "Questie");
        assertTrue(other.mkdirs());
        Map<String, String> v1 = addon("0.24.2");
        CompanionAddon.install(addons, v1.keySet().toArray(new String[0]), source(v1));
        File record = new File(tempAddons(), "addon-installs.txt");
        CompanionAddon.remove(record, addons);
        assertFalse(new File(addons, "ThorCompanion").exists());
        assertTrue(other.isDirectory());
        assertNull(CompanionAddon.installedVersion(addons));
        // Nothing there: removing again does nothing.
        CompanionAddon.remove(record, addons);
    }

    @Test
    public void removeNeverFollowsALinkedFolder() throws IOException {
        File addons = tempAddons();
        File outside = tempAddons();
        File keep = new File(outside, "keep.txt");
        Files.write(keep.toPath(), "keep".getBytes(StandardCharsets.UTF_8));
        Files.createSymbolicLink(new File(addons, "ThorCompanion").toPath(), outside.toPath());
        try {
            CompanionAddon.remove(new File(outside, "record.txt"), addons);
        } catch (IOException expected) {
            // A link is not an add-on folder: refused.
        }
        assertTrue(keep.isFile());
    }

    @Test
    public void takesTheInterfaceNumberOfTheGame() throws IOException {
        File addons = tempAddons();
        Map<String, String> files = new HashMap<>();
        files.put("ThorCompanion.toc", "## Interface: 16001\n## Version: 0.30.9\n\nStrip.lua\n");
        files.put("Strip.lua", "-- strip");
        String[] names = files.keySet().toArray(new String[0]);
        File toc = new File(addons, "ThorCompanion/ThorCompanion.toc");
        assertTrue(CompanionAddon.install(addons, names, source(files), 16001));
        assertEquals("## Interface: 16001\n## Version: 0.30.9\n\nStrip.lua\n", new String(Files.readAllBytes(toc.toPath()), StandardCharsets.UTF_8));
        assertFalse(CompanionAddon.install(addons, names, source(files), 16001));
        // Unknown game version: what is there stays.
        assertFalse(CompanionAddon.install(addons, names, source(files), 0));
        // A game patch: the .toc gets the new number, once.
        assertTrue(CompanionAddon.install(addons, names, source(files), 16002));
        assertEquals("## Interface: 16002\n## Version: 0.30.9\n\nStrip.lua\n", new String(Files.readAllBytes(toc.toPath()), StandardCharsets.UTF_8));
        assertFalse(CompanionAddon.install(addons, names, source(files), 16002));
        assertEquals("0.30.9", CompanionAddon.installedVersion(addons));
        assertEquals("## Interface: 16100\n## Title: x\n", CompanionAddon.withInterface("## Title: x\n", 16100));
        assertEquals("## Title: x\r\n## Interface: 16100\r\n", CompanionAddon.withInterface("## Title: x\r\n## Interface: 16001, 16002\r\n", 16100));
    }
}
