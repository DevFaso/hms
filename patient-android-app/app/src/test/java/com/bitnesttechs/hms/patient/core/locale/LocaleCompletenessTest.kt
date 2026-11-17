package com.bitnesttechs.hms.patient.core.locale

import com.bitnesttechs.hms.patient.StringsXml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4: Spanish is a full locale. lintDebug's MissingTranslation already
 * refuses a string absent from a locale; this also refuses a blank one and a
 * translation that lost or changed a format placeholder (which crashes
 * `getString(id, args)` at run time, not at build time).
 */
class LocaleCompletenessTest {

    private val english = StringsXml.read("values")
    private val untranslatable = StringsXml.untranslatable()
    private val placeholder = Regex("%\\d\\$[sd]")

    private fun assertComplete(qualifier: String) {
        val translated = StringsXml.read(qualifier)
        val problems = mutableListOf<String>()
        for ((name, text) in english) {
            if (name in untranslatable) continue
            val other = translated[name]
            if (other.isNullOrBlank()) {
                problems += "$name is missing"
                continue
            }
            val expected = placeholder.findAll(text).map { it.value }.sorted().toList()
            val actual = placeholder.findAll(other).map { it.value }.sorted().toList()
            if (expected != actual) problems += "$name placeholders $expected became $actual"
        }
        assertTrue("$qualifier:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `every string has a Spanish translation with the same placeholders`() = assertComplete("values-es")

    @Test
    fun `every string has a French translation with the same placeholders`() = assertComplete("values-fr")

    @Test
    fun `Spanish is offered in the language picker and read back as es`() {
        assertEquals(listOf("en", "fr", "es"), LocaleHelper.supportedLanguages)
        assertEquals("Español", LocaleHelper.getDisplayName("es"))
        assertEquals("es", LocaleHelper.normalize("ES"))
    }

    @Test
    fun `the display name never changes with the language`() {
        for (qualifier in listOf("values", "values-fr", "values-es")) {
            assertEquals("e-Keneya", StringsXml.read(qualifier)["app_name"])
        }
    }
}
