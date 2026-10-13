package com.winlator.cmod.xserver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Debug build only: counts the X requests the server handles, per client and request type,
 * with their size and the time spent on them, so the CPU log can show what keeps the X
 * server busy. Clients are named by their resource id base (0x800000 is the first window
 * id they create, which the x-windows log ties to a program).
 */
public final class XRequestStats {
    private static final class Entry {
        long count;
        long bytes;
        long nanos;
    }

    private static final Map<String, Entry> entries = new HashMap<>();
    private static long totalNanos;

    private XRequestStats() {}

    private static int lastPutImageTarget;

    /** Remembers which drawable the current PutImage goes to, for the log. */
    public static void setPutImageTarget(int drawableId) {
        lastPutImageTarget = drawableId;
    }

    static void record(int clientBase, byte opcode, byte minor, int length, long nanos, boolean skipped) {
        String name = name(opcode, minor);
        if (opcode == ClientOpcodes.PUT_IMAGE) name += String.format(Locale.US, " to 0x%x", lastPutImageTarget);
        if (skipped) name += " (hidden, skipped)";
        String key = String.format(Locale.US, "client=0x%x %s", clientBase, name);
        synchronized (entries) {
            Entry entry = entries.get(key);
            if (entry == null) entries.put(key, entry = new Entry());
            entry.count++;
            entry.bytes += length;
            entry.nanos += nanos;
            totalNanos += nanos;
        }
    }

    /** The busiest request types since the last call, one line each, and resets the counts. */
    public static List<String> drain(double seconds, int max) {
        List<Map.Entry<String, Entry>> list;
        long total;
        synchronized (entries) {
            list = new ArrayList<>(entries.entrySet());
            total = totalNanos;
            entries.clear();
            totalNanos = 0;
        }
        list.sort((a, b) -> Long.compare(b.getValue().nanos, a.getValue().nanos));
        List<String> lines = new ArrayList<>();
        lines.add(String.format(Locale.US, "X requests: %.0f ms busy per second", total / 1e6 / seconds));
        for (int i = 0; i < Math.min(max, list.size()); i++) {
            Entry e = list.get(i).getValue();
            lines.add(String.format(Locale.US, "  %-52s %7.1f/s %8.1f KB/s %6.1f ms/s",
                    list.get(i).getKey(), e.count / seconds, e.bytes / 1024.0 / seconds, e.nanos / 1e6 / seconds));
        }
        return lines;
    }

    private static String name(byte opcode, byte minor) {
        if (opcode < 0) return "ext" + (opcode & 0xff) + "/" + (minor & 0xff);
        switch (opcode) {
            case ClientOpcodes.PUT_IMAGE: return "PutImage";
            case ClientOpcodes.GET_IMAGE: return "GetImage";
            case ClientOpcodes.COPY_AREA: return "CopyArea";
            case ClientOpcodes.POLY_FILL_RECTANGLE: return "PolyFillRectangle";
            case ClientOpcodes.CHANGE_PROPERTY: return "ChangeProperty";
            case ClientOpcodes.GET_PROPERTY: return "GetProperty";
            case ClientOpcodes.CONFIGURE_WINDOW: return "ConfigureWindow";
            case ClientOpcodes.QUERY_POINTER: return "QueryPointer";
            case ClientOpcodes.GET_GEOMETRY: return "GetGeometry";
            case ClientOpcodes.TRANSLATE_COORDINATES: return "TranslateCoordinates";
            case ClientOpcodes.GET_INPUT_FOCUS: return "GetInputFocus";
            default: return "op" + opcode;
        }
    }
}
