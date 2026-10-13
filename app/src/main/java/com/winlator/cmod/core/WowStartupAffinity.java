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
    /** When the main thread gets the fast core: just after the first release, during login. */
    private static final long FAST_CORE_START_MS = 31000;
    private static final long FAST_CORE_CHECK_MS = 2000;

    private final WinHandler winHandler;
    private final int startupMask;
    private final int releaseMask;
    private final boolean mainThreadOnFastCore;
    private final Set<Integer> handledPids = new HashSet<>();
    private final Set<String> pinnedNames = new HashSet<>();
    private final Set<String> startedNames = new HashSet<>();
    private ScheduledExecutorService executor;

    /** cpuMask: the cores the container lets programs use (0 = all). */
    public WowStartupAffinity(WinHandler winHandler, int cpuMask, boolean mainThreadOnFastCore) {
        this.winHandler = winHandler;
        this.mainThreadOnFastCore = mainThreadOnFastCore;
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
            synchronized (this) {
                handledPids.add(pid);
                // Only the game's first start: WoW later starts more processes under its own
                // name (for its voice chat and error reports). Pinning by name then put the
                // running game back on one core for 30 seconds each time.
                if (!startedNames.add(exeName.toLowerCase(Locale.ROOT))) continue;
            }
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
        int restMask = releaseMask & ~startupMask;
        boolean split = mainThreadOnFastCore && restMask != 0;
        for (long delay : RELEASE_AFTER_MS) {
            // The split below keeps the cores right from then on. A second release would spread
            // all threads out in the middle of the game, only to move them back 2 seconds later.
            if (split && delay > RELEASE_AFTER_MS[0]) break;
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
        if (split) {
            ScheduledExecutorService ex;
            synchronized (this) { ex = executor; }
            if (ex == null) return;
            Log.d(TAG, "Keeping the main thread of " + exeName + " on mask 0x" + Integer.toHexString(startupMask)
                    + ", its other threads on 0x" + Integer.toHexString(restMask));
            ex.scheduleWithFixedDelay(() -> keepMainThreadOnFastCore(pid, restMask),
                    FAST_CORE_START_MS, FAST_CORE_CHECK_MS, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * WoW does most of its work on its main thread, which is the limit in crowded places. On
     * the fastest core (the prime core on Snapdragon chips) a crowded city ran at about 25 fps
     * instead of about 21. So the main thread gets the fastest core to itself. Wine's server, which
     * the main thread waits for, gets the next fast core to itself: that took the city to 30-45 fps.
     * WoW's other threads use the rest. Checked every 2 seconds, because new threads and Wine
     * can change the cores.
     */
    private void keepMainThreadOnFastCore(int pid, int restMask) {
        File[] tasks = new File("/proc/" + pid + "/task").listFiles();
        if (tasks == null) throw new RuntimeException("WoW exited"); // ends this repeating task
        StringBuilder moves = new StringBuilder();
        int count = 0;
        for (File task : tasks) {
            int tid;
            try { tid = Integer.parseInt(task.getName()); } catch (NumberFormatException e) { continue; }
            int wanted = tid == pid ? startupMask : threadsMask(restMask);
            int current = parseHexMask(statusField(readText(new File(task, "status")), "Cpus_allowed:"));
            if (current != wanted) {
                moves.append("taskset -p ").append(Integer.toHexString(wanted)).append(' ').append(tid).append(" >/dev/null;");
                count++;
            }
        }
        // One shell for all threads: starting a program per thread took about half a second
        // for WoW's 80 threads and stalled the game for a moment.
        if (count > 0) runShell(moves.toString(), "moved " + count + " threads of pid " + pid);
        // Wine's server process answers WoW's calls all the time, and the main thread waits for
        // its answers. It gets the fastest of the remaining cores to itself.
        if (wineserverPid == 0) wineserverPid = findProcess("wineserver");
        if (wineserverPid > 0) {
            int wanted = serverMask(restMask);
            int current = parseHexMask(statusField(readText(new File("/proc/" + wineserverPid + "/status")), "Cpus_allowed:"));
            if (current != 0 && current != wanted) setThreadsAffinity(wineserverPid, wanted);
        }
    }

    /** The highest core of the mask, which is one of the fast ones; 0 when the mask has one core. */
    static int serverMask(int restMask) {
        int highest = Integer.highestOneBit(restMask);
        return highest == restMask ? restMask : highest;
    }

    /** The cores for WoW's other threads: all remaining ones except Wine's server core. */
    static int threadsMask(int restMask) {
        int server = serverMask(restMask);
        return server == restMask ? restMask : restMask & ~server;
    }

    private int wineserverPid;

    private static int findProcess(String comm) {
        File[] dirs = new File("/proc").listFiles();
        if (dirs == null) return 0;
        for (File dir : dirs) {
            String name = dir.getName();
            if (name.isEmpty() || !Character.isDigit(name.charAt(0))) continue;
            if (comm.equals(readText(new File(dir, "comm")).trim())) {
                try { return Integer.parseInt(name); } catch (NumberFormatException e) { return 0; }
            }
        }
        return 0;
    }

    /**
     * Sets the cores of every thread of the process right away with Android's taskset. The
     * request through Wine alone sometimes arrived seconds late or not at all, after WoW had
     * already crashed. Wine's processes run as this app's user, so this is allowed.
     */
    private static void setThreadsAffinity(int pid, int mask) {
        runTaskset(pid, mask, true);
    }

    private static void runShell(String script, String what) {
        try {
            Process process = new ProcessBuilder("sh", "-c", script).redirectErrorStream(true).start();
            byte[] buffer = new byte[256];
            while (process.getInputStream().read(buffer) > 0) { /* drain */ }
            int exit = process.waitFor();
            Log.d(TAG, what + ", exit " + exit);
        } catch (Exception e) {
            Log.w(TAG, "shell failed: " + what, e);
        }
    }

    private static void runTaskset(int pid, int mask, boolean allThreads) {
        try {
            Process process = (allThreads
                    ? new ProcessBuilder("taskset", "-a", "-p", Integer.toHexString(mask), String.valueOf(pid))
                    : new ProcessBuilder("taskset", "-p", Integer.toHexString(mask), String.valueOf(pid)))
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

    /** Parses a Cpus_allowed value like "ff" or "00000000,000000ff" into a bit mask (0 if unreadable). */
    static int parseHexMask(String hex) {
        String digits = hex.replace(",", "").trim();
        if (digits.isEmpty()) return 0;
        if (digits.length() > 7) digits = digits.substring(digits.length() - 7);
        try { return Integer.parseInt(digits, 16); } catch (NumberFormatException e) { return 0; }
    }

    private static String statusField(String status, String key) {
        for (String line : status.split("\n")) {
            if (line.startsWith(key)) return line.substring(key.length()).trim();
        }
        return "";
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
            byte[] buffer = new byte[4096];
            int count = 0;
            for (int n; count < buffer.length && (n = in.read(buffer, count, buffer.length - count)) > 0; ) count += n;
            return new String(buffer, 0, count);
        } catch (Exception e) {
            return "";
        }
    }
}
