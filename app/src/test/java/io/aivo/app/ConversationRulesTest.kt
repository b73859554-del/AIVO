package io.aivo.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ConversationRulesTest {
    @Test
    fun normalizesTitlesAndCapsLength() {
        assertEquals("AIVO support thread", ConversationRules.normalizeTitle("  AIVO   support\nthread "))
        assertEquals(
            ConversationRules.MAX_TITLE_LENGTH,
            ConversationRules.titleFromFirstMessage("x".repeat(ConversationRules.MAX_TITLE_LENGTH + 20)).length,
        )
    }

    @Test
    fun rejectsEmptyRenamedTitle() {
        assertThrows(IllegalArgumentException::class.java) {
            ConversationRules.normalizeTitle(" \n ")
        }
    }
}
