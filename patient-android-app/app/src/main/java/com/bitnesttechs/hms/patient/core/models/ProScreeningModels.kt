package com.bitnesttechs.hms.patient.core.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * GET /me/patient/pro-screenings (ProSelfReportDTO, NOT wrapped). What is
 * open to the patient right now (while a postpartum plan is active) and what
 * they answered before. Carries no score by design: the care team follows up.
 */
@JsonClass(generateAdapter = true)
data class ProSelfReport(
    @Json(name = "available") val available: List<ProScreeningAvailable> = emptyList(),
    @Json(name = "history") val history: List<ProScreeningEntry> = emptyList()
)

@JsonClass(generateAdapter = true)
data class ProScreeningAvailable(
    @Json(name = "code") val code: String,
    @Json(name = "name") val name: String? = null,
    @Json(name = "languages") val languages: List<String> = emptyList()
)

@JsonClass(generateAdapter = true)
data class ProScreeningEntry(
    @Json(name = "id") val id: String,
    @Json(name = "instrumentCode") val instrumentCode: String? = null,
    @Json(name = "instrumentName") val instrumentName: String? = null,
    @Json(name = "administeredAt") val administeredAt: String? = null,
    @Json(name = "followUpPlanned") val followUpPlanned: Boolean = false,
    @Json(name = "careTeamAlerted") val careTeamAlerted: Boolean = false
)

/** GET /me/patient/pro-instruments/{code}?language= (ProInstrumentViewDTO, NOT wrapped). Items and options, no scores. */
@JsonClass(generateAdapter = true)
data class ProInstrumentView(
    @Json(name = "code") val code: String,
    @Json(name = "name") val name: String? = null,
    @Json(name = "version") val version: String? = null,
    @Json(name = "sourceCitation") val sourceCitation: String? = null,
    @Json(name = "licenceNote") val licenceNote: String? = null,
    @Json(name = "language") val language: String? = null,
    @Json(name = "availableLanguages") val availableLanguages: List<String> = emptyList(),
    @Json(name = "instruction") val instruction: String? = null,
    @Json(name = "items") val items: List<ProInstrumentItem> = emptyList()
)

@JsonClass(generateAdapter = true)
data class ProInstrumentItem(
    @Json(name = "itemNo") val itemNo: Int,
    @Json(name = "prompt") val prompt: String? = null,
    @Json(name = "options") val options: List<ProInstrumentOption> = emptyList()
)

@JsonClass(generateAdapter = true)
data class ProInstrumentOption(
    @Json(name = "optionNo") val optionNo: Int,
    @Json(name = "label") val label: String? = null
)

/**
 * POST /me/patient/pro-screenings (ProResponseCreateDTO). `answers` is
 * itemNo -> optionNo; the keys travel as strings, which Jackson binds to the
 * DTO's Map<Integer, Integer>. Hospital and time are pinned server-side.
 */
@JsonClass(generateAdapter = true)
data class ProResponseCreate(
    @Json(name = "instrumentCode") val instrumentCode: String,
    @Json(name = "language") val language: String? = null,
    @Json(name = "answers") val answers: Map<String, Int>
)
