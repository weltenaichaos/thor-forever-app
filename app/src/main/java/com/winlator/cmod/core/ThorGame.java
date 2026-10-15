package com.winlator.cmod.core;

import android.content.Context;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.container.Shortcut;

import java.io.File;
import java.io.IOException;
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
    /** The player's own Battle.net shortcut, with its settings, or null. */
    public final Shortcut battleNetShortcut;

    private ThorGame(Shortcut shortcut, File flavorDir, Shortcut battleNetShortcut) {
        this.shortcut = shortcut;
        this.flavorDir = flavorDir;
        this.battleNetShortcut = battleNetShortcut;
    }

    /** The WoW shortcut started last (or the first one), or null when there is none yet. */
    public static ThorGame find(ContainerManager manager) {
        Shortcut best = null;
        long bestRun = -1;
        List<Shortcut> shortcuts = manager.loadShortcuts();
        for (Shortcut shortcut : shortcuts) {
            if (!isWowExe(shortcut.path)) continue;
            long lastRun;
            try { lastRun = Long.parseLong(shortcut.getExtra("lastRunAt", "0")); } catch (NumberFormatException e) { lastRun = 0; }
            if (lastRun > bestRun) {
                best = shortcut;
                bestRun = lastRun;
            }
        }
        if (best == null) return null;
        // A Battle.net shortcut, the one in the game's container first.
        Shortcut battleNet = null;
        for (Shortcut shortcut : shortcuts) {
            if (!isBattleNetExe(shortcut.path) || isBattleNetSetupExe(shortcut.path)) continue;
            // Shortcuts made from Battle.net's .lnk can lose the spaces in their path ("Program
            // Files (x86)"), and Wine then says "File not found": only take one that points to a file.
            File target = toAndroidPath(shortcut.container, shortcut.path);
            if (target == null || !target.isFile()) continue;
            if (battleNet == null || shortcut.container == best.container) battleNet = shortcut;
        }
        File exe = toAndroidPath(best.container, best.path);
        return new ThorGame(best, exe != null ? exe.getParentFile() : null, battleNet);
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

    /** True for Battle.net's own launcher or app, and its installer (which goes on to start the app). */
    public static boolean isBattleNetExe(String dosPath) {
        if (dosPath == null) return false;
        String path = dosPath.toLowerCase(Locale.ROOT);
        return path.contains("battle.net launcher.exe") || path.contains("battle.net.exe") || isBattleNetSetupExe(dosPath);
    }

    /** True for Battle.net's installer, Battle.net-Setup.exe. */
    static boolean isBattleNetSetupExe(String dosPath) {
        return dosPath != null && dosPath.toLowerCase(Locale.ROOT).contains("battle.net-setup");
    }

    /**
     * Gives the WoW shortcut the Thor Forever pictures: the tall one in the library, unless the
     * player already picked their own (Winlator/icons/<shortcut>.user.png), and the wide one
     * on the loading screen (Winlator/banners/<shortcut>.png, where Winlator otherwise keeps a
     * picture it downloaded by the shortcut's name).
     */
    public void applyCoverArt(Context context) {
        File storage = android.os.Environment.getExternalStorageDirectory();
        String name = FileUtils.getBasename(shortcut.file.getPath());
        File cover = new File(storage, "Winlator/icons/" + name + ".user.png");
        if (!cover.exists()) copyResource(context, com.winlator.cmod.R.drawable.thor_cover, cover);
        copyResource(context, com.winlator.cmod.R.drawable.thor_banner, new File(storage, "Winlator/banners/" + name + ".png"));
    }

    /** Writes the picture unless the file already holds exactly it. */
    private static void copyResource(Context context, int id, File target) {
        try (java.io.InputStream in = context.getResources().openRawResource(id)) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[65536];
            for (int n; (n = in.read(buffer)) > 0; ) bytes.write(buffer, 0, n);
            byte[] data = bytes.toByteArray();
            if (target.isFile() && java.util.Arrays.equals(Files.readAllBytes(target.toPath()), data)) return;
            target.getParentFile().mkdirs();
            Files.write(target.toPath(), data);
        } catch (Exception e) {
            android.util.Log.w("ThorGame", "Could not write " + target, e);
        }
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
        // The game's own drive first: Battle.net is often installed next to it.
        String drive = shortcut.path != null && shortcut.path.length() > 1 && shortcut.path.charAt(1) == ':'
                ? String.valueOf(shortcut.path.charAt(0)) : null;
        return ThorSetup.findBattleNet(shortcut.container, drive);
    }

    /**
     * Writes a shortcut that starts Battle.net in the game's container, into the app's cache,
     * and returns it. It runs with the container's own settings, like starting Battle.net from
     * the container's desktop.
     */
    public File writeBattleNetShortcut(Context context, String battleNetPath) throws IOException {
        return ThorSetup.writeRunShortcut(context, shortcut.container, "Battle.net", battleNetPath);
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
