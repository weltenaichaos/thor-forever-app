package com.winlator.cmod.core;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Closes the session when World of Warcraft has quit. WoW leaves Blizzard's agent.exe
 * running, which keeps Wine's desktop open, so the app used to stay on an empty screen
 * until it was closed by hand. Waits while WoW's error reporter is still open, so a
 * crash report is still written.
 */
public class WowExitWatcher {
    private static final String TAG = "WowExitWatcher";
    private static final long CHECK_MS = 1000;
    /** Checks in a row without WoW before closing, so a restart of WoW is not cut off. */
    private static final int GONE_CHECKS = 5;

    private final Runnable onWowExited;
    private ScheduledExecutorService executor;
    private boolean wowSeen;
    private int goneChecks;

    public WowExitWatcher(Runnable onWowExited) {
        this.onWowExited = onWowExited;
    }

    public synchronized void start() {
        if (executor != null) return;
        executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleWithFixedDelay(this::check, CHECK_MS, CHECK_MS, TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        if (executor != null) executor.shutdownNow();
        executor = null;
    }

    private void check() {
        boolean running = isWowRunning(runningProcessNames());
        synchronized (this) {
            if (executor == null) return;
            if (running) {
                wowSeen = true;
                goneChecks = 0;
                return;
            }
            if (!wowSeen || ++goneChecks < GONE_CHECKS) return;
        }
        Log.i(TAG, "World of Warcraft has quit, closing the session");
        stop();
        onWowExited.run();
    }

    /** True while WoW or its error reporter runs. Process names are cut to 15 characters. */
    static boolean isWowRunning(List<String> processNames) {
        for (String name : processNames) {
            String n = name.toLowerCase(Locale.ROOT);
            if (n.startsWith("wow") || n.startsWith("blizzarderror")) return true;
        }
        return false;
    }

    static List<String> runningProcessNames() {
        List<String> names = new ArrayList<>();
        File[] dirs = new File("/proc").listFiles();
        if (dirs == null) return names;
        for (File dir : dirs) {
            String name = dir.getName();
            if (name.isEmpty() || !Character.isDigit(name.charAt(0))) continue;
            names.add(readText(new File(dir, "comm")).trim());
        }
        return names;
    }

    private static String readText(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[256];
            int length = in.read(buffer);
            return length > 0 ? new String(buffer, 0, length) : "";
        } catch (Exception e) {
            return "";
        }
    }
}
