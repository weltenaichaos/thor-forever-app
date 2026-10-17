package com.winlator.cmod.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NotificationServiceTest {
    @Test
    public void closingTheCompanionKeepsTheGameRunning() {
        assertFalse(NotificationService.endsTheApp("thor.companion.CompanionActivity"));
    }

    @Test
    public void closingTheGameStillEndsTheApp() {
        assertTrue(NotificationService.endsTheApp("com.winlator.cmod.ui.start.ThorStartActivity"));
        assertTrue(NotificationService.endsTheApp("com.winlator.cmod.MainActivity"));
        assertTrue(NotificationService.endsTheApp("com.winlator.cmod.XServerDisplayActivity"));
        assertTrue(NotificationService.endsTheApp(null));
    }

    @Test
    public void theNameMatchesTheCompanion() {
        assertEquals(thor.companion.CompanionActivity.class.getName(), NotificationService.COMPANION_ACTIVITY);
    }
}
