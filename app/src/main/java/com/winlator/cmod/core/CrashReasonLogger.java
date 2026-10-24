package com.winlator.cmod.core;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import androidx.annotation.RequiresApi;
import androidx.preference.PreferenceManager;

import com.winlator.cmod.SettingsFragment;

import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Writes why the app was closed to Winlator/logs/app-exits.txt, so a crash or an
 * Android kill (for example low memory) can be read afterwards without a computer.
 * Java crashes are written as they happen; everything else (native crashes, kills)
 * is read from Android's exit records the next time the app starts.
 */
public final class CrashReasonLogger {
    private static final String TAG = "CrashReasonLogger";
    private static final String PREF_LAST_EXIT = "last_logged_exit_timestamp";

    private CrashReasonLogger() {}

    public static void install(Context context) {
        final Context app = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, e) -> {
            StringWriter trace = new StringWriter();
            e.printStackTrace(new PrintWriter(trace));
            append("Java crash in thread \"" + thread.getName() + "\"\n" + trace);
            if (previous != null) previous.uncaughtException(thread, e);
        });
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) logPreviousExits(app);
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private static void logPreviousExits(Context context) {
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            long lastLogged = prefs.getLong(PREF_LAST_EXIT, 0);
            List<ApplicationExitInfo> exits = am.getHistoricalProcessExitReasons(null, 0, 10);
            long newest = lastLogged;
            // The list is newest first; write oldest first so the file reads in order.
            for (int i = exits.size() - 1; i >= 0; i--) {
                ApplicationExitInfo info = exits.get(i);
                if (info.getTimestamp() <= lastLogged) continue;
                newest = Math.max(newest, info.getTimestamp());
                if (info.getReason() == ApplicationExitInfo.REASON_EXIT_SELF
                        || info.getReason() == ApplicationExitInfo.REASON_USER_REQUESTED) continue;
                StringBuilder sb = new StringBuilder();
                sb.append("Process ").append(info.getProcessName())
                  .append(" ended at ").append(format(info.getTimestamp()))
                  .append("\n  reason: ").append(reasonName(info.getReason()))
                  .append("\n  status: ").append(info.getStatus())
                  .append("\n  importance: ").append(info.getImportance())
                  .append("\n  memory: pss ").append(info.getPss() / 1024).append(" MB, rss ")
                  .append(info.getRss() / 1024).append(" MB")
                  .append("\n  description: ").append(info.getDescription()).append('\n');
                if (info.getReason() == ApplicationExitInfo.REASON_ANR) {
                    try (InputStream in = info.getTraceInputStream()) {
                        if (in != null) sb.append(new String(readAll(in), StandardCharsets.UTF_8));
                    }
                }
                append(sb.toString());
            }
            if (newest != lastLogged) prefs.edit().putLong(PREF_LAST_EXIT, newest).apply();
        } catch (Exception e) {
            Log.w(TAG, "Could not read exit reasons", e);
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private static String reasonName(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_ANR: return "ANR (app not responding)";
            case ApplicationExitInfo.REASON_CRASH: return "CRASH (Java)";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "CRASH_NATIVE";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "EXCESSIVE_RESOURCE_USAGE";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "INITIALIZATION_FAILURE";
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "LOW_MEMORY";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE: return "PERMISSION_CHANGE";
            case ApplicationExitInfo.REASON_SIGNALED: return "SIGNALED";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED: return "DEPENDENCY_DIED";
            case ApplicationExitInfo.REASON_OTHER: return "OTHER";
            default: return "code " + reason;
        }
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0 && out.size() < 512 * 1024) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static String format(long time) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(time));
    }

    /** Adds [text] to app-exits.txt (an error the app survived, for example). */
    public static void note(String text) {
        append(text);
    }

    private static synchronized void append(String text) {
        try {
            File dir = new File(SettingsFragment.DEFAULT_WINLATOR_PATH, "logs");
            if (!dir.exists()) dir.mkdirs();
            try (FileWriter w = new FileWriter(new File(dir, "app-exits.txt"), true)) {
                w.write("=== " + format(System.currentTimeMillis()) + " ===\n" + text + "\n");
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not write app-exits.txt", e);
        }
    }
}
