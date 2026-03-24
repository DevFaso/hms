package com.bitnesttechs.hms.patient.consents.data

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface ConsentsApi {

    @GET("me/patient/consents")
    suspend fun getConsents(@Query("page") page: Int = 0, @Query("size") size: Int = 50): List<ConsentDto>

    @POST("me/patient/consents")
    suspend fun grantConsent(@Body request: GrantConsentRequest)

    @DELETE("me/patient/consents")
    suspend fun revokeConsent(@Query("fromHospitalId") fromId: String, @Query("toHospitalId") toId: String)
}

@Serializable
data class ConsentDto(
    val id: String = "",
    val fromHospitalId: String? = null,
    val fromHospitalName: String? = null,
    val toHospitalId: String? = null,
    val toHospitalName: String? = null,
    val consentType: String? = null,
    val status: String? = null,
    val grantedDate: String? = null,
    val expiryDate: String? = null,
    val notes: String? = null
) {
    val isActive: Boolean get() = status?.uppercase() == "ACTIVE"
}

@Serializable
data class GrantConsentRequest(
    val fromHospitalId: String,
    val toHospitalId: String,
    val consentType: String,
    val notes: String? = null
)
