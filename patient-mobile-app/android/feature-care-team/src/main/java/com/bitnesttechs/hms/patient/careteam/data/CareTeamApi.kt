package com.bitnesttechs.hms.patient.careteam.data

import kotlinx.serialization.Serializable
import retrofit2.http.GET

interface CareTeamApi {

    @GET("me/patient/care-team")
    suspend fun getCareTeam(): List<CareTeamMemberDto>
}

@Serializable
data class CareTeamMemberDto(
    val id: String = "",
    val firstName: String? = null,
    val lastName: String? = null,
    val role: String? = null,
    val specialty: String? = null,
    val department: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val photoUrl: String? = null,
    val hospitalName: String? = null
) {
    val displayName: String
        get() = listOfNotNull(firstName, lastName).joinToString(" ").ifBlank { "Unknown Provider" }

    val initials: String
        get() = "${firstName?.firstOrNull() ?: ""}${lastName?.firstOrNull() ?: ""}".uppercase()
}
