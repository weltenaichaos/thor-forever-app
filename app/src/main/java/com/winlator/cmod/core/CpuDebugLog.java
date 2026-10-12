package com.winlator.cmod.core;

import com.winlator.cmod.SettingsFragment;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Debug build only: every few seconds writes which threads use the most CPU, on which core
 * they last ran, the core speeds, and (once per process) the cpuset and allowed cores of
 * every process we can see, to Winlator/logs/cpu_<time>.txt. Only reads /proc and /sys.
 */
public final class CpuDebugLog {
    private static final long INTERVAL_MS = 5000;
    private static final int TOP_THREADS = 15;
    private static final int MAX_SAMPLES = 2000;
    private static final double TICKS_PER_SECOND = 100.0;

    private final Map<Integer, Long> lastTicks = new HashMap<>();
    private final Set<Integer> describedPids = new HashSet<>();
    private volatile boolean running;
    private Thread thread;

    public synchronized void start() {
        if (thread != null) return;
        running = true;
        thread = new Thread(this::run, "CpuDebugLog");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        if (thread != null) thread.interrupt();
        thread = null;
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
            for (File task : tasks) {
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
        for (int i = 0; i < Math.min(TOP_THREADS, samples.size()); i++) {
            Sample s = samples.get(i);
            if (s.delta == 0) break;
            writer.println(String.format(Locale.US, "           %5.1f%% cpu%d nice=%d %s(%d) / %s(%d)",
                    s.delta * scale, s.cpu, s.nice, s.process, s.pid, s.thread, s.tid));
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
