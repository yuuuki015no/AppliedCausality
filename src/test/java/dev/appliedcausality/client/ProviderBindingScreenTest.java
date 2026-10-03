package dev.appliedcausality.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderBindingScreenTest {
    @Test
    void primaryPatternClickSelectsWithoutTriggeringTheContainerSlot() {
        assertTrue(ProviderBindingScreen.consumesPatternSlotClick(0, 0, false));
        assertTrue(ProviderBindingScreen.consumesPatternSlotClick(8, 0, false));
        assertFalse(ProviderBindingScreen.consumesPatternSlotClick(-1, 0, false));
        assertFalse(ProviderBindingScreen.consumesPatternSlotClick(0, 1, false));
        assertFalse(ProviderBindingScreen.consumesPatternSlotClick(0, 0, true),
                "shift-click is reserved for the normal provider/player transfer path");
    }

    @Test
    void saturatedServerRevisionStillChangesTheClientPresentationToken() {
        assertTrue(ProviderBindingScreen.snapshotPresentationChanged(0L, 1L));
        assertTrue(ProviderBindingScreen.snapshotPresentationChanged(Long.MAX_VALUE, Long.MIN_VALUE));
        assertFalse(ProviderBindingScreen.snapshotPresentationChanged(17L, 17L));
    }
}
