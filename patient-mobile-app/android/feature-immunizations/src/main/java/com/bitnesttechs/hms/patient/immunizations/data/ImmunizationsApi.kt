package com.bitnesttechs.hms.patient.immunizations.data

import kotlinx.serialization.Serializable
import retrofit2.http.GET

interface ImmunizationsApi {

    @GET("me/patient/immunizations")
    suspend fun getImmunizations(): List<ImmunizationDto>
}

@Serializable
data class ImmunizationDto(
    val id: String = "",
    val vaccineName: String? = null,
    val vaccineCode: String? = null,
    val doseNumber: Int? = null,
    val totalDoses: Int? = null,
    val administeredDate: String? = null,
    val expirationDate: String? = null,
    val administeredBy: String? = null,
    val site: String? = null,
    val lotNumber: String? = null,
    val manufacturer: String? = null,
    val status: String? = null,
    val notes: String? = null
)
