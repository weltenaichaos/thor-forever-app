package com.winlator.cmod.core;

import android.util.Log;

import com.winlator.cmod.winhandler.WinHandler;

import java.io.File;
import java.io.FileInputStream;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Starts World of Warcraft on one CPU core and frees all cores once its startup is over,
 * like the Thor Forever launcher's AFFINITY=one-then-all. With esync, WoW's main thread
 * sometimes crashes at startup (a stack overflow, then a black window) when its startup
 * threads run on several cores at once; on one core that did not happen in testing.
 *
 * Only the CPU affinity of WoW's process is changed: at once for all its threads with
 * Android's taskset, and through Wine's SetProcessAffinityMask (the same call the task
 * manager uses), so threads WoW starts later follow it too.
 */
public class WowStartupAffinity {
    private static final String TAG = "WowStartupAffinity";
    private static final long POLL_MS = 100;
    private static final long[] RELEASE_AFTER_MS = {30000, 60000};

    private final WinHandler winHandler;
    private final int startupMask;
    private final int releaseMask;
    private final Set<Integer> handledPids = new HashSet<>();
    private final Set<String> pinnedNames = new HashSet<>();
    private ScheduledExecutorService executor;

    /** cpuMask: the cores the container lets programs use (0 = all). */
    public WowStartupAffinity(WinHandler winHandler, int cpuMask) {
        this.winHandler = winHandler;
        int online = readOnlineMask();
        this.releaseMask = cpuMask != 0 ? cpuMask : online;
        this.startupMask = startupCoreMask(releaseMask, online);
    }

    public synchronized void start() {
        if (executor != null || startupMask == 0) return;
        executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleWithFixedDelay(this::poll, 0, POLL_MS, TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        if (executor != null) executor.shutdownNow();
        executor = null;
    }

    /** True while the program with this name is held on one core, so nothing else changes its cores. */
    public synchronized boolean isPinned(String exeName) {
        return exeName != null && pinnedNames.contains(exeName.toLowerCase(Locale.ROOT));
    }

    private void poll() {
        File[] dirs = new File("/proc").listFiles();
        if (dirs == null) return;
        for (File dir : dirs) {
            String pidText = dir.getName();
            if (pidText.isEmpty() || !Character.isDigit(pidText.charAt(0))) continue;
            int pid;
            try { pid = Integer.parseInt(pidText); } catch (NumberFormatException e) { continue; }
            synchronized (this) { if (handledPids.contains(pid)) continue; }
            String comm = readText(new File(dir, "comm")).trim();
            if (!isWowComm(comm)) continue;
            String exeName = exeNameFromCmdline(readText(new File(dir, "cmdline")), comm);
            pin(pid, exeName);
        }
    }

    private void pin(int pid, String exeName) {
        String key = exeName.toLowerCase(Locale.ROOT);
        synchronized (this) {
            handledPids.add(pid);
            pinnedNames.add(key);
        }
        Log.d(TAG, "Starting " + exeName + " (pid " + pid + ") on one core, mask 0x" + Integer.toHexString(startupMask));
        setThreadsAffinity(pid, startupMask);
        winHandler.setProcessAffinity(exeName, startupMask);
        for (long delay : RELEASE_AFTER_MS) {
            ScheduledExecutorService ex;
            synchronized (this) { ex = executor; }
            if (ex == null) return;
            ex.schedule(() -> {
                synchronized (this) { pinnedNames.remove(key); }
                Log.d(TAG, "Freeing all cores for " + exeName + ", mask 0x" + Integer.toHexString(releaseMask));
                setThreadsAffinity(pid, releaseMask);
                winHandler.setProcessAffinity(exeName, releaseMask);
            }, delay, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Sets the cores of every thread of the process right away with Android's taskset. The
     * request through Wine alone sometimes arrived seconds late or not at all, after WoW had
     * already crashed. Wine's processes run as this app's user, so this is allowed.
     */
    private static void setThreadsAffinity(int pid, int mask) {
        try {
            Process process = new ProcessBuilder("taskset", "-a", "-p", Integer.toHexString(mask), String.valueOf(pid))
                    .redirectErrorStream(true).start();
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[256];
            for (int n; (n = process.getInputStream().read(buffer)) > 0; ) bytes.write(buffer, 0, n);
            String output = bytes.toString().trim();
            int exit = process.waitFor();
            Log.d(TAG, "taskset pid " + pid + " mask 0x" + Integer.toHexString(mask) + " exit " + exit + (output.isEmpty() ? "" : ": " + output));
        } catch (Exception e) {
            Log.w(TAG, "taskset failed for pid " + pid, e);
        }
    }

    /** Wine names each process after its .exe; Linux cuts the name to 15 characters. */
    static boolean isWowComm(String comm) {
        String c = comm.toLowerCase(Locale.ROOT);
        return c.startsWith("wow") && (c.endsWith(".exe") || c.length() == 15);
    }

    /** The full .exe name for Wine, taken from the command line, or the process name when it is complete. */
    static String exeNameFromCmdline(String cmdline, String comm) {
        String prefix = comm.toLowerCase(Locale.ROOT);
        if (prefix.endsWith(".exe")) return comm;
        for (String arg : cmdline.split("\0")) {
            String name = arg.substring(Math.max(arg.lastIndexOf('\\'), arg.lastIndexOf('/')) + 1);
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.startsWith(prefix) && lower.endsWith(".exe")) return name;
        }
        return comm;
    }

    /** The highest core that is allowed and online: the prime core on Snapdragon chips. */
    static int startupCoreMask(int allowed, int online) {
        int usable = allowed & online;
        if (usable == 0) return 0;
        return Integer.highestOneBit(usable);
    }

    /** Parses a CPU list like "0-3,5,7" into a bit mask. */
    static int parseCpuRanges(String list) {
        int mask = 0;
        for (String part : list.trim().split(",")) {
            if (part.isEmpty()) continue;
            String[] range = part.split("-");
            try {
                int from = Integer.parseInt(range[0].trim());
                int to = range.length > 1 ? Integer.parseInt(range[1].trim()) : from;
                for (int i = from; i <= to && i < 31; i++) mask |= 1 << i;
            } catch (NumberFormatException ignored) {}
        }
        return mask;
    }

    private static int readOnlineMask() {
        int mask = parseCpuRanges(readText(new File("/sys/devices/system/cpu/online")));
        if (mask != 0) return mask;
        int count = Runtime.getRuntime().availableProcessors();
        return count >= 31 ? 0x7fffffff : (1 << count) - 1;
    }

    private static String readText(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[1024];
            int count = in.read(buffer);
            return count > 0 ? new String(buffer, 0, count) : "";
        } catch (Exception e) {
            return "";
        }
    }
}
