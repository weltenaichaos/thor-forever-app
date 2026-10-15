package com.winlator.cmod.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.List;

public class AddonStoreTest {
    // Copied from the latest releases on 2026-10-15 (Questie's own extra section left out).
    private static final String QUESTIE = "{\"releases\": [{\"filename\": \"Questie-v12.1.0+v1.0.6.zip\", \"nolib\": false, \"metadata\": ["
            + "{\"flavor\": \"classic\", \"interface\": 11508}, {\"flavor\": \"classic\", \"interface\": 11509}, {\"flavor\": \"bcc\", \"interface\": 20506},"
            + " {\"flavor\": \"wrath\", \"interface\": 38002}, {\"flavor\": \"mists\", \"interface\": 50503}, {\"flavor\": \"mists\", \"interface\": 50504},"
            + " {\"flavor\": \"forever\", \"interface\": 16001}]}], \"questie\": {\"changelog\": [{\"text\": \"Added \\\"Known Professions\\\"\"}]}}";
    private static final String DBM_VANILLA = "{\"releases\":[{\"name\":\"DBM - Vanilla and Season of Discovery\",\"version\":\"r832\",\"filename\":\"DBM-Vanilla_SoD-r832.zip\",\"nolib\":false,"
            + "\"metadata\":[{\"flavor\":\"mists\",\"interface\":50504},{\"flavor\":\"classic\",\"interface\":11509},{\"flavor\":\"forever\",\"interface\":16001}]}]}";
    private static final String WEAKAURAS = "{\"releases\":[{\"name\":\"WeakAuras\",\"version\":\"5.22.1\",\"filename\":\"WeakAuras-5.22.1.zip\",\"nolib\":false,"
            + "\"metadata\":[{\"flavor\":\"mists\",\"interface\":50504},{\"flavor\":\"classic\",\"interface\":11509},{\"flavor\":\"bcc\",\"interface\":20506}]}]}";

    @Test
    public void turnsTheGameVersionIntoTheInterfaceNumber() {
        assertEquals(16001, AddonStore.interfaceOf("1.60.1.70374"));
        assertEquals(11508, AddonStore.interfaceOf("1.15.8.63829"));
        assertEquals(0, AddonStore.interfaceOf(""));
    }

    @Test
    public void offersTheFileMadeForTheGame() {
        AddonStore.Offer questie = AddonStore.pickFile(QUESTIE, 16001);
        assertEquals("Questie-v12.1.0+v1.0.6.zip", questie.filename);
        assertEquals("Questie-v12.1.0+v1.0.6", questie.version);
        assertEquals("r832", AddonStore.pickFile(DBM_VANILLA, 16001).version);
    }

    @Test
    public void aSmallGamePatchStillFindsTheFile() {
        assertEquals("r832", AddonStore.pickFile(DBM_VANILLA, 16002).version);
        assertNull(AddonStore.pickFile(DBM_VANILLA, 16101));
    }

    @Test
    public void offersNothingWhenTheAddonIsNotMadeForTheGame() {
        assertNull(AddonStore.pickFile(WEAKAURAS, 16001));
        assertNull(AddonStore.pickFile("<html>Not Found</html>", 16001));
        assertNull(AddonStore.pickFile(QUESTIE, 0));
    }

    @Test
    public void refusesAFileNameThatIsNotPlain() {
        assertNull(AddonStore.pickFile("{\"releases\":[{\"filename\":\"../x.zip\",\"metadata\":[{\"interface\":16001}]}]}", 16001));
    }

    @Test
    public void readsTheStoreList() {
        List<AddonStore.Entry> entries = AddonStore.parseCatalog("# comment\nQuestie|Questie/Questie|Questie|Quests on the map\nbad line\nX|not a repo|X|y\n");
        assertEquals(1, entries.size());
        assertEquals("Questie/Questie", entries.get(0).repo);
        assertEquals("Questie", entries.get(0).folder);
    }
}
