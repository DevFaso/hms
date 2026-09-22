package com.bitnesttechs.hms.patient.core.models

import com.bitnesttechs.hms.patient.core.network.ApiResponse
import com.bitnesttechs.hms.patient.features.medicalhistory.MedicalHistoryViewModel
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MedicalHistoryModelsTest {
    private val moshi = Moshi.Builder().build()

    private inline fun <reified T> listAdapter() = moshi.adapter<ApiResponse<List<T>>>(
        Types.newParameterizedType(ApiResponse::class.java, Types.newParameterizedType(List::class.java, T::class.java))
    )

    @Test
    fun medicalHistoryDecodesTheWrappedDiagnosisList() {
        val decoded = listAdapter<PatientDiagnosisSummary>().fromJson(
            """
            {"success":true,"message":"ok","data":[
              {"id":"d-1","description":"Hypertension","icdCode":"I10","status":"ACTIVE",
               "diagnosedAt":"2026-03-04T10:15:00+00:00","diagnosedByName":"Dr Kone"},
              {"id":"d-2","description":"Asthma","icdCode":null,"status":"RESOLVED","diagnosedAt":null,"diagnosedByName":null}
            ]}
            """.trimIndent()
        )
        val rows = decoded?.data.orEmpty()
        assertEquals(listOf("d-1", "d-2"), rows.map { it.id })
        assertEquals("2026-03-04T10:15:00+00:00", rows[0].diagnosedAt)
        assertNull(rows[1].diagnosedAt)
    }

    @Test
    fun surgicalHistoryDecodesWithNonNullFieldsOmitted() {
        val decoded = listAdapter<SurgicalHistoryEntry>().fromJson(
            """{"success":true,"data":[{"id":"s-1","patientId":"p-1","procedureDisplay":"Appendectomy","procedureDate":"2019-06-01"}]}"""
        )
        val row = decoded?.data?.single()
        assertEquals("Appendectomy", row?.procedureDisplay)
        assertEquals("2019-06-01", row?.procedureDate)
        assertNull(row?.outcome)
    }

    @Test
    fun familyHistoryIgnoresTheFieldsTheWebDoesNotShow() {
        val decoded = listAdapter<FamilyHistoryEntry>().fromJson(
            """
            {"success":true,"data":[{"id":"f-1","patientId":"p-1","relationship":"Mother","relativeName":"Awa",
              "conditionDisplay":"Type 2 diabetes","ageAtOnset":52,"severity":"MODERATE","isDiabetes":true,
              "geneticCondition":false,"createdAt":"2026-01-01T00:00:00"}]}
            """.trimIndent()
        )
        val row = decoded?.data?.single()
        assertEquals("Mother", row?.relationship)
        assertEquals(52, row?.ageAtOnset)
        assertEquals("MODERATE", row?.severity)
    }

    @Test
    fun socialHistoryDecodesAndANullDataMeansNothingOnRecord() {
        val adapter = moshi.adapter<ApiResponse<SocialHistory>>(
            Types.newParameterizedType(ApiResponse::class.java, SocialHistory::class.java)
        )
        val present = adapter.fromJson(
            """{"success":true,"data":{"id":"sh-1","tobaccoUse":true,"tobaccoType":"Cigarettes","tobaccoPacksPerDay":0.5,
               "tobaccoQuitDate":"2024-02-10","alcoholUse":true,"alcoholFrequency":"Weekly","alcoholDrinksPerWeek":3,"active":true}}"""
        )
        assertEquals("Cigarettes", present?.data?.tobaccoType)
        assertEquals(3, present?.data?.alcoholDrinksPerWeek)
        val absent = adapter.fromJson("""{"success":true,"message":"ok","data":null}""")
        assertTrue(absent?.success == true)
        assertNull(absent?.data)
    }

    @Test
    fun tobaccoStatusFollowsTheWebRule() {
        assertEquals(
            MedicalHistoryViewModel.TobaccoStatus.FORMER,
            MedicalHistoryViewModel.tobaccoStatus(SocialHistory(tobaccoUse = true, tobaccoQuitDate = "2024-02-10"))
        )
        assertEquals(
            MedicalHistoryViewModel.TobaccoStatus.CURRENT,
            MedicalHistoryViewModel.tobaccoStatus(SocialHistory(tobaccoUse = true))
        )
        assertEquals(
            MedicalHistoryViewModel.TobaccoStatus.NEVER,
            MedicalHistoryViewModel.tobaccoStatus(SocialHistory(tobaccoUse = false))
        )
        assertEquals(MedicalHistoryViewModel.TobaccoStatus.NEVER, MedicalHistoryViewModel.tobaccoStatus(SocialHistory()))
    }
}
