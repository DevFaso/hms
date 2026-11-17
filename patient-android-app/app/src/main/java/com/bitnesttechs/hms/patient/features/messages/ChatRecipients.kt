package com.bitnesttechs.hms.patient.features.messages

import com.bitnesttechs.hms.patient.core.models.AppointmentDto
import com.bitnesttechs.hms.patient.core.models.CareTeamDto

/**
 * Someone the patient can start a conversation with. [userId] is the HMS
 * `users.id` that `POST /chat/send` takes as `recipientId`.
 */
data class ChatRecipient(val userId: String, val name: String, val hospitalName: String?)

/**
 * The web portal's patient picker (chat.ts / patient-portal.service.ts):
 * the care team (current primary care plus its history) and the clinicians
 * of the patient's appointments, deduplicated by user id, never the patient.
 *
 * A care-team entry's `id` is the primary-care LINK id, not a user: messages
 * sent to it went nowhere. The recipient is its `doctorUserId`, and an entry
 * without one is skipped. Either source may be null (its request failed);
 * the other still counts.
 */
object ChatRecipients {

    fun merge(careTeam: CareTeamDto?, appointments: List<AppointmentDto>?, selfUserId: String?): List<ChatRecipient> {
        val fromCareTeam = listOfNotNull(careTeam?.primaryPhysician) + careTeam?.members.orEmpty()
        val candidates = fromCareTeam.map { entry ->
            ChatRecipient(
                userId = entry.doctorUserId.orEmpty().trim(),
                name = entry.doctorDisplay?.takeIf { it.isNotBlank() } ?: entry.name,
                hospitalName = entry.hospitalName
            )
        } + appointments.orEmpty().map { appt ->
            ChatRecipient(
                userId = appt.staffUserId.orEmpty().trim(),
                name = appt.staffName.orEmpty(),
                hospitalName = appt.hospitalName
            )
        }
        val seen = mutableSetOf<String>()
        selfUserId?.takeIf { it.isNotBlank() }?.let { seen += it }
        return candidates.filter { it.userId.isNotEmpty() && seen.add(it.userId) }
    }
}
