package com.bitnesttechs.hms.patient.core.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * What the backend sends on `/me/patient/disclosures` (DisclosureAccountingDTO):
 * per-category counts across the whole window, not just the returned page,
 * plus one page of events, newest first. The web's "Who accessed my record".
 */
@JsonClass(generateAdapter = true)
data class DisclosureAccountingDto(
    @Json(name = "from") val from: String? = null,
    @Json(name = "to") val to: String? = null,
    /** Keyed by DisclosureCategory name; kept as text so a new category never breaks parsing. */
    @Json(name = "countsByCategory") val countsByCategory: Map<String, Long> = emptyMap(),
    @Json(name = "totalEvents") val totalEvents: Long = 0,
    /** Events that left the treating team: another hospital, an insurer, or out as a file. */
    @Json(name = "externalDisclosures") val externalDisclosures: Long = 0,
    @Json(name = "entries") val entries: List<DisclosureEntryDto> = emptyList(),
    @Json(name = "totalPages") val totalPages: Int = 0,
    @Json(name = "page") val page: Int = 0
)

/** One access or disclosure event (AccessLogEntryDTO). */
@JsonClass(generateAdapter = true)
data class DisclosureEntryDto(
    @Json(name = "id") val id: String = "",
    @Json(name = "actor") val actor: String? = null,
    /** May arrive as `ROLE_DOCTOR` or `Doctor`; the screen strips the prefix like the web's bareRole. */
    @Json(name = "actorRole") val actorRole: String? = null,
    @Json(name = "hospitalName") val hospitalName: String? = null,
    @Json(name = "eventType") val eventType: String? = null,
    @Json(name = "entityType") val entityType: String? = null,
    @Json(name = "resourceId") val resourceId: String? = null,
    @Json(name = "description") val description: String? = null,
    @Json(name = "status") val status: String? = null,
    @Json(name = "timestamp") val timestamp: String? = null,
    /** EMERGENCY_ACCESS, TREATMENT_ACCESS, SHARED_WITH_PROVIDER, INSURANCE, COPY_RELEASED, IDENTITY_CHANGE, or null. */
    @Json(name = "category") val category: String? = null,
    @Json(name = "externalDisclosure") val externalDisclosure: Boolean = false
)

/** The patient's cross-hospital record-sharing opt-out (RecordSharingOptOutDTO, bare on the wire). */
@JsonClass(generateAdapter = true)
data class RecordSharingOptOutDto(
    @Json(name = "patientId") val patientId: String? = null,
    @Json(name = "inForce") val inForce: Boolean = false,
    @Json(name = "optedOutAt") val optedOutAt: String? = null,
    @Json(name = "reason") val reason: String? = null,
    @Json(name = "revokedAt") val revokedAt: String? = null
)

/** Body of the opt-out; the reason is optional and at most 1000 characters (server `@Size`). A null reason is omitted, so the body is `{}` as on the web. */
@JsonClass(generateAdapter = true)
data class OptOutRequest(
    @Json(name = "reason") val reason: String? = null
)
