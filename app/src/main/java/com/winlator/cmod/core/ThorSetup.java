package com.winlator.cmod.core;

import android.content.Context;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.fexcore.FEXCorePreset;
import com.winlator.cmod.fexcore.FEXCorePresetManager;
import com.winlator.cmod.xenvironment.ImageFs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The first setup from the Thor Forever start screen: download Blizzard's Battle.net installer
 * into the container and start it, and once World of Warcraft is installed, add its shortcut
 * with the Thor settings. Only writes inside the app's own containers and cache.
 */
public final class ThorSetup {
    /** Blizzard's own download link for the Windows Battle.net installer. */
    public static final String INSTALLER_URL = "https://downloader.battle.net/download/getInstaller?os=win&installer=Battle.net-Setup.exe";
    /** Where the installer is kept in the container, as Windows sees it. */
    public static final String INSTALLER_DOS_PATH = "C:\\ProgramData\\ThorForever\\Battle.net-Setup.exe";
    /** The DXVK settings that tested best on the Thor (see the graphics tuning guide). */
    static final String DXVK_CONFIG = "dxvk.enableGraphicsPipelineLibrary=false;dxvk.tilerMode=False;d3d11.relaxedBarriers=True";
    /** What an earlier version of the first setup wrote, to bring those shortcuts up to date. */
    private static final String OLD_DXVK_CONFIG = "dxvk.enableGraphicsPipelineLibrary=false;dxvk.tilerMode=False";
    /** The file of the WoW shortcut the first setup adds. */
    static final String WOW_SHORTCUT_FILE = "World of Warcraft Forever.desktop";
    /** The FEXCore preset of Jesse's WoW shortcut that runs well ("GameHub Extreme" there). */
    static final String FEX_PRESET_NAME = "Thor Extreme";
    /** The game settings the Thor Forever launcher ships, in WTF next to WoW's exe. */
    static final String WOW_CONFIG = "Config-Thor-Forever.wtf";
    static final String WOW_CONFIG_ASSET = "thor/" + WOW_CONFIG;
    /**
     * WoW's start arguments, as the Thor Forever launcher starts it: Direct3D 11, and the
     * launcher's game settings in place of Config.wtf.
     */
    static final String WOW_EXEC_ARGS = "-d3d11 -config " + WOW_CONFIG;
    /** Smaller than this is an error page, not the installer (it is about 5 MB). */
    private static final long MIN_INSTALLER_BYTES = 1024 * 1024;

    private ThorSetup() {}

    public interface Progress {
        /** percent is 0 to 100, or -1 when the size is unknown. */
        void onProgress(int percent);
    }

    /** The container for the game: the one Battle.net is installed in, else the first one. */
    public static Container pickContainer(ContainerManager manager) {
        List<Container> containers = manager.getContainers();
        for (Container container : containers)
            if (findBattleNet(container, null) != null) return container;
        return containers.isEmpty() ? null : containers.get(0);
    }

    /** Battle.net's launcher in the container as a Windows path, or null when it isn't installed. */
    public static String findBattleNet(Container container, String preferDrive) {
        String[] folders = { "Program Files (x86)\\Battle.net", "Program Files\\Battle.net", "Battle.net" };
        String[] names = { "Battle.net Launcher.exe", "Battle.net.exe" };
        for (String drive : drives(container, preferDrive))
            for (String folder : folders)
                for (String name : names) {
                    String candidate = drive + ":\\" + folder + "\\" + name;
                    File file = ThorGame.toAndroidPath(container, candidate);
                    if (file != null && file.isFile()) return candidate;
                }
        return null;
    }

    /** C, the container's other drives, and preferDrive (when given) first. */
    private static List<String> drives(Container container, String preferDrive) {
        List<String> drives = new ArrayList<>();
        drives.add("C");
        for (String[] drive : container.drivesIterator()) drives.add(drive[0].toUpperCase(Locale.ROOT));
        if (preferDrive != null) drives.add(0, preferDrive.toUpperCase(Locale.ROOT));
        return drives;
    }

    /**
     * WoW's .exe as Battle.net installed it (like C:\Program Files (x86)\World of Warcraft\
     * _classic_beta_\WowB-ARM64.exe), or null. With several game versions, the one changed last.
     */
    public static String findInstalledWow(Container container) {
        String best = null;
        long bestTime = -1;
        for (String wow : findInstalledWows(container)) {
            File exe = ThorGame.toAndroidPath(container, wow);
            long time = exe != null ? exe.lastModified() : 0;
            if (time > bestTime) {
                best = wow;
                bestTime = time;
            }
        }
        return best;
    }

    /**
     * The .exe of every game version Battle.net installed in the container (one per folder like
     * _classic_beta_ or _retail_), as Windows paths.
     */
    public static List<String> findInstalledWows(Container container) {
        String[] folders = { "Program Files (x86)\\World of Warcraft", "Program Files\\World of Warcraft", "World of Warcraft" };
        List<String> found = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String drive : drives(container, null))
            for (String folder : folders) {
                String wowDos = drive + ":\\" + folder;
                File wowDir = ThorGame.toAndroidPath(container, wowDos);
                if (wowDir == null) continue;
                File[] flavors = wowDir.listFiles(File::isDirectory);
                if (flavors == null) continue;
                java.util.Arrays.sort(flavors);
                for (File flavor : flavors) {
                    if (!flavor.getName().startsWith("_")) continue;
                    String exe = pickWowExe(flavor.list());
                    if (exe == null) continue;
                    // The same folder seen through two drive letters is one game version.
                    String key;
                    try { key = flavor.getCanonicalPath(); } catch (IOException e) { key = flavor.getAbsolutePath(); }
                    if (seen.add(key)) found.add(wowDos + "\\" + flavor.getName() + "\\" + exe);
                }
            }
        return found;
    }

    /**
     * Gives every game version installed next to the game a WoW shortcut of its own, so the start
     * screen can switch between them: a version Battle.net added (like the release next to the
     * beta) gets the settings of the game's shortcut, and one of the app's shortcuts whose .exe
     * was renamed in its folder points to the new one. A version installed after the game's own becomes the one Play
     * starts. Returns the shortcut of the last version added, or null when none was added.
     */
    public static Shortcut addNewWowVersions(Context context, ContainerManager manager, ThorGame game) throws IOException {
        Container container = game.shortcut.container;
        List<Shortcut> known = new ArrayList<>();
        for (Shortcut shortcut : manager.loadShortcuts())
            if (shortcut.container == container && ThorGame.isWowExe(shortcut.path)) known.add(shortcut);
        Shortcut added = null;
        for (String wow : findInstalledWows(container)) {
            File exe = ThorGame.toAndroidPath(container, wow);
            boolean has = false;
            Shortcut moved = null;
            for (Shortcut shortcut : known) {
                File its = ThorGame.toAndroidPath(container, shortcut.path);
                // The same .exe, also when the shortcut reaches it through another drive letter.
                if (shortcut.path.trim().equalsIgnoreCase(wow) || sameFile(its, exe)) { has = true; break; }
                boolean gone = its == null || !its.isFile();
                // Only the app's own shortcuts are changed; a player's own one stays as it is.
                if (moved == null && gone && its != null && exe != null && isThorWowShortcutFile(shortcut.file.getName())
                        && sameFile(its.getParentFile(), exe.getParentFile())) moved = shortcut;
            }
            if (has) continue;
            if (moved != null) {
                repointShortcut(context, moved, wow);
                continue;
            }
            String kind = ThorGame.kindOf(productIn(exe), flavorName(wow));
            // Battle.net installed it after the game's version (like the release after the beta): Play starts it.
            File gameExe = ThorGame.toAndroidPath(container, game.shortcut.path);
            boolean newer = exe != null && (gameExe == null || !gameExe.isFile() || exe.lastModified() > gameExe.lastModified());
            added = addWowShortcut(context, container, wow, kind, game.shortcut, newer);
            known.add(added);
        }
        return added;
    }

    private static boolean sameFile(File a, File b) {
        if (a == null || b == null) return false;
        try {
            return a.getCanonicalPath().equals(b.getCanonicalPath());
        } catch (IOException e) {
            return a.getAbsolutePath().equals(b.getAbsolutePath());
        }
    }

    /** The folder part of a Windows path. */
    static String folderOf(String dosPath) {
        int slash = dosPath.lastIndexOf('\\');
        return slash < 0 ? "" : dosPath.substring(0, slash);
    }

    /** The game folder's name in a path like ...\_classic_beta_\WowB-ARM64.exe. */
    static String flavorName(String dosPath) {
        String folder = folderOf(dosPath);
        return folder.substring(folder.lastIndexOf('\\') + 1);
    }

    private static String productIn(File exe) {
        if (exe == null) return "";
        try {
            return ThorGame.flavorProduct(new String(java.nio.file.Files.readAllBytes(new File(exe.getParentFile(), ".flavor.info").toPath()), java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * Adds a WoW shortcut for another game version with the settings of like (its own extras:
     * Thor variables, FPS cap, start arguments, ...), and the launcher's game settings in that
     * version's WTF folder. With choose, it is marked as picked, so Play starts it.
     */
    static Shortcut addWowShortcut(Context context, Container container, String wowDosPath, String kind, Shortcut like, boolean choose) throws IOException {
        File desktop = container.getDesktopDir();
        if (!desktop.isDirectory() && !desktop.mkdirs()) throw new IOException("Could not create " + desktop);
        File file = new File(desktop, wowShortcutFileName(kind, desktop.list()));
        writeDesktopEntry(context, file, container, "World of Warcraft " + kind, wowDosPath);
        Shortcut shortcut = new Shortcut(container, file);
        for (java.util.Map.Entry<String, String> extra : copiedExtras(like.file).entrySet())
            shortcut.putExtra(extra.getKey(), extra.getValue());
        if (shortcut.getExtra("envVars").isEmpty()) shortcut.putExtra("envVars", thorEnvVars(container.getEnvVars()));
        if (shortcut.getExtra("execArgs").isEmpty()) shortcut.putExtra("execArgs", WOW_EXEC_ARGS);
        if (choose) shortcut.putExtra(ThorGame.EXTRA_CHOSEN_AT, String.valueOf(System.currentTimeMillis()));
        seedWowConfig(context, ThorGame.toAndroidPath(container, wowDosPath).getParentFile());
        shortcut.saveData();
        return shortcut;
    }

    /** A free file name like "World of Warcraft Forever Live.desktop" among the desktop's files. */
    static String wowShortcutFileName(String kind, String[] taken) {
        java.util.Set<String> names = new java.util.HashSet<>();
        if (taken != null) for (String name : taken) names.add(name.toLowerCase(Locale.ROOT));
        String base = "World of Warcraft Forever " + kind;
        String name = base + ".desktop";
        for (int i = 2; names.contains(name.toLowerCase(Locale.ROOT)); i++) name = base + " " + i + ".desktop";
        return name;
    }

    /** True for the shortcut files this app adds for WoW (one per game version). */
    public static boolean isThorWowShortcutFile(String name) {
        return name.equals(WOW_SHORTCUT_FILE) || (name.startsWith("World of Warcraft Forever ") && name.endsWith(".desktop"));
    }

    /** Extras that belong to one shortcut and are not copied: when it ran, its id, its pictures. */
    private static final String[] OWN_EXTRAS = { "lastRunAt", ThorGame.EXTRA_CHOSEN_AT, "uuid", "customCoverArtPath" };

    /** The [Extra Data] of a shortcut file, without the ones that belong to that shortcut alone. */
    static java.util.Map<String, String> copiedExtras(File desktopFile) {
        java.util.Map<String, String> extras = new java.util.LinkedHashMap<>();
        String section = "";
        for (String line : FileUtils.readLines(desktopFile)) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("[") && line.contains("]")) { section = line.substring(1, line.indexOf(']')); continue; }
            int eq = line.indexOf('=');
            if (!section.equals("Extra Data") || eq <= 0) continue;
            String key = line.substring(0, eq);
            if (!java.util.Arrays.asList(OWN_EXTRAS).contains(key)) extras.put(key, line.substring(eq + 1));
        }
        return extras;
    }

    /** Points a shortcut to another .exe, keeping its name and settings. */
    static void repointShortcut(Context context, Shortcut shortcut, String dosPath) throws IOException {
        java.util.Map<String, String> extras = new java.util.LinkedHashMap<>(copiedExtras(shortcut.file));
        for (String key : OWN_EXTRAS) if (!shortcut.getExtra(key).isEmpty()) extras.put(key, shortcut.getExtra(key));
        String name = "World of Warcraft";
        for (String line : FileUtils.readLines(shortcut.file))
            if (line.startsWith("Name=")) { name = line.substring(5); break; }
        writeDesktopEntry(context, shortcut.file, shortcut.container, name, dosPath);
        Shortcut moved = new Shortcut(shortcut.container, shortcut.file);
        for (java.util.Map.Entry<String, String> extra : extras.entrySet()) moved.putExtra(extra.getKey(), extra.getValue());
        seedWowConfig(context, ThorGame.toAndroidPath(shortcut.container, dosPath).getParentFile());
        moved.saveData();
    }

    /** The game's .exe among a game folder's files: the ARM64 one when there is one. */
    static String pickWowExe(String[] names) {
        if (names == null) return null;
        String found = null;
        for (String name : names) {
            if (!name.toLowerCase(Locale.ROOT).endsWith(".exe") || !ThorGame.isWowExe("C:\\" + name)) continue;
            if (name.toLowerCase(Locale.ROOT).contains("arm64")) return name;
            if (found == null) found = name;
        }
        return found;
    }

    /**
     * The container's environment with the settings of Jesse's WoW shortcut that runs well on the
     * Thor: Turnip's autotuner for the rendering mode (in place of TU_DEBUG's sysmem/gmem), DXVK's
     * HUD off, DXVK_CONFIG, DXVK's state cache off and the wrapper's OpConstantComposite patch off.
     * TU_DEBUG and DXVK_HUD are set empty, not left out: a game starts with the container's
     * variables and then the shortcut's, so a variable the shortcut lacks keeps the container's value.
     */
    static String thorEnvVars(String containerEnvVars) {
        EnvVars envVars = new EnvVars(containerEnvVars != null ? containerEnvVars : "");
        envVars.put("TU_DEBUG", "");
        envVars.put("DXVK_HUD", "");
        envVars.put("TU_AUTOTUNE_ALGO", "profiled");
        envVars.put("DXVK_CONFIG", DXVK_CONFIG);
        envVars.put("DXVK_STATE_CACHE", "0");
        envVars.put("WRAPPER_NO_PATCH_OPCONSTCOMP", "1");
        return envVars.toString();
    }

    /** The values of Jesse's "GameHub Extreme" FEXCore preset, as in his screenshots. */
    static String thorFexEnvVars() {
        EnvVars envVars = new EnvVars();
        envVars.put("FEX_TSOENABLED", "0");
        envVars.put("FEX_VECTORTSOENABLED", "0");
        envVars.put("FEX_HALFBARRIERTSOENABLED", "0");
        envVars.put("FEX_MEMCPYSETTSOENABLED", "0");
        envVars.put("FEX_X87REDUCEDPRECISION", "1");
        envVars.put("FEX_MULTIBLOCK", "1");
        envVars.put("FEX_MAXINST", "5000");
        envVars.put("FEX_HOSTFEATURES", "off");
        envVars.put("FEX_SMALLTSCSCALE", "1");
        envVars.put("FEX_SMCCHECKS", "mtrack");
        envVars.put("FEX_VOLATILEMETADATA", "1");
        envVars.put("FEX_MONOHACKS", "1");
        envVars.put("FEX_HIDEHYPERVISORBIT", "0");
        envVars.put("FEX_DISABLEL2CACHE", "0");
        envVars.put("FEX_DYNAMICL1CACHE", "0");
        return envVars.toString();
    }

    /** The id of the "Thor Extreme" FEXCore preset, added to the app's presets when missing. */
    static String thorFexPreset(Context context) {
        for (FEXCorePreset preset : FEXCorePresetManager.getPresets(context)) {
            if (preset.isCustom() && FEX_PRESET_NAME.equals(preset.name)) return preset.id;
        }
        FEXCorePresetManager.editPreset(context, null, FEX_PRESET_NAME, new EnvVars(thorFexEnvVars()));
        for (FEXCorePreset preset : FEXCorePresetManager.getPresets(context)) {
            if (preset.isCustom() && FEX_PRESET_NAME.equals(preset.name)) return preset.id;
        }
        return null;
    }

    /**
     * Adds a World of Warcraft shortcut to the container's desktop, as the library shows it. Its
     * own file name, so it never replaces the shortcut Battle.net may put there for its launcher.
     */
    public static Shortcut createWowShortcut(Context context, Container container, String wowDosPath) throws IOException {
        File desktop = container.getDesktopDir();
        if (!desktop.isDirectory() && !desktop.mkdirs()) throw new IOException("Could not create " + desktop);
        File file = new File(desktop, WOW_SHORTCUT_FILE);
        writeDesktopEntry(context, file, container, "World of Warcraft", wowDosPath);
        Shortcut shortcut = new Shortcut(container, file);
        shortcut.putExtra("envVars", thorEnvVars(container.getEnvVars()));
        // As in the WoW shortcut that runs well on the Thor; the container's default is fifo.
        shortcut.putExtra("rendererPresentMode", "mailbox");
        shortcut.putExtra("execArgs", WOW_EXEC_ARGS);
        seedWowConfig(context, ThorGame.toAndroidPath(container, wowDosPath).getParentFile());
        String fexPreset = thorFexPreset(context);
        if (fexPreset != null) shortcut.putExtra("fexcorePreset", fexPreset);
        shortcut.saveData();
        return shortcut;
    }

    /**
     * Puts the launcher's game settings into WoW's WTF folder when they aren't there yet. An
     * existing file is never changed: WoW saves the player's own settings into it.
     */
    static void seedWowConfig(Context context, File wowDir) {
        if (wowDir == null || !wowDir.isDirectory()) return;
        try (InputStream in = context.getAssets().open(WOW_CONFIG_ASSET)) {
            seedWowConfig(wowDir, in);
        } catch (IOException ignored) {
        }
    }

    /**
     * Returns true when the file was written. Its login portal fits the game version: "test"
     * (the beta's login servers, as in the template) for a beta or test version, the region
     * Battle.net installed the game from (like EU) for the game itself.
     */
    static boolean seedWowConfig(File wowDir, InputStream template) throws IOException {
        File config = new File(wowDir, "WTF/" + WOW_CONFIG);
        if (config.exists()) return false;
        File wtf = config.getParentFile();
        if (!wtf.isDirectory() && !wtf.mkdirs()) throw new IOException("Could not create " + wtf);
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int n; (n = template.read(buffer)) > 0; ) bytes.write(buffer, 0, n);
        String text = withPortal(bytes.toString("UTF-8"), portalFor(wowDir));
        write(config, text);
        return true;
    }

    /**
     * Fixes the login portal in the game settings an earlier build seeded: it always wrote the
     * beta's "test", so the game itself (stress test, release) logged in to the beta's servers
     * and offered only the beta's account. Only that one line changes, and only from "test".
     * Returns true when the file was changed.
     */
    static boolean fixWowConfigPortal(File wowDir) throws IOException {
        if (wowDir == null) return false;
        File config = new File(wowDir, "WTF/" + WOW_CONFIG);
        if (!config.isFile()) return false;
        String portal = portalFor(wowDir);
        if (portal == null || portal.equals("test")) return false;
        String text = new String(java.nio.file.Files.readAllBytes(config.toPath()), java.nio.charset.StandardCharsets.UTF_8);
        if (!java.util.regex.Pattern.compile("(?m)^SET portal \"test\"").matcher(text).find()) return false;
        write(config, withPortal(text, portal));
        return true;
    }

    /**
     * The login portal for the game version in wowDir: "test" for a beta or test version, the
     * region from .build.info (like EU) for the game itself, "" when that region is unknown (the
     * line is left out), or null when the version is unknown (the settings stay as they are).
     */
    static String portalFor(File wowDir) {
        String product = productIn(new File(wowDir, "x.exe"));
        if (product.isEmpty()) return null;
        if (!ThorGame.kindOf(product, wowDir.getName()).equals("Live")) return "test";
        String buildInfo;
        try {
            buildInfo = new String(java.nio.file.Files.readAllBytes(new File(wowDir.getParentFile(), ".build.info").toPath()), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            buildInfo = "";
        }
        String region = ThorGame.fieldFromBuildInfo(buildInfo, product, "Branch");
        return region.matches("[A-Za-z]{2}") ? region.toUpperCase(Locale.ROOT) : "";
    }

    /** The settings with their portal line set to portal, without it for "", unchanged for null. */
    static String withPortal(String config, String portal) {
        if (portal == null) return config;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?m)^SET portal \"[^\"\r\n]*\"(\r?\n)?").matcher(config);
        if (!m.find()) return portal.isEmpty() ? config : "SET portal \"" + portal + "\"\n" + config;
        String end = m.group(1) != null ? m.group(1) : "";
        String line = portal.isEmpty() ? "" : "SET portal \"" + portal + "\"" + end;
        return config.substring(0, m.start()) + line + config.substring(m.end());
    }

    /** Writes text next to file and renames it over it. */
    private static void write(File file, String text) throws IOException {
        File part = new File(file.getParentFile(), file.getName() + ".part");
        try (OutputStream out = new FileOutputStream(part)) {
            out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        if (!part.renameTo(file)) throw new IOException("Could not write " + file);
    }

    /** Writes a shortcut that runs a Windows program in the container, into the app's cache. */
    public static File writeRunShortcut(Context context, Container container, String name, String dosPath) throws IOException {
        File file = new File(context.getCacheDir(), "thor_" + name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_") + ".desktop");
        writeDesktopEntry(context, file, container, name, dosPath);
        if (ThorGame.isBattleNetExe(dosPath)) {
            Shortcut shortcut = new Shortcut(container, file);
            shortcut.putExtra("envVars", "WINEESYNC=0");
            shortcut.saveData();
        }
        return file;
    }

    /**
     * Shows esync as off in the settings of Battle.net shortcuts, the way it runs: Battle.net
     * hangs with it. Only WINEESYNC changes; every other variable stays as it was.
     */
    public static void turnOffEsyncForBattleNet(ContainerManager manager) {
        for (Shortcut shortcut : manager.loadShortcuts()) {
            if (!ThorGame.isBattleNetExe(shortcut.path)) continue;
            // Like the shortcut editor: without its own variables it starts from the container's.
            String own = shortcut.getExtra("envVars");
            EnvVars envVars = new EnvVars(own.isEmpty() ? shortcut.container.getEnvVars() : own);
            if ("0".equals(envVars.get("WINEESYNC"))) continue;
            envVars.put("WINEESYNC", "0");
            shortcut.putExtra("envVars", envVars.toString());
            shortcut.saveData();
        }
    }

    /**
     * Fixes WoW shortcuts an earlier version of the first setup added: they left TU_DEBUG and
     * DXVK_HUD out, so the game still got the container's values, had no present mode, a shorter
     * DXVK_CONFIG, no DXVK_STATE_CACHE or WRAPPER_NO_PATCH_OPCONSTCOMP and no FEXCore preset of
     * their own and not the launcher's start arguments and game settings. Only those change, and
     * only where the shortcut still has what the setup wrote.
     */
    public static void fixAddedWowShortcuts(ContainerManager manager) {
        for (Shortcut shortcut : manager.loadShortcuts()) {
            if (!isThorWowShortcutFile(shortcut.file.getName())) continue;
            EnvVars envVars = new EnvVars(shortcut.getExtra("envVars"));
            boolean presentMode = shortcut.getExtra("rendererPresentMode").isEmpty();
            boolean fexPreset = shortcut.getExtra("fexcorePreset").isEmpty();
            String oldArgs = shortcut.getExtra("execArgs");
            // Empty, or "-d3d11" from the build before the game settings came along.
            boolean execArgs = oldArgs.isEmpty() || oldArgs.trim().equals("-d3d11");
            if (shortcut.getExtra("execArgs", WOW_EXEC_ARGS).contains(WOW_CONFIG) || execArgs) {
                File wowDir = ThorGame.toAndroidPath(shortcut.container, shortcut.path).getParentFile();
                seedWowConfig(manager.getContext(), wowDir);
                try { fixWowConfigPortal(wowDir); } catch (IOException ignored) {}
            }
            String before = envVars.toString();
            if (!envVars.has("TU_DEBUG")) envVars.put("TU_DEBUG", "");
            if (!envVars.has("DXVK_HUD")) envVars.put("DXVK_HUD", "");
            if (OLD_DXVK_CONFIG.equals(envVars.get("DXVK_CONFIG"))) envVars.put("DXVK_CONFIG", DXVK_CONFIG);
            if (!envVars.has("DXVK_STATE_CACHE")) envVars.put("DXVK_STATE_CACHE", "0");
            if (!envVars.has("WRAPPER_NO_PATCH_OPCONSTCOMP")) envVars.put("WRAPPER_NO_PATCH_OPCONSTCOMP", "1");
            if (before.equals(envVars.toString()) && !presentMode && !fexPreset && !execArgs) continue;
            shortcut.putExtra("envVars", envVars.toString());
            if (presentMode) shortcut.putExtra("rendererPresentMode", "mailbox");
            if (execArgs) shortcut.putExtra("execArgs", WOW_EXEC_ARGS);
            String thorFex = fexPreset ? thorFexPreset(manager.getContext()) : null;
            if (thorFex != null) shortcut.putExtra("fexcorePreset", thorFex);
            shortcut.saveData();
        }
    }

    /**
     * Earlier builds never put DXVK's files into the container: the launcher asked for
     * "dxvk-2.6.2-thor-arm64x-1" and the installed package is called "DXVK-2.6.2-thor-arm64x-1",
     * so WoW got Wine's own Direct3D and crashed. The lookup now ignores the case, but the
     * container remembers that it already "has" that DXVK and wouldn't copy the files again.
     * This forgets it once, so the next start copies them.
     */
    public static void redoThorDxvkFiles(ContainerManager manager) {
        for (Container container : manager.getContainers()) {
            if (!needsDxvkRedo(container.getExtra("dxwrapper"), container.getExtra(DXVK_REDONE))) continue;
            container.putExtra("dxwrapper", null);
            container.putExtra(DXVK_REDONE, "1");
            container.saveData();
        }
    }

    static final String DXVK_REDONE = "thorDxvkFilesRedone";

    static boolean needsDxvkRedo(String dxwrapper, String redone) {
        return redone.isEmpty() && dxwrapper.toLowerCase(Locale.ROOT).startsWith("dxvk-2.6.2-thor-arm64x");
    }

    private static void writeDesktopEntry(Context context, File file, Container container, String name, String dosPath) throws IOException {
        String winePrefix = wineHome(context, container) + "/.wine";
        try (PrintWriter writer = new PrintWriter(new FileWriter(file))) {
            writer.println("[Desktop Entry]");
            writer.println("Name=" + name);
            writer.println("Exec=env WINEPREFIX=\"" + winePrefix + "\" wine " + execPath(dosPath));
            writer.println("Type=Application");
            writer.println("container_id:" + container.id);
        }
    }

    /**
     * The program's path as the Exec line needs it, so that Shortcut reads back exactly dosPath.
     * StringUtils.escapeFileDOSPath can't be used: it turns spaces into backslashes, which
     * Shortcut drops, so "Battle.net Launcher.exe" became "Battle.netLauncher.exe" (Wine:
     * "File not found").
     */
    static String execPath(String dosPath) {
        return dosPath.replace("\\", "\\\\\\\\");
    }

    private static String wineHome(Context context, Container container) {
        String imagefs = ImageFs.find(context).getRootDir().getAbsolutePath();
        String root = container.getRootDir().getAbsolutePath();
        return root.startsWith(imagefs) ? root.substring(imagefs.length()) : "/home/" + ImageFs.USER;
    }

    /** Downloads Blizzard's Battle.net installer to INSTALLER_DOS_PATH. */
    public static void downloadInstaller(Container container, Progress progress) throws IOException {
        File part = partFile(container);
        download(INSTALLER_URL, part, progress);
        finish(part, ThorGame.toAndroidPath(container, INSTALLER_DOS_PATH));
    }

    /** Copies an installer the player picked (when the download failed) to INSTALLER_DOS_PATH. */
    public static void copyInstaller(Container container, InputStream in, long size, Progress progress) throws IOException {
        File part = partFile(container);
        copy(in, part, size, progress);
        finish(part, ThorGame.toAndroidPath(container, INSTALLER_DOS_PATH));
    }

    private static File partFile(Container container) throws IOException {
        File dir = ThorGame.toAndroidPath(container, INSTALLER_DOS_PATH).getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Could not create " + dir);
        return new File(dir, "Battle.net-Setup.exe.part");
    }

    private static void download(String url, File part, Progress progress) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(30000);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) throw new IOException("Blizzard's server answered " + code);
            try (InputStream in = connection.getInputStream()) {
                copy(in, part, connection.getContentLengthLong(), progress);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static void copy(InputStream in, File part, long total, Progress progress) throws IOException {
        try (OutputStream out = new FileOutputStream(part)) {
            byte[] buffer = new byte[65536];
            long done = 0;
            int lastPercent = -2;
            for (int n; (n = in.read(buffer)) > 0; ) {
                out.write(buffer, 0, n);
                done += n;
                int percent = total > 0 ? (int) (done * 100 / total) : -1;
                if (percent != lastPercent) {
                    progress.onProgress(percent);
                    lastPercent = percent;
                }
            }
        }
    }

    /** Checks the file is a Windows program, then puts it in place. */
    private static void finish(File part, File target) throws IOException {
        if (!looksLikeExe(part)) throw new IOException("That file is not the Battle.net installer");
        if (!part.renameTo(target)) {
            FileUtils.copy(part, target);
            if (!target.isFile() || target.length() != part.length()) throw new IOException("Could not save " + target);
        }
    }

    private static boolean looksLikeExe(File file) {
        if (file.length() < MIN_INSTALLER_BYTES) return false;
        try (InputStream in = new java.io.FileInputStream(file)) {
            return in.read() == 'M' && in.read() == 'Z';
        } catch (IOException e) {
            return false;
        }
    }
}
