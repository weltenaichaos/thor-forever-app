package com.winlator.cmod.xserver;

import com.winlator.cmod.SettingsFragment;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Debug build only: writes what happens to top-level X windows (create, map, move,
 * resize, reparent, destroy, title) to Winlator/logs/x-windows_<time>.txt, so we can
 * see where a window that never appears on screen went. Lines are written by a
 * background thread so the X server never waits on the file.
 */
final class WindowDebugLog {
    private static final int MAX_LINES = 50000;
    private static final int MAX_DEPTH = 3;
    private static final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>();
    private static final long startTime = System.currentTimeMillis();
    private static int lineCount = 0;
    private static boolean started = false;

    private WindowDebugLog() {}

    /** How many levels below the root window this window is (root children are 1). */
    static int depth(Window window) {
        int depth = 0;
        for (Window parent = window.getParent(); parent != null; parent = parent.getParent()) depth++;
        return depth;
    }

    static void log(String event, Window window) {
        if (window == null) return;
        int depth = depth(window);
        if (depth == 0 || depth > MAX_DEPTH) return;
        Window parent = window.getParent();
        String name = window.getName();
        String className = window.getClassName();
        write(String.format(Locale.US, "%s win=0x%x parent=0x%x depth=%d geom=%dx%d+%d+%d mapped=%b override=%b class=\"%s\" name=\"%s\"",
                event, window.id, parent != null ? parent.id : 0, depth,
                window.getWidth(), window.getHeight(), window.getX(), window.getY(),
                window.attributes.isMapped(), window.attributes.isOverrideRedirect(),
                className, name));
    }

    private static synchronized void write(String line) {
        if (lineCount >= MAX_LINES) return;
        lineCount++;
        if (!started) {
            started = true;
            Thread thread = new Thread(WindowDebugLog::run, "XWindowDebugLog");
            thread.setDaemon(true);
            thread.start();
        }
        queue.offer(String.format(Locale.US, "%8.3f %s", (System.currentTimeMillis() - startTime) / 1000.0, line));
    }

    private static void run() {
        File dir = new File(SettingsFragment.DEFAULT_WINLATOR_PATH, "logs");
        dir.mkdirs();
        String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date(startTime));
        try (PrintWriter writer = new PrintWriter(new FileWriter(new File(dir, "x-windows_" + stamp + ".txt")))) {
            while (true) {
                writer.println(queue.take());
                if (queue.isEmpty()) writer.flush();
            }
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }
}
