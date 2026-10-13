package com.winlator.cmod.core;

import com.winlator.cmod.SettingsFragment;
import com.winlator.cmod.xserver.XRequestStats;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Debug build only: every few seconds writes which threads use the most CPU, on which core
 * they last ran, the core speeds, and (once per process) the cpuset and allowed cores of
 * every process we can see, to Winlator/logs/cpu_<time>.txt. It also counts the X requests
 * the X server handles, and for this app's own busiest threads samples their Java stacks
 * (20 times a second) to show which code they are in. Only reads, changes nothing.
 */
public final class CpuDebugLog {
    private static final long INTERVAL_MS = 5000;
    private static final int TOP_THREADS = 15;
    private static final int MAX_SAMPLES = 2000;
    private static final double TICKS_PER_SECOND = 100.0;

    private static final List<String> notes = new ArrayList<>();
    private static final long START = System.currentTimeMillis();

    /** Records something the app did (like changing a program's cores), with the time, for the next log line. */
    public static void note(String text) {
        synchronized (notes) {
            if (notes.size() < 200) notes.add(String.format(Locale.US, "%s %s",
                    new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date()), text));
        }
    }

    private final Map<Integer, Long> lastTicks = new HashMap<>();
    private final Set<Integer> describedPids = new HashSet<>();
    private final Map<String, Integer> stackCounts = new HashMap<>();
    private volatile Set<String> hotThreadNames = new HashSet<>();
    private volatile boolean running;
    private Thread thread;
    private Thread stackThread;

    public synchronized void start() {
        if (thread != null) return;
        running = true;
        thread = new Thread(this::run, "CpuDebugLog");
        thread.setDaemon(true);
        thread.start();
        stackThread = new Thread(this::sampleStacks, "CpuDebugStacks");
        stackThread.setDaemon(true);
        stackThread.start();
    }

    public synchronized void stop() {
        running = false;
        if (thread != null) thread.interrupt();
        if (stackThread != null) stackThread.interrupt();
        thread = null;
        stackThread = null;
    }

    private static final class Sample {
        String process;
        String thread;
        int pid;
        int tid;
        long delta;
        int cpu;
        int nice;
    }

    private void run() {
        File dir = new File(SettingsFragment.DEFAULT_WINLATOR_PATH, "logs");
        dir.mkdirs();
        long startTime = System.currentTimeMillis();
        String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date(startTime));
        try (PrintWriter writer = new PrintWriter(new FileWriter(new File(dir, "cpu_" + stamp + ".txt")))) {
            writer.println("app cpuset=" + read("/proc/self/cpuset").trim() + " online=" + read("/sys/devices/system/cpu/online").trim());
            for (int i = 0; i < MAX_SAMPLES && running; i++) {
                sample(writer, (System.currentTimeMillis() - startTime) / 1000.0, i > 0);
                writer.flush();
                Thread.sleep(INTERVAL_MS);
            }
        }
        catch (InterruptedException ignored) {}
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void sample(PrintWriter writer, double seconds, boolean report) {
        ArrayList<Sample> samples = new ArrayList<>();
        List<String> affinityLines = new ArrayList<>();
        Map<Integer, Long> ticks = new HashMap<>();
        File[] procs = new File("/proc").listFiles();
        if (procs == null) return;
        for (File proc : procs) {
            String name = proc.getName();
            if (name.isEmpty() || !Character.isDigit(name.charAt(0))) continue;
            int pid;
            try { pid = Integer.parseInt(name); } catch (NumberFormatException e) { continue; }
            File[] tasks = new File(proc, "task").listFiles();
            if (tasks == null) continue;
            String processName = read(new File(proc, "comm")).trim();
            if (!describedPids.contains(pid)) {
                describedPids.add(pid);
                writer.println(String.format(Locale.US, "%8.1f process pid=%d name=%s cpuset=%s allowed=%s cgroup=%s",
                        seconds, pid, processName, read(new File(proc, "cpuset")).trim(),
                        statusField(read(new File(proc, "status")), "Cpus_allowed_list:"),
                        read(new File(proc, "cgroup")).trim().replace('\n', ' ')));
            }
            Map<String, Integer> allowedCounts = null;
            if (report && processName.toLowerCase(Locale.ROOT).startsWith("wow")) allowedCounts = new HashMap<>();
            for (File task : tasks) {
                if (allowedCounts != null) {
                    allowedCounts.merge(statusField(read(new File(task, "status")), "Cpus_allowed_list:"), 1, Integer::sum);
                }
                String stat = read(new File(task, "stat"));
                int close = stat.lastIndexOf(')');
                int open = stat.indexOf('(');
                if (close < 0 || open < 0) continue;
                String[] f = stat.substring(close + 2).split(" ");
                if (f.length < 37) continue;
                try {
                    Sample s = new Sample();
                    s.process = processName;
                    s.thread = stat.substring(open + 1, close);
                    s.pid = pid;
                    s.tid = Integer.parseInt(task.getName());
                    long total = Long.parseLong(f[11]) + Long.parseLong(f[12]);
                    s.nice = Integer.parseInt(f[16]);
                    s.cpu = Integer.parseInt(f[36]);
                    ticks.put(s.tid, total);
                    Long last = lastTicks.get(s.tid);
                    s.delta = last != null ? total - last : 0;
                    samples.add(s);
                } catch (NumberFormatException ignored) {}
            }
            if (allowedCounts != null) {
                StringBuilder line = new StringBuilder("allowed cores ").append(processName).append('(').append(pid).append("):");
                for (Map.Entry<String, Integer> e : allowedCounts.entrySet()) {
                    line.append(' ').append(e.getKey()).append(" x").append(e.getValue());
                }
                affinityLines.add(line.toString());
            }
        }
        lastTicks.clear();
        lastTicks.putAll(ticks);
        if (!report) return;

        StringBuilder freqs = new StringBuilder();
        for (int cpu = 0; cpu < 16; cpu++) {
            String khz = read("/sys/devices/system/cpu/cpu" + cpu + "/cpufreq/scaling_cur_freq").trim();
            if (khz.isEmpty()) continue;
            String max = read("/sys/devices/system/cpu/cpu" + cpu + "/cpufreq/scaling_max_freq").trim();
            try {
                freqs.append(" cpu").append(cpu).append('=').append(Long.parseLong(khz) / 1000)
                        .append('/').append(max.isEmpty() ? "?" : String.valueOf(Long.parseLong(max) / 1000));
            } catch (NumberFormatException ignored) {}
        }
        long busy = 0;
        for (Sample s : samples) busy += s.delta;
        double scale = 100.0 / (TICKS_PER_SECOND * INTERVAL_MS / 1000.0);
        writer.println(String.format(Locale.US, "%8.1f total=%.0f%% MHz(now/max):%s", seconds, busy * scale, freqs));
        samples.sort((a, b) -> Long.compare(b.delta, a.delta));
        int myPid = android.os.Process.myPid();
        Set<String> hot = new HashSet<>();
        for (int i = 0; i < Math.min(TOP_THREADS, samples.size()); i++) {
            Sample s = samples.get(i);
            if (s.delta == 0) break;
            writer.println(String.format(Locale.US, "           %5.1f%% cpu%d nice=%d %s(%d) / %s(%d)",
                    s.delta * scale, s.cpu, s.nice, s.process, s.pid, s.thread, s.tid));
            if (s.pid == myPid && s.delta * scale >= 10) hot.add(s.thread);
        }
        hotThreadNames = hot;

        for (String line : affinityLines) writer.println("           " + line);
        synchronized (notes) {
            for (String note : notes) writer.println("           app: " + note);
            notes.clear();
        }
        for (String line : XRequestStats.drain(INTERVAL_MS / 1000.0, 12)) writer.println("           " + line);
        writer.println("           " + com.winlator.cmod.xserver.FramePacingStats.drain());

        List<Map.Entry<String, Integer>> stacks;
        int stackTotal = 0;
        synchronized (stackCounts) {
            stacks = new ArrayList<>(stackCounts.entrySet());
            stackCounts.clear();
        }
        for (Map.Entry<String, Integer> e : stacks) stackTotal += e.getValue();
        stacks.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        for (int i = 0; i < Math.min(8, stacks.size()); i++) {
            writer.println(String.format(Locale.US, "           stack %3.0f%% %s",
                    100.0 * stacks.get(i).getValue() / stackTotal, stacks.get(i).getKey()));
        }
    }

    /** Samples where this app's busiest threads are in their Java code (Linux names are cut to 15 characters). */
    private void sampleStacks() {
        Map<String, Thread> byName = new HashMap<>();
        long lastLookup = 0;
        while (running) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                return;
            }
            Set<String> hot = hotThreadNames;
            if (hot.isEmpty()) continue;
            long now = System.currentTimeMillis();
            if (now - lastLookup > INTERVAL_MS) {
                lastLookup = now;
                byName.clear();
                for (Thread t : Thread.getAllStackTraces().keySet()) {
                    String name = t.getName();
                    byName.put(name.length() > 15 ? name.substring(0, 15) : name, t);
                }
            }
            for (String name : hot) {
                Thread t = byName.get(name);
                if (t == null) continue;
                StackTraceElement[] trace = t.getStackTrace();
                StringBuilder key = new StringBuilder(name).append(':');
                for (int i = 0; i < Math.min(6, trace.length); i++) {
                    StackTraceElement f = trace[i];
                    String cls = f.getClassName();
                    key.append(' ').append(cls.substring(cls.lastIndexOf('.') + 1)).append('.').append(f.getMethodName())
                            .append(':').append(f.getLineNumber());
                }
                synchronized (stackCounts) {
                    stackCounts.merge(key.toString(), 1, Integer::sum);
                }
            }
        }
    }

    private static String statusField(String status, String key) {
        for (String line : status.split("\n")) {
            if (line.startsWith(key)) return line.substring(key.length()).trim();
        }
        return "";
    }

    private static String read(String path) {
        return read(new File(path));
    }

    private static String read(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[4096];
            int count = in.read(buffer);
            return count > 0 ? new String(buffer, 0, count) : "";
        } catch (Exception e) {
            return "";
        }
    }
}
