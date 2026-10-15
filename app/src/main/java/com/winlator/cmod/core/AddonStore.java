package com.winlator.cmod.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The add-on store: a short list of add-ons (assets/thor/addon-store.txt) installed from their
 * latest GitHub release. The release.json those releases carry says which game versions each
 * .zip is for, so only a file made for the installed game is offered. No account or key is
 * needed: the files come from github.com/OWNER/REPO/releases/latest/download/.
 */
public final class AddonStore {
    public static final String CATALOG_ASSET = "thor/addon-store.txt";
    private static final String DOWNLOAD_URL = "https://github.com/%s/releases/latest/download/%s";

    private AddonStore() {}

    /** One add-on of the store's list. */
    public static final class Entry {
        public final String name;
        public final String repo;
        /** Its main folder in AddOns, to see whether it is installed. */
        public final String folder;
        public final String description;

        Entry(String name, String repo, String folder, String description) {
            this.name = name;
            this.repo = repo;
            this.folder = folder;
            this.description = description;
        }
    }

    /** The file of an add-on's latest release that is made for the installed game. */
    public static final class Offer {
        public final String filename;
        public final String version;

        Offer(String filename, String version) {
            this.filename = filename;
            this.version = version;
        }
    }

    /** The store's list: lines of Name|owner/repo|folder|description; # starts a comment. */
    public static List<Entry> parseCatalog(String text) {
        List<Entry> entries = new ArrayList<>();
        for (String line : text.split("\r?\n")) {
            if (line.trim().isEmpty() || line.startsWith("#")) continue;
            String[] f = line.split("\\|", -1);
            if (f.length < 4 || !f[1].matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) continue;
            entries.add(new Entry(f[0].trim(), f[1].trim(), f[2].trim(), f[3].trim()));
        }
        return entries;
    }

    /** The interface number add-ons use for a game version: 1.60.1.70374 is 16001. */
    public static int interfaceOf(String gameVersion) {
        if (gameVersion == null) return 0;
        String[] p = gameVersion.trim().split("\\.");
        if (p.length < 3) return 0;
        try {
            return Integer.parseInt(p[0]) * 10000 + Integer.parseInt(p[1]) * 100 + Integer.parseInt(p[2]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * The file in a release.json made for the game's interface number: one listing it exactly,
     * else one for the same major version (16001 for 16002), so a small game patch doesn't hide
     * every add-on. Null when none is.
     */
    @SuppressWarnings("unchecked")
    static Offer pickFile(String releaseJson, int gameInterface) {
        if (gameInterface <= 0) return null;
        Object root;
        try {
            root = new MiniJson(releaseJson).parse();
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (!(root instanceof Map)) return null;
        Object releases = ((Map<String, Object>) root).get("releases");
        if (!(releases instanceof List)) return null;
        Offer close = null;
        for (Object item : (List<Object>) releases) {
            if (!(item instanceof Map)) continue;
            Map<String, Object> release = (Map<String, Object>) item;
            if (Boolean.TRUE.equals(release.get("nolib"))) continue;
            Object filename = release.get("filename");
            Object metadata = release.get("metadata");
            if (!(filename instanceof String) || !(metadata instanceof List)) continue;
            String name = (String) filename;
            // Only a plain file name: it becomes part of the download link.
            if (!name.matches("[A-Za-z0-9_.+ -]+\\.zip")) continue;
            Object v = release.get("version");
            Offer offer = new Offer(name, v instanceof String ? (String) v : name.substring(0, name.length() - 4));
            for (Object m : (List<Object>) metadata) {
                if (!(m instanceof Map)) continue;
                Object i = ((Map<String, Object>) m).get("interface");
                if (!(i instanceof Number)) continue;
                int iface = ((Number) i).intValue();
                if (iface == gameInterface) return offer;
                if (close == null && iface / 100 == gameInterface / 100) close = offer;
            }
        }
        return close;
    }

    /** Asks GitHub for the add-on's latest release.json; null when nothing fits the game. */
    public static Offer findOffer(Entry entry, int gameInterface) throws IOException {
        URL url = new URL(String.format(DOWNLOAD_URL, entry.repo, "release.json"));
        HttpURLConnection connection = open(url);
        try {
            int code = connection.getResponseCode();
            // No release, or a release without release.json: nothing to offer.
            if (code == HttpURLConnection.HTTP_NOT_FOUND) return null;
            if (code != HttpURLConnection.HTTP_OK) throw new IOException("GitHub answered " + code);
            try (InputStream in = connection.getInputStream()) {
                return pickFile(readAll(in, 1 << 20), gameInterface);
            }
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Downloads the offered .zip into cacheDir and installs it into addonsDir. The download is
     * removed afterwards. Returns the installed folders.
     */
    public static List<String> install(Entry entry, Offer offer, File addonsDir, File cacheDir, ThorSetup.Progress progress) throws IOException {
        File zip = new File(cacheDir, "addon-store.zip");
        try {
            URL url = new URL(String.format(DOWNLOAD_URL, entry.repo, offer.filename.replace(" ", "%20")));
            HttpURLConnection connection = open(url);
            try {
                int code = connection.getResponseCode();
                if (code != HttpURLConnection.HTTP_OK) throw new IOException("GitHub answered " + code);
                long total = connection.getContentLengthLong();
                try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(zip)) {
                    byte[] buffer = new byte[65536];
                    long done = 0;
                    int last = -2;
                    for (int n; (n = in.read(buffer)) > 0; ) {
                        out.write(buffer, 0, n);
                        done += n;
                        int percent = total > 0 ? (int) (done * 100 / total) : -1;
                        if (percent != last) {
                            progress.onProgress(percent);
                            last = percent;
                        }
                    }
                }
            } finally {
                connection.disconnect();
            }
            return AddonInstaller.install(zip, addonsDir);
        } finally {
            zip.delete();
        }
    }

    private static HttpURLConnection open(URL url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        // GitHub sends the file from another host (objects.githubusercontent.com).
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        return connection;
    }

    private static String readAll(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int n; (n = in.read(buffer)) > 0; ) {
            out.write(buffer, 0, n);
            if (out.size() > limit) throw new IOException("release.json is too large");
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }

    /** Just enough JSON for release.json (org.json is a stub in unit tests). */
    static final class MiniJson {
        private final String s;
        private int i;

        MiniJson(String s) {
            this.s = s;
        }

        Object parse() {
            Object value = value();
            space();
            if (i != s.length()) throw error();
            return value;
        }

        private Object value() {
            space();
            if (i >= s.length()) throw error();
            char c = s.charAt(i);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            return number();
        }

        private Map<String, Object> object() {
            Map<String, Object> map = new LinkedHashMap<>();
            i++;
            space();
            if (peek('}')) { i++; return map; }
            while (true) {
                space();
                if (!peek('"')) throw error();
                String key = string();
                space();
                expect(':');
                map.put(key, value());
                space();
                if (peek(',')) { i++; continue; }
                expect('}');
                return map;
            }
        }

        private List<Object> array() {
            List<Object> list = new ArrayList<>();
            i++;
            space();
            if (peek(']')) { i++; return list; }
            while (true) {
                list.add(value());
                space();
                if (peek(',')) { i++; continue; }
                expect(']');
                return list;
            }
        }

        private String string() {
            StringBuilder out = new StringBuilder();
            i++;
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return out.toString();
                if (c != '\\') { out.append(c); continue; }
                if (i >= s.length()) break;
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': out.append('\n'); break;
                    case 't': out.append('\t'); break;
                    case 'r': out.append('\r'); break;
                    case 'b': out.append('\b'); break;
                    case 'f': out.append('\f'); break;
                    case 'u':
                        if (i + 4 > s.length()) throw error();
                        try {
                            out.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        } catch (NumberFormatException ex) {
                            throw error();
                        }
                        i += 4;
                        break;
                    default: out.append(e);
                }
            }
            throw error();
        }

        private Number number() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (start == i) throw error();
            try {
                return Double.parseDouble(s.substring(start, i));
            } catch (NumberFormatException e) {
                throw error();
            }
        }

        private void space() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        private boolean peek(char c) {
            return i < s.length() && s.charAt(i) == c;
        }

        private void expect(char c) {
            if (!peek(c)) throw error();
            i++;
        }

        private IllegalArgumentException error() {
            return new IllegalArgumentException("Bad JSON at " + i);
        }
    }
}
