package com.winlator.cmod.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Asks Blizzard's public version server which game version Battle.net would install now, so
 * the start screen can say when an update is waiting. Only reads; the update itself stays
 * Battle.net's job.
 */
public final class GameUpdateCheck {
    /** The same list Battle.net reads, like us|...|70374|1.60.1.70374|... per region. */
    static final String VERSIONS_URL = "https://us.version.battle.net/v2/products/%s/versions";

    private GameUpdateCheck() {}

    /** The version Blizzard offers for product in region, or "" when there is none. */
    public static String latestVersion(String product, String region) throws IOException {
        if (product == null || !product.matches("[a-z0-9_]+")) return "";
        HttpURLConnection connection = (HttpURLConnection) new URL(String.format(VERSIONS_URL, product)).openConnection();
        try {
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) throw new IOException("Blizzard's server answered " + code);
            try (InputStream in = connection.getInputStream()) {
                return versionFromVersions(readAll(in), region);
            }
        } finally {
            connection.disconnect();
        }
    }

    /** The VersionsName of the region's row (the first row when the region isn't listed). */
    static String versionFromVersions(String versions, String region) {
        String[] lines = versions.split("\r?\n");
        int header = -1;
        for (int i = 0; i < lines.length; i++)
            if (lines[i].startsWith("Region!")) { header = i; break; }
        if (header < 0) return "";
        int regionCol = column(lines[header], "Region");
        int versionCol = column(lines[header], "VersionsName");
        if (versionCol < 0) return "";
        String first = "";
        for (int i = header + 1; i < lines.length; i++) {
            String line = lines[i];
            // "## seqn = ..." and blank lines are not rows.
            if (line.trim().isEmpty() || line.startsWith("#")) continue;
            String version = field(line, versionCol);
            if (version.isEmpty()) continue;
            if (region != null && !region.isEmpty() && region.equalsIgnoreCase(field(line, regionCol))) return version;
            if (first.isEmpty()) first = version;
        }
        return first;
    }

    /**
     * Whether Battle.net would update the game: the offered version differs from the installed
     * one. Battle.net moves to the offered version either way, so any difference counts.
     */
    public static boolean updateAvailable(String installed, String latest) {
        return installed != null && latest != null && !installed.isEmpty() && !latest.isEmpty()
                && !installed.trim().equals(latest.trim());
    }

    private static int column(String header, String name) {
        String[] fields = header.split("\\|", -1);
        for (int i = 0; i < fields.length; i++) {
            int bang = fields[i].indexOf('!');
            if ((bang >= 0 ? fields[i].substring(0, bang) : fields[i]).equals(name)) return i;
        }
        return -1;
    }

    private static String field(String line, int index) {
        String[] fields = line.split("\\|", -1);
        return index >= 0 && index < fields.length ? fields[index].trim() : "";
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        // The list is a few hundred bytes; stop early on anything unexpectedly large.
        for (int n; (n = in.read(buffer)) > 0 && out.size() < 65536; ) out.write(buffer, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
