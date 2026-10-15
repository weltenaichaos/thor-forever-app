package com.winlator.cmod.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GameUpdateCheckTest {
    // As us.version.battle.net/v2/products/wow_classic_beta/versions answered on 2026-10-15,
    // with a made-up kr row to test the region pick.
    private static final String VERSIONS =
            "Region!STRING:0|BuildConfig!HEX:16|CDNConfig!HEX:16|KeyRing!HEX:16|BuildId!DEC:4|VersionsName!String:0|ProductConfig!HEX:16\n"
            + "## seqn = 3012345\n"
            + "us|25822068f8fce8b21bc714e7e3210481|a0408f97170527fd6ebd148af45e80b8||70374|1.60.1.70374|fcfd1bc39031f5921d00aee2d869adb5\n"
            + "eu|25822068f8fce8b21bc714e7e3210481|a0408f97170527fd6ebd148af45e80b8||70374|1.60.1.70374|fcfd1bc39031f5921d00aee2d869adb5\n"
            + "kr|aaaa|bbbb||70300|1.60.1.70300|cccc\n";

    @Test
    public void picksThePlayersRegion() {
        assertEquals("1.60.1.70374", GameUpdateCheck.versionFromVersions(VERSIONS, "eu"));
        assertEquals("1.60.1.70300", GameUpdateCheck.versionFromVersions(VERSIONS, "KR"));
    }

    @Test
    public void withoutTheRegionTakesTheFirstRow() {
        assertEquals("1.60.1.70374", GameUpdateCheck.versionFromVersions(VERSIONS, "xx"));
        assertEquals("1.60.1.70374", GameUpdateCheck.versionFromVersions(VERSIONS, ""));
        assertEquals("", GameUpdateCheck.versionFromVersions("<html>error</html>", "eu"));
    }

    @Test
    public void seesAnUpdateOnlyWhenTheVersionsDiffer() {
        assertTrue(GameUpdateCheck.updateAvailable("1.60.1.70009", "1.60.1.70374"));
        assertFalse(GameUpdateCheck.updateAvailable("1.60.1.70374", "1.60.1.70374"));
        assertFalse(GameUpdateCheck.updateAvailable("", "1.60.1.70374"));
        assertFalse(GameUpdateCheck.updateAvailable("1.60.1.70374", ""));
    }

    @Test
    public void readsTheRegionFromBuildInfo() {
        String buildInfo = "Branch!STRING:0|Active!DEC:1|Version!STRING:0|Product!STRING:0\r\n"
                + "us|1|11.2.5.63506|wow\r\n"
                + "eu|1|1.60.1.70009|wow_classic_beta\r\n";
        assertEquals("eu", ThorGame.fieldFromBuildInfo(buildInfo, "wow_classic_beta", "Branch"));
    }
}
