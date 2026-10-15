package com.winlator.cmod.core;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/**
 * The Forever Companion's addon (ThorCompanion), which the app carries in its assets. The
 * start screen's Companion button puts it into the game's AddOns folder, and takes it out
 * again when the companion is switched off.
 */
public final class CompanionAddon {
    public static final String FOLDER = "ThorCompanion";
    public static final String ASSET_DIR = "thor/companion/" + FOLDER;
    private static final String TOC = FOLDER + ".toc";

    /** Opens one of the addon's files by name. */
    public interface Source {
        InputStream open(String name) throws IOException;
    }

    private CompanionAddon() {}

    /** The "## Version:" of a .toc, or null. */
    public static String version(InputStream toc) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(toc, StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null; ) {
                if (line.startsWith("## Version:")) return line.substring("## Version:".length()).trim();
            }
        }
        return null;
    }

    /** The version in the game's AddOns folder, or null when the addon isn't there. */
    public static String installedVersion(File addonsDir) {
        File toc = new File(new File(addonsDir, FOLDER), TOC);
        if (!toc.isFile()) return null;
        try (InputStream in = new java.io.FileInputStream(toc)) {
            return version(in);
        } catch (IOException e) {
            return null;
        }
    }

    /** Puts the bundled addon into addonsDir when that holds another version; true when it did. */
    public static boolean install(Context context, File addonsDir) throws IOException {
        String[] files = context.getAssets().list(ASSET_DIR);
        if (files == null || files.length == 0) throw new IOException("The app has no companion addon in it");
        return install(addonsDir, files, name -> context.getAssets().open(ASSET_DIR + "/" + name));
    }

    /**
     * Puts the addon's files into addonsDir/ThorCompanion when that holds another version.
     * Nothing is deleted: each file is written next to its old copy and renamed over it, and
     * the .toc goes last, so an install that stops halfway is done again next time.
     */
    public static boolean install(File addonsDir, String[] files, Source source) throws IOException {
        String bundled;
        try (InputStream toc = source.open(TOC)) {
            bundled = version(toc);
        }
        if (bundled == null) throw new IOException("The companion addon has no version");
        if (bundled.equals(installedVersion(addonsDir))) return false;
        File dir = new File(addonsDir, FOLDER);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Could not create " + dir);
        String[] order = files.clone();
        java.util.Arrays.sort(order, (a, b) -> Boolean.compare(a.equals(TOC), b.equals(TOC)));
        for (String name : order) {
            if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.startsWith(".")) continue;
            File target = new File(dir, name);
            File part = new File(dir, name + ".part");
            try (InputStream in = source.open(name); OutputStream out = new FileOutputStream(part)) {
                byte[] buffer = new byte[16384];
                for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
            }
            if (!part.renameTo(target)) {
                part.delete();
                throw new IOException("Could not write " + target);
            }
        }
        return true;
    }

    /** Takes the addon out of addonsDir (only its own folder), when it is there. */
    public static void remove(File record, File addonsDir) throws IOException {
        if (!new File(addonsDir, FOLDER).isDirectory()) return;
        AddonInstaller.uninstall(record, addonsDir, Collections.singletonList(FOLDER));
    }
}
