package com.winlator.cmod.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Installs World of Warcraft add-ons from a .zip, the way add-on sites pack them: one or more
 * folders, each with a .toc file named like the folder. They go into the game's
 * Interface\AddOns folder. Only adds folders: it never replaces or deletes one.
 */
public final class AddonInstaller {
    private AddonInstaller() {}

    /** An installed add-on: its folder and the title its .toc file gives. */
    public static final class Addon {
        public final String folder;
        public final String title;

        Addon(String folder, String title) {
            this.folder = folder;
            this.title = title;
        }
    }

    /** The add-ons are already there; nothing was installed. */
    public static final class AlreadyInstalledException extends IOException {
        public final List<String> folders;

        AlreadyInstalledException(List<String> folders) {
            super("Already installed: " + String.join(", ", folders));
            this.folders = folders;
        }
    }

    /** The game's add-on folder, like ..._classic_beta_/Interface/AddOns, or null without the game. */
    public static File addonsDir(ThorGame game) {
        return game == null || game.flavorDir == null ? null : new File(game.flavorDir, "Interface/AddOns");
    }

    /** The add-ons in the folder, by title. */
    public static List<Addon> installed(File addonsDir) {
        return installed(addonsDir, 0);
    }

    /**
     * The add-ons in the folder, by title, as the game with interface number gameInterface
     * (like 16001) sees them: from the .toc made for it.
     */
    public static List<Addon> installed(File addonsDir, int gameInterface) {
        List<Addon> addons = new ArrayList<>();
        File[] folders = addonsDir != null ? addonsDir.listFiles(File::isDirectory) : null;
        if (folders == null) return addons;
        for (File folder : folders) {
            File toc = tocFile(folder, gameInterface);
            if (toc == null) continue;
            String title = "";
            try {
                title = titleFromToc(readHead(toc));
            } catch (IOException ignored) {}
            addons.add(new Addon(folder.getName(), title.isEmpty() ? folder.getName() : title));
        }
        Collections.sort(addons, (a, b) -> a.title.compareToIgnoreCase(b.title));
        return addons;
    }

    /**
     * The .toc the game reads: the one listing its interface number, else one for the same
     * major version, else the plain Name.toc. The suffix doesn't tell: Questie's file for
     * WoW Forever is Questie_Camelot.toc.
     */
    private static File tocFile(File folder, int gameInterface) {
        String[] names = folder.list();
        if (names == null) return null;
        File exact = null, close = null, plain = null, any = null;
        for (String name : names) {
            if (!isTocOf(folder.getName(), name)) continue;
            File toc = new File(folder, name);
            if (any == null) any = toc;
            if (name.equalsIgnoreCase(folder.getName() + ".toc")) plain = toc;
            if (gameInterface <= 0) continue;
            String text;
            try {
                text = readHead(toc);
            } catch (IOException e) {
                continue;
            }
            for (int i : interfacesFromToc(text)) {
                if (i == gameInterface && exact == null) exact = toc;
                if (i / 100 == gameInterface / 100 && close == null) close = toc;
            }
        }
        return exact != null ? exact : close != null ? close : plain != null ? plain : any;
    }

    /**
     * The start of a .toc file, where its ## lines are. Some are huge: QuestieDB keeps its
     * whole database in them (up to 57 MB).
     */
    private static String readHead(File toc) throws IOException {
        try (InputStream in = new java.io.FileInputStream(toc)) {
            byte[] buffer = new byte[16384];
            int n = 0;
            for (int r; n < buffer.length && (r = in.read(buffer, n, buffer.length - n)) > 0; ) n += r;
            return new String(buffer, 0, n, StandardCharsets.UTF_8);
        }
    }

    /** The numbers of a .toc's "## Interface: 11508, 11509" line. */
    static List<Integer> interfacesFromToc(String toc) {
        List<Integer> result = new ArrayList<>();
        for (String line : toc.split("\r?\n")) {
            String l = line.trim();
            if (l.startsWith("\uFEFF")) l = l.substring(1);
            if (!l.regionMatches(true, 0, "## Interface:", 0, 13)) continue;
            for (String part : l.substring(13).split(",")) {
                try {
                    result.add(Integer.parseInt(part.trim()));
                } catch (NumberFormatException ignored) {}
            }
            break;
        }
        return result;
    }

    /** Whether file is the .toc of an add-on folder: Name.toc, or a flavor one like Name_Vanilla.toc or Name-Classic.toc. */
    static boolean isTocOf(String folder, String file) {
        String f = file.toLowerCase(Locale.ROOT);
        String d = folder.toLowerCase(Locale.ROOT);
        if (!f.endsWith(".toc")) return false;
        String base = f.substring(0, f.length() - 4);
        return base.equals(d) || base.startsWith(d + "_") || base.startsWith(d + "-");
    }

    /** The "## Title:" of a .toc file, without the game's colour and texture codes. */
    static String titleFromToc(String toc) {
        for (String line : toc.split("\r?\n")) {
            String l = line.trim();
            if (l.startsWith("﻿")) l = l.substring(1);
            if (!l.regionMatches(true, 0, "## Title:", 0, 9)) continue;
            return l.substring(9)
                    .replaceAll("\\|c[0-9a-fA-F]{8}", "")
                    .replaceAll("\\|r", "")
                    .replaceAll("\\|T[^|]*\\|t", "")
                    .trim();
        }
        return "";
    }

    /**
     * The add-on folders in a .zip's entries, mapped to their path in the .zip (like
     * "DBM-Core/" or "Wrapper/DBM-Core/"). Folders inside an add-on (its libraries) are part of
     * it, not add-ons of their own.
     */
    static Map<String, String> addonRoots(List<String> entryNames) {
        List<String> roots = new ArrayList<>();
        for (String raw : entryNames) {
            String name = raw.replace('\\', '/');
            int slash = name.lastIndexOf('/');
            if (slash <= 0) continue;
            String dir = name.substring(0, slash);
            String folder = dir.substring(dir.lastIndexOf('/') + 1);
            if (isTocOf(folder, name.substring(slash + 1))) roots.add(dir + "/");
        }
        // The shortest paths first, so an add-on comes before the libraries inside it.
        Collections.sort(roots, (a, b) -> a.length() - b.length());
        Map<String, String> result = new LinkedHashMap<>();
        for (String root : roots) {
            boolean inside = false;
            for (String outer : result.values()) if (root.startsWith(outer)) inside = true;
            if (inside) continue;
            String folder = root.substring(0, root.length() - 1);
            folder = folder.substring(folder.lastIndexOf('/') + 1);
            if (!result.containsKey(folder)) result.put(folder, root);
        }
        return result;
    }

    /**
     * Unpacks the add-ons in zip into addonsDir. Returns the installed folders. Throws, and
     * changes nothing, when the .zip has no add-on or one of them is already installed.
     */
    public static List<String> install(File zip, File addonsDir) throws IOException {
        try (ZipFile zipFile = new ZipFile(zip)) {
            List<String> names = new ArrayList<>();
            for (Enumeration<? extends ZipEntry> e = zipFile.entries(); e.hasMoreElements(); ) names.add(e.nextElement().getName());
            Map<String, String> roots = addonRoots(names);
            if (roots.isEmpty()) throw new IOException("No add-on in that file. Pick the add-on's own .zip (not the source code .zip)");
            List<String> existing = new ArrayList<>();
            for (String folder : roots.keySet()) if (new File(addonsDir, folder).exists()) existing.add(folder);
            if (!existing.isEmpty()) throw new AlreadyInstalledException(existing);
            // Where each file goes, all checked before anything is written.
            String base = addonsDir.getCanonicalPath() + File.separator;
            Map<ZipEntry, File> targets = new LinkedHashMap<>();
            for (Enumeration<? extends ZipEntry> e = zipFile.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                String name = entry.getName().replace('\\', '/');
                for (Map.Entry<String, String> root : roots.entrySet()) {
                    if (!name.startsWith(root.getValue())) continue;
                    File target = new File(addonsDir, root.getKey() + "/" + name.substring(root.getValue().length()));
                    // Never outside the add-on folder, whatever the .zip says ("../").
                    if (!target.getCanonicalPath().startsWith(base)) throw new IOException("Unsafe path in the file: " + name);
                    targets.put(entry, target);
                    break;
                }
            }
            if (!addonsDir.isDirectory() && !addonsDir.mkdirs()) throw new IOException("Could not create " + addonsDir);
            for (Map.Entry<ZipEntry, File> item : targets.entrySet()) {
                ZipEntry entry = item.getKey();
                File target = item.getValue();
                String name = entry.getName().replace('\\', '/');
                if (entry.isDirectory() || name.endsWith("/")) {
                    if (!target.isDirectory() && !target.mkdirs()) throw new IOException("Could not create " + target);
                } else {
                    File parent = target.getParentFile();
                    if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Could not create " + parent);
                    try (InputStream in = zipFile.getInputStream(entry); OutputStream out = new FileOutputStream(target)) {
                        byte[] buffer = new byte[65536];
                        for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
                    }
                }
            }
            return new ArrayList<>(roots.keySet());
        }
    }
}
