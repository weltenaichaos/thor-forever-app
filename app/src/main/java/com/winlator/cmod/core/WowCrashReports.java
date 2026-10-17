package com.winlator.cmod.core;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Copies WoW's own crash reports out of the game folder (its Errors folder, inside the
 * container where they can't be opened on the device) to Winlator/logs/wow-errors.
 * Runs once when the game closes and once before it starts (a crash can end the app too),
 * so nothing runs while the game is played. Reports copied before are skipped; the game's
 * own files are only read, never changed or removed.
 */
public final class WowCrashReports {
    private static final String[] INSTALL_DIRS = {
            "Program Files (x86)/World of Warcraft",
            "Program Files/World of Warcraft"
    };

    private WowCrashReports() {}

    /** Copies the reports not copied yet from every WoW flavor folder under drive_c into target. Returns the new copies. */
    public static List<File> copyNew(File driveC, File target) {
        List<File> copied = new ArrayList<>();
        for (String installDir : INSTALL_DIRS) {
            File[] flavors = new File(driveC, installDir).listFiles();
            if (flavors == null) continue;
            for (File flavor : flavors) {
                if (!WowConfigDefaults.isFlavorDir(flavor)) continue;
                File[] reports = new File(flavor, "Errors").listFiles();
                if (reports == null) continue;
                for (File report : reports) {
                    if (!isReport(report)) continue;
                    File copy = new File(target, report.getName());
                    if (copy.exists()) continue;
                    try {
                        if (!target.isDirectory() && !target.mkdirs()) return copied;
                        Files.copy(report.toPath(), copy.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
                        copied.add(copy);
                    } catch (IOException ignored) {
                    }
                }
            }
        }
        return copied;
    }

    static boolean isReport(File file) {
        String lower = file.getName().toLowerCase(Locale.ROOT);
        return (lower.endsWith(".txt") || lower.endsWith(".log"))
                && file.isFile() && !Files.isSymbolicLink(file.toPath());
    }
}
