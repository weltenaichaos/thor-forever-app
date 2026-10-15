package com.winlator.cmod.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;

public class WowInterfaceStoreTest {
    // Entries as api.mmoui.com/v4/game/WOW/filelist.json listed them on 2026-10-15 (fields trimmed),
    // plus a made-up one that lists 1.60.1.
    private static final String LIST = "["
            + "{\"id\":11190,\"version\":\"4.18.3.1\",\"title\":\"Bartender4\",\"author\":\"Nevcairiel\",\"downloads\":1446096,\"gameVersions\":[\"12.1.0\",\"1.15.7\",\"4.4.2\",\"2.5.4\"]},"
            + "{\"id\":27239,\"version\":\"0.5.0\",\"title\":\"AdBlock Forever\",\"author\":\"a\",\"downloads\":10,\"gameVersions\":[\"12.1.0\"]},"
            + "{\"id\":27238,\"version\":\"0.9.0\",\"title\":\"PartyPins for WoW Forever\",\"author\":\"b\",\"downloads\":20,\"gameVersions\":null},"
            + "{\"id\":27262,\"version\":\"v.1.2.5-forever\",\"title\":\"High Fidelity World Map\",\"author\":\"c\",\"downloads\":30,\"gameVersions\":[\"1.15.7\"]},"
            + "{\"id\":1,\"version\":\"1.0\",\"title\":\"Made Up\",\"author\":\"d\",\"downloads\":5,\"gameVersions\":[\"1.60.1\"]},"
            + "{\"id\":2,\"version\":\"1.0\",\"title\":\"Classic Only\",\"author\":\"e\",\"downloads\":99999,\"gameVersions\":[\"1.15.7\"]}"
            + "]";

    @Test
    public void takesAddonsThatListTheGameOrSayForever() {
        List<WowInterfaceStore.Item> items = WowInterfaceStore.candidates(LIST, "1.60.1.70374", Collections.emptySet());
        assertEquals(4, items.size());
        // Most downloaded first.
        assertEquals("High Fidelity World Map", items.get(0).title);
        assertEquals("PartyPins for WoW Forever", items.get(1).title);
        assertEquals("AdBlock Forever", items.get(2).title);
        assertEquals("Made Up", items.get(3).title);
    }

    @Test
    public void leavesOutTheStoresOwnAddons() {
        HashSet<String> skip = new HashSet<>(Collections.singleton("high fidelity world map"));
        assertEquals(3, WowInterfaceStore.candidates(LIST, "1.60.1", skip).size());
        assertEquals(0, WowInterfaceStore.candidates("not json", "1.60.1", skip).size());
        assertEquals(0, WowInterfaceStore.candidates(LIST, "", skip).size());
    }

    @Test
    public void readsTheDownloadLink() {
        // As filedetails/11190.json answered on 2026-10-15 (description left out).
        String details = "[{\"id\":11190,\"categoryId\":19,\"version\":\"4.18.3.1\",\"fileName\":\"Bartender4-4.18.3.1.zip\","
                + "\"downloadUri\":\"https:\\/\\/cdn.wowinterface.com\\/downloads\\/getfile.php?id=11190&d=1790967342&minion\",\"title\":\"Bartender4\"}]";
        assertEquals("https://cdn.wowinterface.com/downloads/getfile.php?id=11190&d=1790967342&minion", WowInterfaceStore.downloadUri(details));
        assertNull(WowInterfaceStore.downloadUri("[{\"downloadUri\":\"http://evil.example/x.zip\"}]"));
        assertNull(WowInterfaceStore.downloadUri("[]"));
    }
}
