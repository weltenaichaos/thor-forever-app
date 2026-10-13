package com.winlator.cmod.core;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Debug build only: finds out what WoW's main thread is doing when a frame takes very long.
 * From one minute after WoW starts, 50 times a second it reads the main thread's state
 * (running, waiting, or waiting for the disk), what it waits in (wchan), and Wine's server's.
 * Every quarter second it also keeps the CPU time of all WoW threads. When a frame took
 * longer than half a second, it writes a summary of that stretch to the CPU log: how the
 * main thread spent it and which threads were busy meanwhile. Only reads, changes nothing.
 */
public final class HitchProbe {
    private static final long SAMPLE_MS = 20;
    private static final long THREADS_MS = 250;
    private static final long HITCH_NS = 500_000_000L;
    private static final int RING = 250; // 5 seconds of samples

    private static final long[] times = new long[RING];
    private static final String[] mainStates = new String[RING];
    private static final String[] serverStates = new String[RING];
    private static int next;

    private static final List<long[]> pendingHitches = new ArrayList<>();
    private static volatile boolean running;
    private static Thread thread;

    private static final long START_DELAY_NS = 60_000_000_000L;
    private static int wowPid;
    private static long wowSeenAt;
    private static int serverPid;
    private static long lastThreadsAt;
    /** Snapshots of all WoW threads' CPU ticks, oldest first: time, then tid -> ticks. */
    private static final List<Object[]> threadSnapshots = new ArrayList<>();

    private HitchProbe() {}

    public static synchronized void start() {
        if (thread != null) return;
        running = true;
        thread = new Thread(HitchProbe::run, "HitchProbe");
        thread.setDaemon(true);
        thread.start();
    }

    public static synchronized void stop() {
        running = false;
        if (thread != null) thread.interrupt();
        thread = null;
    }

    /** Called for every presented frame with the time since the one before. Cheap. */
    public static void onFrame(long now, long interval) {
        if (interval < HITCH_NS || !running) return;
        synchronized (pendingHitches) {
            if (pendingHitches.size() < 20) pendingHitches.add(new long[]{now - interval, now});
        }
    }

    private static void run() {
        while (running) {
            try {
                Thread.sleep(SAMPLE_MS);
            } catch (InterruptedException e) {
                return;
            }
            long now = System.nanoTime();
            if (wowPid == 0 || !new File("/proc/" + wowPid).exists()) {
                wowPid = findProcess("wow");
                serverPid = findProcess("wineserver");
                wowSeenAt = now;
                if (wowPid == 0) continue;
            }
            // Leave WoW's startup alone: build 45 read its threads from the first moment and
            // WoW then did not start.
            if (now - wowSeenAt < START_DELAY_NS) continue;
            String main = describe("/proc/" + wowPid + "/task/" + wowPid);
            String server = serverPid > 0 ? describe("/proc/" + serverPid + "/task/" + serverPid) : "?";
            synchronized (HitchProbe.class) {
                times[next] = now;
                mainStates[next] = main;
                serverStates[next] = server;
                next = (next + 1) % RING;
            }
            if (now - lastThreadsAt >= THREADS_MS * 1_000_000L) {
                lastThreadsAt = now;
                threadSnapshots.add(new Object[]{now, threadTicks(wowPid)});
                while (threadSnapshots.size() > 5000 / THREADS_MS) threadSnapshots.remove(0);
            }
            List<long[]> hitches;
            synchronized (pendingHitches) {
                hitches = new ArrayList<>(pendingHitches);
                pendingHitches.clear();
            }
            for (long[] hitch : hitches) report(hitch[0], hitch[1]);
        }
    }

    /** State letter, wait channel and system call number of one thread, like "S futex_wait_queue sc98". */
    private static String describe(String taskDir) {
        String stat = read(taskDir + "/stat");
        int close = stat.lastIndexOf(')');
        String state = close > 0 && close + 2 < stat.length() ? stat.substring(close + 2, close + 3) : "?";
        if (state.equals("R")) return "R";
        String wchan = read(taskDir + "/wchan").trim();
        return state + " " + (wchan.isEmpty() || wchan.equals("0") ? "-" : wchan);
    }

    private static void report(long start, long end) {
        Map<String, Integer> main = new TreeMap<>();
        Map<String, Integer> server = new TreeMap<>();
        int total = 0;
        synchronized (HitchProbe.class) {
            for (int i = 0; i < RING; i++) {
                if (mainStates[i] == null || times[i] < start || times[i] > end) continue;
                main.merge(mainStates[i], 1, Integer::sum);
                server.merge(serverStates[i], 1, Integer::sum);
                total++;
            }
        }
        StringBuilder line = new StringBuilder(String.format(Locale.US, "hitch %.0f ms, %d samples. main thread: ",
                (end - start) / 1e6, total));
        appendCounts(line, main);
        line.append(" | wineserver: ");
        appendCounts(line, server);
        line.append(" | busiest WoW threads meanwhile: ").append(busiestThreads(start, end));
        CpuDebugLog.note(line.toString());
    }

    private static void appendCounts(StringBuilder line, Map<String, Integer> counts) {
        List<Map.Entry<String, Integer>> list = new ArrayList<>(counts.entrySet());
        list.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        for (int i = 0; i < Math.min(5, list.size()); i++) {
            if (i > 0) line.append(", ");
            line.append(list.get(i).getKey()).append(" x").append(list.get(i).getValue());
        }
    }

    @SuppressWarnings("unchecked")
    private static String busiestThreads(long start, long end) {
        Object[] before = null, after = null;
        for (Object[] snapshot : threadSnapshots) {
            long t = (long) snapshot[0];
            if (t <= start) before = snapshot;
            if (after == null && t >= end) after = snapshot;
        }
        if (after == null && !threadSnapshots.isEmpty()) after = threadSnapshots.get(threadSnapshots.size() - 1);
        if (before == null || after == null || before == after) return "no data";
        Map<Integer, Long> a = (Map<Integer, Long>) before[1];
        Map<Integer, Long> b = (Map<Integer, Long>) after[1];
        double seconds = ((long) after[0] - (long) before[0]) / 1e9;
        List<long[]> deltas = new ArrayList<>();
        for (Map.Entry<Integer, Long> e : b.entrySet()) {
            Long old = a.get(e.getKey());
            if (old != null) deltas.add(new long[]{e.getKey(), e.getValue() - old});
        }
        deltas.sort((x, y) -> Long.compare(y[1], x[1]));
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < Math.min(5, deltas.size()); i++) {
            long[] d = deltas.get(i);
            if (i > 0) out.append(", ");
            String name = read("/proc/" + wowPid + "/task/" + d[0] + "/comm").trim();
            out.append(String.format(Locale.US, "%s(%d) %.0f%%", name, d[0], d[1] / seconds));
        }
        return out.toString();
    }

    /** CPU ticks (utime + stime) of every thread of the process. */
    private static Map<Integer, Long> threadTicks(int pid) {
        Map<Integer, Long> ticks = new HashMap<>();
        File[] tasks = new File("/proc/" + pid + "/task").listFiles();
        if (tasks == null) return ticks;
        for (File task : tasks) {
            String stat = read(new File(task, "stat").getPath());
            int close = stat.lastIndexOf(')');
            if (close < 0) continue;
            String[] f = stat.substring(close + 2).split(" ");
            if (f.length < 13) continue;
            try {
                ticks.put(Integer.parseInt(task.getName()), Long.parseLong(f[11]) + Long.parseLong(f[12]));
            } catch (NumberFormatException ignored) {}
        }
        return ticks;
    }

    private static int findProcess(String prefix) {
        File[] dirs = new File("/proc").listFiles();
        if (dirs == null) return 0;
        for (File dir : dirs) {
            String name = dir.getName();
            if (name.isEmpty() || !Character.isDigit(name.charAt(0))) continue;
            String comm = read(dir.getPath() + "/comm").trim().toLowerCase(Locale.ROOT);
            if (prefix.equals("wow") ? comm.startsWith("wow") && !comm.startsWith("wowvoice") : comm.equals(prefix)) {
                try { return Integer.parseInt(name); } catch (NumberFormatException e) { return 0; }
            }
        }
        return 0;
    }

    private static String read(String path) {
        try (FileInputStream in = new FileInputStream(path)) {
            byte[] buffer = new byte[1024];
            int count = in.read(buffer);
            return count > 0 ? new String(buffer, 0, count) : "";
        } catch (Exception e) {
            return "";
        }
    }
}
