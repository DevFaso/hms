package com.bitnesttechs.hms.patient.core.models

import com.bitnesttechs.hms.patient.R
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G16 — a patient must never read `PENDING_STOCK` or `PARTNER_REJECTED` off
 * their own prescription list, in either language the apps ship.
 *
 * The three enums below map every constant to a string resource through an
 * exhaustive `when`, so the compiler already refuses a constant without a
 * label. What the compiler cannot see is whether that label EXISTS in
 * `values/strings.xml` AND `values-fr/strings.xml` — `lintDebug` would catch a
 * missing English key but a French one merely falls back to English at
 * runtime. This test reads both files and asserts the exact text for the whole
 * enum, so a status added later fails here rather than shipping in English to
 * a French-speaking patient.
 */
class StatusLabelResourcesTest {

    private val english = readStrings("values")
    private val french = readStrings("values-fr")

    // ── Lab result statuses ──────────────────────────────────────────────

    @Test
    fun everyLabStatusHasAnEnglishAndAFrenchLabel() {
        assertLabels(
            LabResultStatus.entries.associateWith { "lab_status_${it.name.lowercase()}" },
            { it.labelRes },
            expectedEnglish = mapOf(
                LabResultStatus.PENDING to "Pending",
                LabResultStatus.NORMAL to "Normal",
                LabResultStatus.ABNORMAL to "Abnormal",
                LabResultStatus.ABNORMAL_LOW to "Abnormal — low",
                LabResultStatus.ABNORMAL_HIGH to "Abnormal — high",
                LabResultStatus.CRITICAL to "Critical",
                LabResultStatus.UNKNOWN to "Status unavailable"
            ),
            expectedFrench = mapOf(
                LabResultStatus.PENDING to "En attente",
                LabResultStatus.NORMAL to "Normal",
                LabResultStatus.ABNORMAL to "Anormal",
                LabResultStatus.ABNORMAL_LOW to "Anormal — bas",
                LabResultStatus.ABNORMAL_HIGH to "Anormal — élevé",
                LabResultStatus.CRITICAL to "Critique",
                LabResultStatus.UNKNOWN to "Statut indisponible"
            )
        )
    }

    // ── Prescription statuses ────────────────────────────────────────────

    @Test
    fun everyPrescriptionStatusHasAnEnglishAndAFrenchLabel() {
        assertLabels(
            PrescriptionStatus.entries.associateWith { "rx_status_${it.name.lowercase()}" },
            { it.labelRes },
            expectedEnglish = mapOf(
                PrescriptionStatus.DRAFT to "Draft",
                PrescriptionStatus.PENDING_SIGNATURE to "Awaiting signature",
                PrescriptionStatus.SIGNED to "Signed",
                PrescriptionStatus.TRANSMITTED to "Sent to the pharmacy",
                PrescriptionStatus.TRANSMISSION_FAILED to "Could not be sent",
                PrescriptionStatus.CANCELLED to "Cancelled",
                PrescriptionStatus.DISCONTINUED to "Discontinued",
                PrescriptionStatus.PENDING_CLARIFICATION to "Awaiting your prescriber",
                PrescriptionStatus.DISPENSED to "Dispensed",
                PrescriptionStatus.PARTIALLY_FILLED to "Partly dispensed",
                PrescriptionStatus.PENDING_STOCK to "Out of stock",
                PrescriptionStatus.REQUIRES_EXTERNAL_FILL to "Outside pharmacy",
                PrescriptionStatus.SENT_TO_PARTNER to "Sent to a partner pharmacy",
                PrescriptionStatus.PARTNER_ACCEPTED to "Accepted by the pharmacy",
                PrescriptionStatus.PARTNER_REJECTED to "Refused by the pharmacy",
                PrescriptionStatus.PARTNER_DISPENSED to "Dispensed by the pharmacy",
                PrescriptionStatus.PRINTED_FOR_PATIENT to "Printed for you",
                PrescriptionStatus.UNKNOWN to "Status unavailable"
            ),
            expectedFrench = mapOf(
                PrescriptionStatus.DRAFT to "Brouillon",
                PrescriptionStatus.PENDING_SIGNATURE to "En attente de signature",
                PrescriptionStatus.SIGNED to "Signée",
                PrescriptionStatus.TRANSMITTED to "Transmise à la pharmacie",
                PrescriptionStatus.TRANSMISSION_FAILED to "Envoi impossible",
                PrescriptionStatus.CANCELLED to "Annulée",
                PrescriptionStatus.DISCONTINUED to "Interrompue",
                PrescriptionStatus.PENDING_CLARIFICATION to "En attente de votre prescripteur",
                PrescriptionStatus.DISPENSED to "Délivrée",
                PrescriptionStatus.PARTIALLY_FILLED to "Partiellement délivrée",
                PrescriptionStatus.PENDING_STOCK to "En rupture de stock",
                PrescriptionStatus.REQUIRES_EXTERNAL_FILL to "Pharmacie externe",
                PrescriptionStatus.SENT_TO_PARTNER to "Envoyée à une pharmacie partenaire",
                PrescriptionStatus.PARTNER_ACCEPTED to "Acceptée par la pharmacie",
                PrescriptionStatus.PARTNER_REJECTED to "Refusée par la pharmacie",
                PrescriptionStatus.PARTNER_DISPENSED to "Délivrée par la pharmacie",
                PrescriptionStatus.PRINTED_FOR_PATIENT to "Imprimée pour vous",
                PrescriptionStatus.UNKNOWN to "Statut indisponible"
            )
        )
    }

    // ── Refill statuses ──────────────────────────────────────────────────

    @Test
    fun everyRefillStatusHasAnEnglishAndAFrenchLabel() {
        assertLabels(
            RefillStatus.entries.associateWith { "refill_status_${it.name.lowercase()}" },
            { it.labelRes },
            expectedEnglish = mapOf(
                RefillStatus.REQUESTED to "Requested",
                RefillStatus.PAUSED to "On hold",
                RefillStatus.APPROVED to "Approved",
                RefillStatus.DENIED to "Denied",
                RefillStatus.DISPENSED to "Dispensed",
                RefillStatus.CANCELLED to "Cancelled",
                RefillStatus.UNKNOWN to "Status unavailable"
            ),
            expectedFrench = mapOf(
                RefillStatus.REQUESTED to "Demandé",
                RefillStatus.PAUSED to "Mis en attente",
                RefillStatus.APPROVED to "Approuvé",
                RefillStatus.DENIED to "Refusé",
                RefillStatus.DISPENSED to "Délivré",
                RefillStatus.CANCELLED to "Annulé",
                RefillStatus.UNKNOWN to "Statut indisponible"
            )
        )
    }

    @Test
    fun noStatusLabelIsTheRawWireName() {
        val keys = LabResultStatus.entries.map { "lab_status_${it.name.lowercase()}" } +
            PrescriptionStatus.entries.map { "rx_status_${it.name.lowercase()}" } +
            RefillStatus.entries.map { "refill_status_${it.name.lowercase()}" }
        for (key in keys) {
            for ((locale, table) in listOf("en" to english, "fr" to french)) {
                val label = table[key]
                assertNotNull("$locale is missing $key", label)
                assertTrue(
                    "$locale label for $key still reads like a wire constant: $label",
                    !label!!.contains('_') && label != label.uppercase()
                )
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun <T : Enum<T>> assertLabels(
        keysByConstant: Map<T, String>,
        labelResOf: (T) -> Int,
        expectedEnglish: Map<T, String>,
        expectedFrench: Map<T, String>
    ) {
        // Whole enum, not a sample: a constant added without an expectation
        // fails right here.
        assertEquals(keysByConstant.keys, expectedEnglish.keys)
        assertEquals(keysByConstant.keys, expectedFrench.keys)

        for ((constant, key) in keysByConstant) {
            assertEquals(
                "$constant does not use R.string.$key",
                resourceId(key),
                labelResOf(constant)
            )
            assertEquals("English label for $constant", expectedEnglish[constant], english[key])
            assertEquals("French label for $constant", expectedFrench[constant], french[key])
        }
    }

    private fun resourceId(key: String): Int = R.string::class.java.getField(key).getInt(null)

    private fun readStrings(qualifier: String): Map<String, String> {
        val file = resolve("src/main/res/$qualifier/strings.xml")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("string")
        return buildMap {
            for (index in 0 until nodes.length) {
                val node = nodes.item(index)
                val name = node.attributes.getNamedItem("name")?.nodeValue ?: continue
                // strings.xml escapes apostrophes for the Android resource
                // compiler; compare against the text a user reads.
                put(name, node.textContent.replace("\\'", "'"))
            }
        }
    }

    /**
     * Gradle runs unit tests with the module directory as the working
     * directory; the repo-root fallback keeps the test runnable from an IDE
     * configured otherwise.
     */
    private fun resolve(relative: String): File {
        val candidates = listOf(
            File(relative),
            File("app/$relative"),
            File("patient-android-app/app/$relative")
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("cannot find $relative from ${File(".").absolutePath}")
    }
}
