package com.winlator.cmod.core;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * WoWInterface's add-ons for the store, through the public catalog other add-on managers use.
 * The candidates are the add-ons that list the game's version (like 1.15 for Classic Era), and
 * for WoW Forever, which the site has no tag for yet, also those that say "Forever" in their name
 * or version. A search narrows them by name. Each one is downloaded and kept only when its own
 * .toc is made for the game: the store never shows an add-on that isn't.
 */
public final class WowInterfaceStore {
    static final String LIST_URL = "https://api.mmoui.com/v4/game/WOW/filelist.json";
    static final String DETAILS_URL = "https://api.mmoui.com/v4/game/WOW/filedetails/%d.json";
    /** At most this many candidates are downloaded to check them. */
    static final int MAX_CANDIDATES = 30;
    /** With a search, fewer: each search downloads its own candidates. */
    static final int MAX_SEARCH_CANDIDATES = 15;
    /** The catalog is a few MB: it is downloaded again only after this long. */
    static final long LIST_MAX_AGE_MS = 30 * 60 * 1000L;

    private static String cachedList;
    private static long cachedListAt;

    private WowInterfaceStore() {}

    /** A WoWInterface add-on. folders is set once it has been checked. */
    public static final class Item {
        public final int id;
        public final String title;
        public final String version;
        public final String author;
        final long downloads;
        public List<String> folders = Collections.emptyList();

        Item copy() {
            return new Item(id, title, version, author, downloads);
        }

        Item(int id, String title, String version, String author, long downloads) {
            this.id = id;
            this.title = title;
            this.version = version;
            this.author = author;
            this.downloads = downloads;
        }
    }

    /** The candidates to check, and how many add-ons of the catalog matched before the limit. */
    public static final class Found {
        public final List<Item> items;
        public final int total;

        Found(List<Item> items, int total) {
            this.items = items;
            this.total = total;
        }
    }

    /** Like candidates(listJson, gameVersion, skipTitles, "", MAX_CANDIDATES), only the list. */
    static List<Item> candidates(String listJson, String gameVersion, Set<String> skipTitles) {
        return candidates(listJson, gameVersion, skipTitles, "", MAX_CANDIDATES).items;
    }

    /**
     * The add-ons of the catalog that may be made for the game: listing its version (gameVersion
     * 1.60.1 matches 1.60 and 1.60.x) or, for WoW Forever (1.60), saying "Forever". Only those
     * whose title or author match query (see matches). Most downloaded first, at most limit;
     * titles in skipTitles (lower case, the store's own list) are left out.
     */
    @SuppressWarnings("unchecked")
    static Found candidates(String listJson, String gameVersion, Set<String> skipTitles, String query, int limit) {
        List<Item> items = new ArrayList<>();
        String[] p = gameVersion == null ? new String[0] : gameVersion.split("\\.");
        if (p.length < 2) return new Found(items, 0);
        String majorMinor = p[0] + "." + p[1];
        boolean forever = majorMinor.equals("1.60");
        Object root;
        try {
            root = new AddonStore.MiniJson(listJson).parse();
        } catch (IllegalArgumentException e) {
            return new Found(items, 0);
        }
        if (!(root instanceof List)) return new Found(items, 0);
        for (Object o : (List<Object>) root) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> m = (Map<String, Object>) o;
            String title = text(m.get("title"));
            String version = text(m.get("version"));
            String author = text(m.get("author"));
            if (!(m.get("id") instanceof Number) || title.isEmpty()) continue;
            if (skipTitles.contains(title.toLowerCase(Locale.ROOT))) continue;
            boolean listed = false;
            if (m.get("gameVersions") instanceof List)
                for (Object v : (List<Object>) m.get("gameVersions")) {
                    String gv = text(v);
                    if (gv.equals(majorMinor) || gv.startsWith(majorMinor + ".")) listed = true;
                }
            boolean saysForever = forever && (title + " " + version).toLowerCase(Locale.ROOT).contains("forever");
            if (!listed && !saysForever) continue;
            if (!matches(title + " " + author, query)) continue;
            long downloads = m.get("downloads") instanceof Number ? ((Number) m.get("downloads")).longValue() : 0;
            items.add(new Item(((Number) m.get("id")).intValue(), title, version, author, downloads));
        }
        Collections.sort(items, (a, b) -> Long.compare(b.downloads, a.downloads));
        int total = items.size();
        return new Found(items.size() > limit ? new ArrayList<>(items.subList(0, limit)) : items, total);
    }

    /**
     * Whether text matches a search: every word of query is in it (any case). A word may list
     * alternatives with | (console|gamepad matches either). An empty query matches everything.
     */
    public static boolean matches(String text, String query) {
        if (query == null || query.trim().isEmpty()) return true;
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
        for (String word : query.toLowerCase(Locale.ROOT).trim().split("\\s+")) {
            boolean any = false;
            for (String alt : word.split("\\|"))
                if (!alt.isEmpty() && t.contains(alt)) { any = true; break; }
            if (!any) return false;
        }
        return true;
    }

    /** The downloadUri of a filedetails answer, when it is a WoWInterface https link. */
    @SuppressWarnings("unchecked")
    static String downloadUri(String detailsJson) {
        Object root;
        try {
            root = new AddonStore.MiniJson(detailsJson).parse();
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (root instanceof List && !((List<Object>) root).isEmpty()) root = ((List<Object>) root).get(0);
        if (!(root instanceof Map)) return null;
        String uri = text(((Map<String, Object>) root).get("downloadUri"));
        return uri.startsWith("https://cdn.wowinterface.com/") ? uri : null;
    }

    private static String text(Object o) {
        return o instanceof String ? ((String) o).trim() : "";
    }

    /**
     * The catalog's add-ons that are made for the game and match query, each checked by
     * downloading it (into cacheDir, removed afterwards). checked keeps earlier answers by
     * "id:version" (their folders, empty when not for the game), so a store opened again only
     * downloads new versions. total is how many add-ons of the catalog list the game and match.
     */
    public static Found findForGame(String gameVersion, int gameInterface, Set<String> skipTitles, String query,
                                    Map<String, List<String>> checked, File cacheDir) throws IOException {
        String list = catalog();
        boolean search = query != null && !query.trim().isEmpty();
        Found found = candidates(list, gameVersion, skipTitles, query, search ? MAX_SEARCH_CANDIDATES : MAX_CANDIDATES);
        List<Item> result = new ArrayList<>();
        for (Item candidate : found.items) {
            Item item = candidate.copy();
            String key = item.id + ":" + item.version + ":" + gameInterface / 100;
            List<String> folders = checked.get(key);
            if (folders == null) {
                folders = Collections.emptyList();
                File zip = new File(cacheDir, "wowi-check-" + item.id + ".zip");
                try {
                    download(item, zip, null);
                    folders = AddonInstaller.foldersForGame(zip, gameInterface);
                } catch (IOException e) {
                    // Not reachable now: ask again next time.
                    continue;
                } finally {
                    zip.delete();
                }
                checked.put(key, folders);
            }
            if (folders.isEmpty()) continue;
            item.folders = folders;
            result.add(item);
        }
        return new Found(result, found.total);
    }

    /** WoWInterface's catalog, downloaded again when the copy in memory is older than LIST_MAX_AGE_MS. */
    private static synchronized String catalog() throws IOException {
        long now = System.currentTimeMillis();
        if (cachedList == null || now - cachedListAt > LIST_MAX_AGE_MS) {
            cachedList = AddonStore.fetchText(new URL(LIST_URL), 32 << 20);
            cachedListAt = now;
        }
        return cachedList;
    }

    /** Downloads the add-on and installs it, checking again that it is made for the game. */
    public static List<String> install(Item item, int gameInterface, File addonsDir, File cacheDir, ThorSetup.Progress progress) throws IOException {
        File zip = new File(cacheDir, "addon-store.zip");
        try {
            download(item, zip, progress);
            return AddonInstaller.install(zip, addonsDir, gameInterface);
        } finally {
            zip.delete();
        }
    }

    private static void download(Item item, File zip, ThorSetup.Progress progress) throws IOException {
        String uri = downloadUri(AddonStore.fetchText(new URL(String.format(Locale.ROOT, DETAILS_URL, item.id)), 4 << 20));
        if (uri == null) throw new IOException("WoWInterface gave no download link");
        AddonStore.download(new URL(uri), zip, progress);
    }
}
