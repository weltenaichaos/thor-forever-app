package com.winlator.cmod.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns off WoW's screen narration before the game starts. Under Wine the narration
 * (text-to-speech) path crashes WoW at the intro, so the Thor Forever launcher ships a
 * Config.wtf with these cvars set to "0". Only these lines are changed; the rest of the
 * file is left as it is.
 */
public final class WowConfigDefaults {
    static final String[] CVARS = {
            "showScreenNarrationDialog",
            "accessibilityScreenNarrationEnabled",
            "textToSpeech"
    };

    private static final String[] INSTALL_DIRS = {
            "Program Files (x86)/World of Warcraft",
            "Program Files/World of Warcraft"
    };

    private WowConfigDefaults() {}

    /** Applies the cvars to every WoW flavor folder (_retail_, _classic_, ...) under drive_c. Returns the files written. */
    public static List<File> apply(File driveC) {
        List<File> written = new ArrayList<>();
        for (String installDir : INSTALL_DIRS) {
            File[] flavors = new File(driveC, installDir).listFiles();
            if (flavors == null) continue;
            for (File flavor : flavors) {
                if (!isFlavorDir(flavor)) continue;
                File config = new File(flavor, "WTF/Config.wtf");
                try {
                    if (applyToFile(config)) written.add(config);
                } catch (IOException ignored) {
                }
            }
        }
        return written;
    }

    static boolean isFlavorDir(File dir) {
        String name = dir.getName();
        if (!dir.isDirectory() || name.length() < 3 || !name.startsWith("_") || !name.endsWith("_")) return false;
        File[] files = dir.listFiles();
        if (files == null) return false;
        for (File f : files) {
            String lower = f.getName().toLowerCase(Locale.ROOT);
            if (f.isFile() && lower.startsWith("wow") && lower.endsWith(".exe")) return true;
        }
        return false;
    }

    /** Returns true when the file was created or changed. */
    static boolean applyToFile(File config) throws IOException {
        String before = config.isFile() ? new String(Files.readAllBytes(config.toPath()), StandardCharsets.UTF_8) : "";
        String after = applyCvars(before);
        if (after.equals(before)) return false;
        File parent = config.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create " + parent);
        Files.write(config.toPath(), after.getBytes(StandardCharsets.UTF_8));
        return true;
    }

    static String applyCvars(String content) {
        String newline = content.contains("\r\n") ? "\r\n" : "\n";
        String result = content;
        for (String cvar : CVARS) {
            String line = "SET " + cvar + " \"0\"";
            Pattern pattern = Pattern.compile("(?im)^[ \\t]*SET[ \\t]+" + Pattern.quote(cvar) + "[ \\t]+[^\\r\\n]*");
            Matcher matcher = pattern.matcher(result);
            if (matcher.find()) {
                result = matcher.replaceAll(Matcher.quoteReplacement(line));
            } else {
                if (!result.isEmpty() && !result.endsWith("\n")) result += newline;
                result += line + newline;
            }
        }
        return result;
    }
}
