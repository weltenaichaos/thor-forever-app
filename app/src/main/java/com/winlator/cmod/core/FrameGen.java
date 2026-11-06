package com.winlator.cmod.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.winlator.cmod.SettingsFragment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Frame generation for WoW: the lsfg-vk Vulkan layer (framegen/ in this repo) draws extra
 * pictures between the game's real ones. It only sees finished pictures on their way to the
 * screen, like the Vulkan driver does; it never touches the game's memory, code or files.
 *
 * The layer needs Lossless.dll from the player's own Lossless Scaling (Steam). The player picks
 * it once; it is copied into this app's private folder and never leaves the device.
 *
 * At launch, when frame generation is on: the layer's manifest goes into the container's
 * explicit layer folder, a config file names WoW's .exe (so Battle.net never gets frame
 * generation), and VK_INSTANCE_LAYERS turns the layer on. When it is off, the manifest is removed.
 */
public final class FrameGen {
    private static final String TAG = "FrameGen";
    public static final String LAYER_NAME = "VK_LAYER_LS_frame_generation";
    private static final String LAYER_LIB = "liblsfg-vk.so";
    private static final String MANIFEST = "VkLayer_LS_frame_generation.json";

    private static final String KEY_MODE = "thor_framegen_mode";
    private static final String KEY_FLOW = "thor_framegen_flow";
    private static final String KEY_PERF = "thor_framegen_perf";

    /** Off, 2x, 3x: how many pictures are shown for every real one (0 = off). */
    public static final int[] MODES = {0, 2, 3};
    /** Quality (the layer's flow scale) in percent. 50 was the best setting for D2R on the Thor. */
    public static final int[] FLOWS = {50, 75, 100};
    public static final int DEFAULT_FLOW = 50;

    /** Every name WoW's own .exe goes by; Wine names the process after it. */
    private static final String[] WOW_EXES = {
        "Wow.exe", "WowB.exe", "WowT.exe", "Wow-ARM64.exe", "WowB-ARM64.exe", "WowT-ARM64.exe",
        "WowClassic.exe", "WowClassicB.exe", "WowClassicT.exe",
        "WowClassic-ARM64.exe", "WowClassicB-ARM64.exe", "WowClassicT-ARM64.exe"
    };

    private FrameGen() {}

    private static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    public static int getMode(Context context) { return prefs(context).getInt(KEY_MODE, 0); }
    public static void setMode(Context context, int mode) { prefs(context).edit().putInt(KEY_MODE, mode).apply(); }
    public static int getFlow(Context context) { return prefs(context).getInt(KEY_FLOW, DEFAULT_FLOW); }
    public static void setFlow(Context context, int flow) { prefs(context).edit().putInt(KEY_FLOW, flow).apply(); }
    public static boolean getPerformance(Context context) { return prefs(context).getBoolean(KEY_PERF, true); }
    public static void setPerformance(Context context, boolean on) { prefs(context).edit().putBoolean(KEY_PERF, on).apply(); }

    /** True when the next WoW start gets frame generation. */
    public static boolean isActive(Context context) {
        return getMode(context) >= 2 && isInBuild(context) && hasDll(context);
    }

    /**
     * The FPS cap for the screen. With frame generation the cap counts the game's real frames
     * (the layer holds them to it), so the screen may show mode times as many.
     */
    public static int displayLimit(Context context, int cap) {
        if (cap <= 0 || !isActive(context)) return cap;
        return cap * getMode(context);
    }

    /** The layer library, built by CI into the APK; missing in local builds. */
    public static File layerLibrary(Context context) {
        return new File(context.getApplicationInfo().nativeLibraryDir, LAYER_LIB);
    }

    public static boolean isInBuild(Context context) {
        return layerLibrary(context).isFile();
    }

    private static File dir(Context context) {
        return new File(context.getFilesDir(), "framegen");
    }

    /** The player's Lossless.dll, in this app's private folder. */
    public static File dll(Context context) {
        return new File(dir(context), "Lossless.dll");
    }

    /** True when a Lossless.dll was chosen and still looks like a Windows DLL. */
    public static boolean hasDll(Context context) {
        File file = dll(context);
        return file.isFile() && looksLikeDll(file);
    }

    private static boolean looksLikeDll(File file) {
        if (file.length() < 64 * 1024) return false;
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            return raf.read() == 'M' && raf.read() == 'Z';
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Copies the chosen file in as Lossless.dll. Throws with a short reason when it is not a
     * Windows DLL; the old copy stays then.
     */
    public static void importDll(Context context, InputStream input) throws IOException {
        File folder = dir(context);
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Could not make the app's folder");
        File temp = new File(folder, "Lossless.dll.new");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            long total = 0;
            while ((n = input.read(buffer)) > 0) {
                out.write(buffer, 0, n);
                total += n;
                if (total > 512L * 1024 * 1024) throw new IOException("The file is far too big for Lossless.dll");
            }
        }
        if (!looksLikeDll(temp)) {
            temp.delete();
            throw new IOException("That file is not a Windows DLL. Choose Lossless.dll from the Lossless Scaling folder.");
        }
        File target = dll(context);
        if (!temp.renameTo(target)) {
            temp.delete();
            throw new IOException("Could not save the file");
        }
    }

    /** Where the layer's config (and its stats.txt next to it) go: the logs folder, so a test run can be shared. */
    private static File configFile() {
        return new File(new File(SettingsFragment.DEFAULT_WINLATOR_PATH, "logs/framegen"), "conf.toml");
    }

    /** WoW's .exe of this launch (from the shortcut), when it is one, else null. */
    private static String wowExe(String executable) {
        if (executable == null) return null;
        String name = executable.trim();
        int slash = Math.max(name.lastIndexOf('\\'), name.lastIndexOf('/'));
        if (slash >= 0) name = name.substring(slash + 1);
        int exe = name.toLowerCase(Locale.ROOT).indexOf(".exe");
        if (exe < 0) return null;
        name = name.substring(0, exe + 4);
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.startsWith("wow") && !lower.startsWith("wowvoice") ? name : null;
    }

    /**
     * Sets up (or clears) frame generation for a launch. layerDir is the container's explicit
     * layer folder (on VK_LAYER_PATH). executable is the shortcut's .exe or null, fpsCap the start
     * screen's FPS cap (0 = none). Returns true
     * when the layer is to be turned on: the caller then adds LAYER_NAME to VK_INSTANCE_LAYERS.
     */
    public static boolean prepareLaunch(Context context, File layerDir, String executable, int fpsCap, EnvVars env) {
        File manifest = new File(layerDir, MANIFEST);
        int mode = getMode(context);
        boolean on = isActive(context);
        if (!on) {
            if (manifest.exists() && !manifest.delete()) Log.w(TAG, "Could not remove " + manifest);
            Log.d(TAG, "Frame generation off (mode " + mode + ", in build " + isInBuild(context) + ", dll " + hasDll(context) + ")");
            return false;
        }
        try {
            if (!layerDir.isDirectory() && !layerDir.mkdirs()) throw new IOException("no layer folder " + layerDir);
            String json = "{\n"
                + "  \"file_format_version\": \"1.0.0\",\n"
                + "  \"layer\": {\n"
                + "    \"name\": \"" + LAYER_NAME + "\",\n"
                + "    \"type\": \"GLOBAL\",\n"
                + "    \"api_version\": \"1.4.313\",\n"
                + "    \"library_path\": \"" + layerLibrary(context).getAbsolutePath() + "\",\n"
                + "    \"implementation_version\": \"1\",\n"
                + "    \"description\": \"Lossless Scaling frame generation layer\",\n"
                + "    \"functions\": {\n"
                + "      \"vkGetInstanceProcAddr\": \"layer_vkGetInstanceProcAddr\",\n"
                + "      \"vkGetDeviceProcAddr\": \"layer_vkGetDeviceProcAddr\"\n"
                + "    },\n"
                + "    \"disable_environment\": { \"DISABLE_LSFG\": \"1\" }\n"
                + "  }\n"
                + "}\n";
            write(manifest, json);

            Set<String> exes = new LinkedHashSet<>();
            String launched = wowExe(executable);
            if (launched != null) exes.add(launched);
            for (String name : WOW_EXES) exes.add(name);
            // Linux keeps only the first 15 characters of a process name (WowClassic-ARM6).
            for (String name : new java.util.ArrayList<>(exes)) if (name.length() > 15) exes.add(name.substring(0, 15));
            int flow = getFlow(context);
            StringBuilder toml = new StringBuilder();
            toml.append("# Written by Thor Forever at every launch; changes here are overwritten.\n");
            toml.append("version = 1\n\n[global]\n");
            toml.append("dll = \"").append(dll(context).getAbsolutePath()).append("\"\n");
            // The start screen's FPS cap holds the game's real frames; the screen shows mode times as many.
            if (fpsCap > 0) toml.append("fps_limit = ").append(fpsCap).append('\n');
            for (String name : exes) {
                toml.append("\n[[game]]\n");
                toml.append("exe = \"").append(name).append("\"\n");
                toml.append("multiplier = ").append(mode).append('\n');
                toml.append("flow_scale = ").append(String.format(Locale.US, "%.2f", flow / 100f)).append('\n');
                toml.append("performance_mode = ").append(getPerformance(context)).append('\n');
            }
            File config = configFile();
            File configDir = config.getParentFile();
            if (configDir != null && !configDir.isDirectory()) configDir.mkdirs();
            write(config, toml.toString());

            env.put("LSFG_CONFIG", config.getAbsolutePath());
            env.remove("LSFG_LEGACY");
            env.remove("DISABLE_LSFG");
            Log.d(TAG, "Frame generation on: " + mode + "x, quality " + flow + "%, performance " + getPerformance(context) + ", exe " + launched);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Could not set up frame generation, starting without it", e);
            manifest.delete();
            return false;
        }
    }

    private static void write(File file, String text) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }
}
