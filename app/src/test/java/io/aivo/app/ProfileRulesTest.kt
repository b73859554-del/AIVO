package io.aivo.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileRulesTest {
    @Test
    fun normalizesWhitespaceAndTrimsName() {
        assertEquals("AIVO User Name", ProfileRules.normalizeDisplayName("  AIVO   User\n Name  "))
    }

    @Test
    fun limitsDisplayNameLength() {
        assertEquals(
            ProfileRules.MAX_DISPLAY_NAME_LENGTH,
            ProfileRules.normalizeDisplayName("A".repeat(ProfileRules.MAX_DISPLAY_NAME_LENGTH + 5)).length,
        )
    }

    @Test
    fun firstNameUsesTheFirstNormalizedWord() {
        assertEquals("Taylor", ProfileRules.firstName("  Taylor   Morgan "))
        assertNull(ProfileRules.firstName(" \n "))
    }
}
