package com.winlator.cmod.core;

import android.util.Log;

import com.winlator.cmod.xserver.XLock;
import com.winlator.cmod.xserver.XServer;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * For the start screen's "Update with Battle.net": closes the session once Battle.net's
 * window is closed, so the player is back on the start screen. Battle.net often stays in the
 * tray after its window closes, so it watches the window, not the process. While WoW runs
 * (Play pressed in Battle.net) it waits, and the WoW exit watcher takes over. While Battle.net's
 * installer runs it waits too, as the installer can swap windows on its way to the app.
 */
public class BattleNetWindowWatcher {
    private static final String TAG = "BattleNetWindowWatcher";
    private static final long CHECK_MS = 1000;
    /** Checks in a row without a window, so a window swap (login to main window) doesn't count. */
    private static final int GONE_CHECKS = 5;

    private final XServer xServer;
    private final Runnable onClosed;
    private ScheduledExecutorService executor;
    private boolean seen;
    private int goneChecks;

    public BattleNetWindowWatcher(XServer xServer, Runnable onClosed) {
        this.xServer = xServer;
        this.onClosed = onClosed;
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
        boolean open;
        try (XLock lock = xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
            open = xServer.windowManager.hasApplicationWindowOfClass("battle.net");
        }
        java.util.List<String> processes = WowExitWatcher.runningProcessNames();
        boolean installing = isInstallerRunning(processes);
        boolean busy = WowExitWatcher.isWowRunning(processes) || installing;
        synchronized (this) {
            if (executor == null) return;
            if (open || busy) {
                // The installer's own window doesn't count: once it is done, Battle.net takes a
                // while to show its login window, and the session must wait for that one.
                seen = installing ? false : seen | open;
                goneChecks = 0;
                return;
            }
            if (!seen || ++goneChecks < GONE_CHECKS) return;
        }
        Log.i(TAG, "Battle.net's window was closed, closing the session");
        stop();
        onClosed.run();
    }

    /** True while Battle.net's installer runs (Linux cuts process names to 15 characters). */
    static boolean isInstallerRunning(java.util.List<String> processNames) {
        for (String name : processNames) {
            String n = name.toLowerCase(java.util.Locale.ROOT);
            if (n.startsWith("battle.net-setu") || (n.startsWith("battle.net") && n.contains("install"))) return true;
        }
        return false;
    }
}
