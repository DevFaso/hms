package com.bitnesttechs.hms.patient.core.models

import com.bitnesttechs.hms.patient.StringsXml
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The treatment-plan and referral tabs printed `REVISIONS_REQUIRED` and
 * `ACKNOWLEDGED` in every language. Every constant of every health-records
 * enum now maps to a resource that exists, non-blank, in each shipped
 * locale, with the portal's wording.
 */
class HealthRecordEnumLabelsTest {

    private val locales = listOf("values", "values-fr")
    private val strings = locales.associateWith { StringsXml.read(it) }

    private fun <T : Enum<T>> assertEveryConstantLabelled(entries: List<T>, prefix: String, labelRes: (T) -> Int) {
        for (constant in entries) {
            val key = "${prefix}_${constant.name.lowercase()}"
            assertEquals("$constant must use R.string.$key", StringsXml.resourceId(key), labelRes(constant))
            for (locale in locales) {
                val text = strings.getValue(locale)[key]
                assertFalse("$locale has no label for $constant", text.isNullOrBlank())
                assertFalse("$locale label for $constant is the raw constant", text == constant.name)
            }
        }
    }

    @Test
    fun `every health-records enum constant has a label in every locale`() {
        assertEveryConstantLabelled(TreatmentPlanStatus.entries, "tp_status") { it.labelRes }
        assertEveryConstantLabelled(ReferralStatus.entries, "referral_status") { it.labelRes }
        assertEveryConstantLabelled(ReferralType.entries, "referral_type") { it.labelRes }
        assertEveryConstantLabelled(ReferralUrgency.entries, "referral_urgency") { it.labelRes }
        assertEveryConstantLabelled(ReferralSpecialty.entries, "referral_specialty") { it.labelRes }
        assertEveryConstantLabelled(PatientGender.entries, "gender") { it.labelRes }
    }

    @Test
    fun `visit, family access, appointment and invoice enums are labelled too`() {
        assertEveryConstantLabelled(EncounterType.entries, "encounter_type") { it.labelRes }
        assertEveryConstantLabelled(EncounterStatus.entries, "encounter_status") { it.labelRes }
        assertEveryConstantLabelled(DischargeDisposition.entries, "discharge_disposition") { it.labelRes }
        assertEveryConstantLabelled(ProxyPermission.entries, "proxy_permission") { it.labelRes }
        assertEveryConstantLabelled(ProxyStatus.entries, "proxy_status") { it.labelRes }
        assertEveryConstantLabelled(AppointmentStatus.entries, "appointment_status") { it.labelRes }
        assertEveryConstantLabelled(InvoiceStatus.entries, "invoice_status") { it.labelRes }
        assertEquals(AppointmentStatus.NO_SHOW, AppointmentStatus.fromWire("NO_SHOW"))
        assertEquals("Absent", strings.getValue("values-fr")["appointment_status_no_show"])
        assertEquals("Partiellement payée", strings.getValue("values-fr")["invoice_status_partially_paid"])
    }

    @Test
    fun `the two values the tasklist caught read as the portal words them`() {
        val en = strings.getValue("values")
        val fr = strings.getValue("values-fr")
        assertEquals("Revisions Required", en["tp_status_revisions_required"])
        assertEquals("Révisions requises", fr["tp_status_revisions_required"])
        assertEquals("Acknowledged", en["referral_status_acknowledged"])
        assertEquals("Confirmée", fr["referral_status_acknowledged"])
        assertEquals("Transfert d'urgence", fr["referral_type_emergency_transfer"])
    }

    @Test
    fun `wire values map case-insensitively and anything unknown gets a neutral label`() {
        assertEquals(TreatmentPlanStatus.REVISIONS_REQUIRED, TreatmentPlanStatus.fromWire("REVISIONS_REQUIRED"))
        assertEquals(ReferralStatus.ACKNOWLEDGED, ReferralStatus.fromWire(" acknowledged "))
        assertEquals(TreatmentPlanStatus.UNKNOWN, TreatmentPlanStatus.fromWire("ACTIVE"))
        assertEquals(ReferralStatus.UNKNOWN, ReferralStatus.fromWire(null))
        assertEquals(ReferralSpecialty.OBSTETRICS_GYNECOLOGY, ReferralSpecialty.fromWire("OBSTETRICS_GYNECOLOGY"))
        assertEquals(PatientGender.FEMALE, PatientGender.fromWire("female"))
        assertEquals(PatientGender.PREFER_NOT_TO_SAY, PatientGender.fromWire("Prefer not to say"))
        // Free text the portal has no label for is shown as entered, not relabelled.
        assertNull(PatientGender.fromWire("F"))
    }

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    @Test
    fun `referrals decode the fields GeneralReferralResponseDTO actually sends`() {
        val dto = moshi.adapter(ReferralDto::class.java).fromJson(
            """
            {"id": "r1", "referralType": "CONSULTATION", "status": "ACKNOWLEDGED", "urgency": "URGENT",
             "targetSpecialty": "CARDIOLOGY", "receivingProviderName": "Dr Sawadogo",
             "referringProviderName": "Dr Kaboré", "receivingHospitalName": "CHU Yalgado",
             "referralReason": "Souffle cardiaque", "submittedAt": "2026-09-19T10:30:00"}
            """.trimIndent()
        )!!
        assertEquals(ReferralStatus.ACKNOWLEDGED, dto.statusEnum)
        assertEquals(ReferralType.CONSULTATION, dto.typeEnum)
        assertEquals(ReferralUrgency.URGENT, dto.urgencyEnum)
        assertEquals(ReferralSpecialty.CARDIOLOGY, dto.specialtyEnum)
        assertEquals("CHU Yalgado", dto.destination)
        assertEquals("Souffle cardiaque", dto.referralReason)
    }

    @Test
    fun `treatment plans decode the fields TreatmentPlanResponseDTO actually sends`() {
        val dto = moshi.adapter(TreatmentPlanDto::class.java).fromJson(
            """
            {"id": "p1", "status": "REVISIONS_REQUIRED", "problemStatement": "Hypertension follow-up",
             "therapeuticGoals": ["Goal A", "Goal B"], "timelineStartDate": "2026-09-01",
             "timelineReviewDate": "2026-12-01", "authorStaffName": "Dr Ouedraogo"}
            """.trimIndent()
        )!!
        assertEquals(TreatmentPlanStatus.REVISIONS_REQUIRED, dto.statusEnum)
        assertEquals("Hypertension follow-up", dto.problemStatement)
        assertEquals(listOf("Goal A", "Goal B"), dto.therapeuticGoals)
        assertTrue(dto.timelineReviewDate == "2026-12-01")
    }
}
