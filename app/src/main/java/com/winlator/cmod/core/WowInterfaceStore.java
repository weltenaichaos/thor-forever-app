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
 * The site has no WoW Forever tag yet, so the candidates are the add-ons that list the game's
 * version (1.60) or say "Forever" in their name or version. Each one is downloaded and kept
 * only when its own .toc is made for the game: the store never shows an add-on that isn't.
 */
public final class WowInterfaceStore {
    static final String LIST_URL = "https://api.mmoui.com/v4/game/WOW/filelist.json";
    static final String DETAILS_URL = "https://api.mmoui.com/v4/game/WOW/filedetails/%d.json";
    /** At most this many candidates are downloaded to check them. */
    static final int MAX_CANDIDATES = 30;

    private WowInterfaceStore() {}

    /** A WoWInterface add-on. folders is set once it has been checked. */
    public static final class Item {
        public final int id;
        public final String title;
        public final String version;
        public final String author;
        final long downloads;
        public List<String> folders = Collections.emptyList();

        Item(int id, String title, String version, String author, long downloads) {
            this.id = id;
            this.title = title;
            this.version = version;
            this.author = author;
            this.downloads = downloads;
        }
    }

    /**
     * The add-ons of the catalog that may be made for the game: listing its version (gameVersion
     * 1.60.1 matches 1.60 and 1.60.x) or saying "Forever". Most downloaded first; titles in
     * skipTitles (lower case, the store's own list) are left out.
     */
    @SuppressWarnings("unchecked")
    static List<Item> candidates(String listJson, String gameVersion, Set<String> skipTitles) {
        List<Item> items = new ArrayList<>();
        String[] p = gameVersion == null ? new String[0] : gameVersion.split("\\.");
        if (p.length < 2) return items;
        String majorMinor = p[0] + "." + p[1];
        Object root;
        try {
            root = new AddonStore.MiniJson(listJson).parse();
        } catch (IllegalArgumentException e) {
            return items;
        }
        if (!(root instanceof List)) return items;
        for (Object o : (List<Object>) root) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> m = (Map<String, Object>) o;
            String title = text(m.get("title"));
            String version = text(m.get("version"));
            if (!(m.get("id") instanceof Number) || title.isEmpty()) continue;
            if (skipTitles.contains(title.toLowerCase(Locale.ROOT))) continue;
            boolean listed = false;
            if (m.get("gameVersions") instanceof List)
                for (Object v : (List<Object>) m.get("gameVersions")) {
                    String gv = text(v);
                    if (gv.equals(majorMinor) || gv.startsWith(majorMinor + ".")) listed = true;
                }
            boolean saysForever = (title + " " + version).toLowerCase(Locale.ROOT).contains("forever");
            if (!listed && !saysForever) continue;
            long downloads = m.get("downloads") instanceof Number ? ((Number) m.get("downloads")).longValue() : 0;
            items.add(new Item(((Number) m.get("id")).intValue(), title, version, text(m.get("author")), downloads));
        }
        Collections.sort(items, (a, b) -> Long.compare(b.downloads, a.downloads));
        return items.size() > MAX_CANDIDATES ? new ArrayList<>(items.subList(0, MAX_CANDIDATES)) : items;
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
     * The catalog's add-ons that are made for the game, each checked by downloading it (into
     * cacheDir, removed afterwards). checked keeps earlier answers by "id:version" (true or
     * false), so a store opened again only downloads new versions.
     */
    public static List<Item> findForGame(String gameVersion, int gameInterface, Set<String> skipTitles,
                                         Map<String, List<String>> checked, File cacheDir) throws IOException {
        String list = AddonStore.fetchText(new URL(LIST_URL), 32 << 20);
        List<Item> result = new ArrayList<>();
        for (Item item : candidates(list, gameVersion, skipTitles)) {
            String key = item.id + ":" + item.version;
            List<String> folders = checked.get(key);
            if (folders == null) {
                folders = Collections.emptyList();
                File zip = new File(cacheDir, "wowi-check.zip");
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
        return result;
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
