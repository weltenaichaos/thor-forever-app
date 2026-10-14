package com.winlator.cmod.core;

import android.content.Context;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.xenvironment.ImageFs;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;

/**
 * What the Thor Forever start screen needs to know about the game: the WoW shortcut, the
 * installed version (from the .build.info file Battle.net writes) and where Battle.net is.
 * Only reads the game's files; the one file it writes is a shortcut in the app's own cache.
 */
public final class ThorGame {
    /** Intent extra: the session runs Battle.net for an update and closes when its window does. */
    public static final String EXTRA_UPDATE_SESSION = "thor_update_session";

    public final Shortcut shortcut;
    /** The game's folder on Android, like .../World of Warcraft/_classic_beta_, or null. */
    public final File flavorDir;

    private ThorGame(Shortcut shortcut, File flavorDir) {
        this.shortcut = shortcut;
        this.flavorDir = flavorDir;
    }

    /** The first shortcut that starts WoW, or null when there is none yet. */
    public static ThorGame find(ContainerManager manager) {
        List<Shortcut> shortcuts = manager.loadShortcuts();
        for (Shortcut shortcut : shortcuts) {
            if (!isWowExe(shortcut.path)) continue;
            File exe = toAndroidPath(shortcut.container, shortcut.path);
            return new ThorGame(shortcut, exe != null ? exe.getParentFile() : null);
        }
        return null;
    }

    /** True for WoW's own .exe (Wow.exe, WowB-ARM64.exe, ...), not its voice chat or other tools. */
    static boolean isWowExe(String dosPath) {
        if (dosPath == null) return false;
        String path = dosPath.trim();
        int quote = path.indexOf(".exe");
        if (quote < 0) return false;
        String exe = path.substring(path.lastIndexOf('\\', quote) + 1, quote + 4).toLowerCase(Locale.ROOT);
        return exe.startsWith("wow") && !exe.startsWith("wowvoice");
    }

    /** The installed game version, like 5.0.1.70009, or "" when it can't be read. */
    public String installedVersion() {
        if (flavorDir == null) return "";
        File wowDir = flavorDir.getParentFile();
        String buildInfo = readText(new File(wowDir, ".build.info"));
        String product = flavorProduct(readText(new File(flavorDir, ".flavor.info")));
        return versionFromBuildInfo(buildInfo, product);
    }

    /** The product name (like wow_classic_beta) from a .flavor.info file. */
    static String flavorProduct(String flavorInfo) {
        String[] lines = flavorInfo.split("\r?\n");
        return lines.length > 1 ? field(lines[1], 0) : "";
    }

    /** The Version column of the row for product (any row when product is empty). */
    static String versionFromBuildInfo(String buildInfo, String product) {
        String[] lines = buildInfo.split("\r?\n");
        if (lines.length < 2) return "";
        int productCol = column(lines[0], "Product");
        int versionCol = column(lines[0], "Version");
        if (versionCol < 0) return "";
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].trim().isEmpty()) continue;
            if (!product.isEmpty() && productCol >= 0 && !product.equals(field(lines[i], productCol))) continue;
            String version = field(lines[i], versionCol);
            if (!version.isEmpty()) return version;
        }
        return "";
    }

    private static int column(String header, String name) {
        String[] fields = header.split("\\|", -1);
        for (int i = 0; i < fields.length; i++) {
            String f = fields[i];
            int bang = f.indexOf('!');
            if ((bang >= 0 ? f.substring(0, bang) : f).equals(name)) return i;
        }
        return -1;
    }

    private static String field(String line, int index) {
        String[] fields = line.split("\\|", -1);
        return index < fields.length ? fields[index].trim() : "";
    }

    /** Battle.net's launcher in this container as a Windows path, or null when it isn't installed. */
    public String battleNetPath() {
        Container container = shortcut.container;
        String[] candidates = {
                "C:\\Program Files (x86)\\Battle.net\\Battle.net Launcher.exe",
                "C:\\Program Files (x86)\\Battle.net\\Battle.net.exe",
                "C:\\Program Files\\Battle.net\\Battle.net Launcher.exe",
                "C:\\Program Files\\Battle.net\\Battle.net.exe"
        };
        for (String candidate : candidates) {
            File file = toAndroidPath(container, candidate);
            if (file != null && file.isFile()) return candidate;
        }
        return null;
    }

    /**
     * Writes a shortcut that starts Battle.net in the game's container, into the app's cache,
     * and returns it. It runs with the container's own settings, like starting Battle.net from
     * the container's desktop.
     */
    public File writeBattleNetShortcut(Context context, String battleNetPath) throws IOException {
        Container container = shortcut.container;
        File file = new File(context.getCacheDir(), "thor_battle_net.desktop");
        String winePrefix = wineHome(context, container) + "/.wine";
        try (PrintWriter writer = new PrintWriter(new FileWriter(file))) {
            writer.println("[Desktop Entry]");
            writer.println("Name=Battle.net");
            writer.println("Exec=env WINEPREFIX=\"" + winePrefix + "\" wine " + StringUtils.escapeFileDOSPath(battleNetPath));
            writer.println("Type=Application");
            writer.println("container_id:" + container.id);
        }
        return file;
    }

    private static String wineHome(Context context, Container container) {
        String imagefs = ImageFs.find(context).getRootDir().getAbsolutePath();
        String root = container.getRootDir().getAbsolutePath();
        return root.startsWith(imagefs) ? root.substring(imagefs.length()) : "/home/" + ImageFs.USER;
    }

    /** Turns a Windows path like E:\Games\Wow.exe into the file on Android, or null. */
    static File toAndroidPath(Container container, String dosPath) {
        String path = dosPath.trim();
        if (path.startsWith("\"")) path = path.substring(1);
        int exe = path.toLowerCase(Locale.ROOT).indexOf(".exe");
        if (exe >= 0) path = path.substring(0, exe + 4);
        if (path.length() < 3 || path.charAt(1) != ':') return null;
        char letter = Character.toUpperCase(path.charAt(0));
        String rest = path.substring(3).replace('\\', '/');
        File drive = null;
        if (letter == 'C') {
            drive = new File(container.getRootDir(), ".wine/drive_c");
        } else {
            for (String[] entry : container.drivesIterator()) {
                if (entry[0].equalsIgnoreCase(String.valueOf(letter))) {
                    drive = new File(entry[1]);
                    break;
                }
            }
            if (drive == null) drive = new File(container.getRootDir(), ".wine/dosdevices/" + Character.toLowerCase(letter) + ":");
        }
        return new File(drive, rest);
    }

    private static String readText(File file) {
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
