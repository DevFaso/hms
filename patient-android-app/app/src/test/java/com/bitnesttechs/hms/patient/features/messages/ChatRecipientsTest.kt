package com.bitnesttechs.hms.patient.features.messages

import com.bitnesttechs.hms.patient.core.auth.TokenStorage
import com.bitnesttechs.hms.patient.core.models.AppointmentDto
import com.bitnesttechs.hms.patient.core.models.CareTeamDto
import com.bitnesttechs.hms.patient.core.models.CareTeamMemberDto
import com.bitnesttechs.hms.patient.core.network.ApiResponse
import com.bitnesttechs.hms.patient.core.network.ApiService
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.IOException

/**
 * The picker sent a care-team entry's LINK id (`PrimaryCareEntry.id`) as the
 * chat recipient; it must be the clinician's user id, `doctorUserId`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatRecipientsTest {

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val me = "patient-user"

    private fun entry(linkId: String, userId: String?, name: String = "Dr $linkId") =
        CareTeamMemberDto(id = linkId, doctorUserId = userId, doctorDisplay = name, hospitalName = "CHU")

    @Test
    fun `a care-team entry is sent to doctorUserId, not to its link id`() {
        val team = CareTeamDto(primaryPhysician = entry("link-1", "doctor-user-1"))
        val recipients = ChatRecipients.merge(team, null, me)
        assertEquals(listOf("doctor-user-1"), recipients.map { it.userId })
        assertEquals("Dr link-1", recipients.single().name)
    }

    @Test
    fun `the backend's care-team JSON decodes doctorUserId`() {
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val dto = moshi.adapter(CareTeamDto::class.java).fromJson(
            """{"primaryCare":{"id":"link-1","doctorUserId":"doctor-user-1","doctorDisplay":"Dr Sawadogo","current":true},
               "primaryCareHistory":[{"id":"link-0","doctorUserId":null,"doctorDisplay":"Dr Old"}]}"""
        )!!
        assertEquals(listOf("doctor-user-1"), ChatRecipients.merge(dto, null, me).map { it.userId })
    }

    @Test
    fun `history and appointments join, deduplicated by user id, never the patient, nulls skipped`() {
        val team = CareTeamDto(
            primaryPhysician = entry("link-1", "doc-A"),
            members = listOf(entry("link-2", "doc-B"), entry("link-3", null), entry("link-4", "doc-A"))
        )
        val appointments = listOf(
            AppointmentDto(staffUserId = "doc-B", staffName = "Dr B"),
            AppointmentDto(staffUserId = "doc-C", staffName = "Dr C"),
            AppointmentDto(staffUserId = me, staffName = "Me"),
            AppointmentDto(staffUserId = null, staffName = "Nobody")
        )
        assertEquals(listOf("doc-A", "doc-B", "doc-C"), ChatRecipients.merge(team, appointments, me).map { it.userId })
    }

    @Test
    fun `each source fails on its own`() = runTest {
        val api = mockk<ApiService>()
        val tokenStorage = mockk<TokenStorage> { every { userId } returns me }
        coEvery { api.getCareTeam() } throws IOException("offline")
        coEvery { api.getAppointments(any(), any()) } returns Response.success(
            ApiResponse(success = true, data = listOf(AppointmentDto(staffUserId = "doc-C", staffName = "Dr C")))
        )
        val vm = MessagesViewModel(api, tokenStorage, mockk(relaxed = true))
        vm.loadCareTeam()
        assertEquals(listOf("doc-C"), vm.careTeamMembers.value.map { it.userId })
    }
}
