package com.winlator.cmod.core;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Closes WoW's voice chat process (WowVoiceProxy) shortly after it starts, like ending it in
 * Task Manager on a PC. WoW has no setting that stops it, it can't connect under Wine anyway,
 * and it still used about 5% of a core. Only the running process is ended; no game file is
 * changed, and WoW starts it again at the next login.
 */
public class WowVoiceCloser {
    private static final String TAG = "WowVoiceCloser";
    private static final long CHECK_MS = 2000;
    /** How long the process may run first, so WoW's login is not disturbed. */
    private static final long CLOSE_AFTER_MS = 10000;
    /** If WoW keeps restarting it, stop closing it, so closing doesn't cost more than it saves. */
    private static final int MAX_CLOSES = 5;

    private final Map<Integer, Long> seenAt = new HashMap<>();
    private ScheduledExecutorService executor;
    private int closes;

    public synchronized void start() {
        if (executor != null) return;
        executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleWithFixedDelay(this::check, CHECK_MS, CHECK_MS, TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        if (executor != null) executor.shutdownNow();
        executor = null;
    }

    private synchronized void check() {
        if (executor == null) return;
        long now = System.currentTimeMillis();
        File[] dirs = new File("/proc").listFiles();
        if (dirs == null) return;
        for (File dir : dirs) {
            String name = dir.getName();
            if (name.isEmpty() || !Character.isDigit(name.charAt(0))) continue;
            if (!isVoiceProxy(readText(new File(dir, "comm")).trim())) continue;
            int pid;
            try { pid = Integer.parseInt(name); } catch (NumberFormatException e) { continue; }
            Long first = seenAt.get(pid);
            if (first == null) {
                seenAt.put(pid, now);
            } else if (now - first >= CLOSE_AFTER_MS) {
                closes++;
                android.os.Process.killProcess(pid);
                Log.i(TAG, "Closed WoW's voice chat, pid " + pid);
                if (closes >= MAX_CLOSES) {
                    Log.i(TAG, "WoW keeps restarting its voice chat, leaving it running");
                    stop();
                    return;
                }
            }
        }
        for (Iterator<Integer> it = seenAt.keySet().iterator(); it.hasNext(); ) {
            if (!new File("/proc/" + it.next()).exists()) it.remove();
        }
    }

    /** WoW's voice chat process. Linux cuts process names to 15 characters. */
    static boolean isVoiceProxy(String comm) {
        return comm.toLowerCase(Locale.ROOT).startsWith("wowvoiceproxy");
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
