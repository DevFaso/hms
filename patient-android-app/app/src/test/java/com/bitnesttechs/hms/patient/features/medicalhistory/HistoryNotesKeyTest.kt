package com.bitnesttechs.hms.patient.features.medicalhistory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Notes were filed under the Keycloak `sub` on the SSO path and under the
 * HMS user id on the password path. Both now use the user id; the notes of
 * the current SSO session move over once, and never merge into a bucket
 * that already has notes.
 */
class HistoryNotesKeyTest {

    private val sub = "kc-sub-1111"
    private val userId = "hms-user-2222"

    @Test
    fun `the current session's notes move from the sub key to the user id key`() {
        val keys = setOf("$sub:MEDICAL", "$sub:FAMILY")
        assertEquals(
            mapOf("$sub:MEDICAL" to "$userId:MEDICAL", "$sub:FAMILY" to "$userId:FAMILY"),
            HistoryNotesStore.subjectMove(keys, sub, userId)
        )
    }

    @Test
    fun `nothing moves when the user id already has notes of its own`() {
        val keys = setOf("$sub:MEDICAL", "$userId:SOCIAL")
        assertTrue(HistoryNotesStore.subjectMove(keys, sub, userId).isEmpty())
    }

    @Test
    fun `another patient's notes on the same phone are never touched`() {
        val keys = setOf("someone-else-sub:MEDICAL", "another-user-id:FAMILY")
        assertTrue(HistoryNotesStore.subjectMove(keys, sub, userId).isEmpty())
    }

    @Test
    fun `a key that merely starts with the same characters is not the sub's`() {
        val keys = setOf("${sub}9:MEDICAL")
        assertTrue(HistoryNotesStore.subjectMove(keys, sub, userId).isEmpty())
    }

    @Test
    fun `nothing moves when the sub already is the user id`() {
        assertTrue(HistoryNotesStore.subjectMove(setOf("$userId:MEDICAL"), userId, userId).isEmpty())
    }
}
