package com.bitnesttechs.hms.patient.core.models

import com.bitnesttechs.hms.patient.core.network.ApiResponse
import com.bitnesttechs.hms.patient.core.network.PageDto
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import org.junit.Assert.assertEquals
import org.junit.Test

class HealthRecordsModelsTest {
    private val moshi = Moshi.Builder().build()

    @Test
    fun treatmentPlansDecodeFromPagedApiResponse() {
        val planType = Types.newParameterizedType(PageDto::class.java, TreatmentPlanDto::class.java)
        val responseType = Types.newParameterizedType(ApiResponse::class.java, planType)
        val adapter = moshi.adapter<ApiResponse<PageDto<TreatmentPlanDto>>>(responseType)

        val decoded = adapter.fromJson(
            """
            {
              "success": true,
              "message": "ok",
              "data": {
                "content": [
                  {
                    "id": "plan-1",
                    "title": "Recovery plan",
                    "status": "ACTIVE"
                  }
                ],
                "totalElements": 1,
                "totalPages": 1,
                "number": 0,
                "size": 20,
                "last": true
              }
            }
            """.trimIndent()
        )

        assertEquals("Recovery plan", decoded?.data?.content?.single()?.title)
    }

    @Test
    fun healthRecordProvenanceFieldsDecode() {
        val summaryAdapter = moshi.adapter(HealthSummaryDto::class.java)
        val vitalAdapter = moshi.adapter(VitalSignDto::class.java)

        val summary = summaryAdapter.fromJson(
            """
            {
              "profile": {
                "id": "patient-1",
                "firstName": "Amina",
                "lastName": "Diallo",
                "hospitalId": "hospital-1",
                "hospitalName": "Central Hospital",
                "primaryHospitalId": "hospital-1",
                "primaryHospitalName": "Central Hospital"
              },
              "currentMedications": [
                {
                  "id": "med-1",
                  "medicationName": "Atorvastatin",
                  "prescribedBy": "Dr. Mensah",
                  "indication": "Hyperlipidemia"
                }
              ]
            }
            """.trimIndent()
        )
        val vital = vitalAdapter.fromJson(
            """
            {
              "id": "vital-1",
              "hospitalId": "hospital-2",
              "hospitalName": "North Clinic",
              "recordedByName": "Nurse Cole",
              "sourceDisplay": "Manual entry"
            }
            """.trimIndent()
        )

        assertEquals("Central Hospital", summary?.profile?.hospitalName)
        assertEquals("Central Hospital", summary?.profile?.primaryHospitalName)
        assertEquals("Dr. Mensah", summary?.currentMedications?.single()?.prescribedBy)
        assertEquals("North Clinic", vital?.hospitalName)
        assertEquals("Nurse Cole", vital?.recordedByName)
    }
}