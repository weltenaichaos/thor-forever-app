package com.winlator.cmod.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Debug build only: when Wine's server traces its requests (WINEDEBUG=+server), counts them
 * per request type and per Windows thread instead of writing every line to the log, and
 * the CPU log prints the busiest ones every few seconds. Shows what keeps the server busy.
 */
public final class ServerRequestStats {
    /** "00e0: get_message( flags=... )" is a request; "00e0: get_message() = 0 {...}" its reply. */
    private static final Pattern LINE = Pattern.compile("^([0-9a-f]{4}): (\\*?[a-z_]+\\*?)\\((\\))?( =)?");

    private static volatile boolean enabled;
    private static final Map<String, long[]> byRequest = new HashMap<>();
    private static final Map<String, long[]> byThread = new HashMap<>();
    private static final Map<String, long[]> byPair = new HashMap<>();
    private static long errors;

    private ServerRequestStats() {}

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    /** Counts a server trace line and returns true so it is left out of the log file. */
    public static boolean consume(String line) {
        if (!enabled || line.length() < 7 || line.charAt(4) != ':') return false;
        Matcher m = LINE.matcher(line);
        if (!m.find()) return false;
        if (m.group(4) != null) {
            // A reply: count the ones that failed, they are often retried in a loop.
            if (!line.contains("= 0") ) {
                synchronized (byRequest) { errors++; }
            }
            return true;
        }
        String thread = m.group(1);
        String request = m.group(2);
        synchronized (byRequest) {
            byRequest.computeIfAbsent(request, k -> new long[1])[0]++;
            byThread.computeIfAbsent(thread, k -> new long[1])[0]++;
            byPair.computeIfAbsent(thread + " " + request, k -> new long[1])[0]++;
        }
        return true;
    }

    /** Lines for the CPU log, and resets the counts. Empty when tracing is off. */
    public static List<String> drain(double seconds) {
        List<String> lines = new ArrayList<>();
        if (!enabled) return lines;
        Map<String, long[]> requests, threads, pairs;
        long failed;
        synchronized (byRequest) {
            requests = new HashMap<>(byRequest);
            threads = new HashMap<>(byThread);
            pairs = new HashMap<>(byPair);
            failed = errors;
            byRequest.clear();
            byThread.clear();
            byPair.clear();
            errors = 0;
        }
        long total = 0;
        for (long[] v : requests.values()) total += v[0];
        lines.add(String.format(Locale.US, "wineserver requests: %.0f/s, failed replies %.0f/s",
                total / seconds, failed / seconds));
        lines.add("  by type: " + top(requests, seconds, 12));
        lines.add("  by thread: " + top(threads, seconds, 8));
        lines.add("  by thread and type: " + top(pairs, seconds, 12));
        return lines;
    }

    private static String top(Map<String, long[]> counts, double seconds, int max) {
        List<Map.Entry<String, long[]>> list = new ArrayList<>(counts.entrySet());
        list.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < Math.min(max, list.size()); i++) {
            if (i > 0) out.append(", ");
            out.append(list.get(i).getKey()).append(' ')
                    .append(String.format(Locale.US, "%.0f/s", list.get(i).getValue()[0] / seconds));
        }
        return out.toString();
    }
}
