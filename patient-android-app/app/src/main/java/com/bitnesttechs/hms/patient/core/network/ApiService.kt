package com.bitnesttechs.hms.patient.core.network

import com.bitnesttechs.hms.patient.core.models.*
import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.*

interface ApiService {

    // ── Auth ──────────────────────────────────────────────────────────────────
    /** Login returns flat LoginResponse — NOT wrapped in ApiResponse. */
    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): Response<LoginResponse>

    @POST("auth/token/refresh")
    suspend fun refreshToken(@Body request: RefreshTokenRequest): Response<LoginResponse>

    @POST("auth/logout")
    suspend fun logout(): Response<ApiResponse<Unit>>

    // ── Patient Profile ───────────────────────────────────────────────────────
    @GET("me/patient/profile")
    suspend fun getProfile(): Response<ApiResponse<PatientProfileDto>>

    @PUT("me/patient/profile")
    suspend fun updateProfile(@Body update: PatientProfileUpdateDto): Response<ApiResponse<PatientProfileDto>>

    @Multipart
    @POST("files/profile-image")
    suspend fun uploadProfileImage(
        @Part file: MultipartBody.Part
    ): Response<ProfileImageResponse>

    @GET("me/patient/health-summary")
    suspend fun getHealthSummary(): Response<ApiResponse<HealthSummaryDto>>

    // ── Appointments ──────────────────────────────────────────────────────────
    /** Patient appointments — API returns list, not paginated */
    @GET("me/patient/appointments")
    suspend fun getAppointments(
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 50
    ): Response<ApiResponse<List<AppointmentDto>>>

    /** Book appointment — POST /appointments returns flat AppointmentDto (201) */
    @POST("appointments")
    suspend fun bookAppointment(@Body request: BookAppointmentRequest): Response<AppointmentDto>

    @PUT("me/patient/appointments/{id}/cancel")
    suspend fun cancelAppointment(
        @Path("id") id: String,
        @Body request: CancelAppointmentRequest
    ): Response<ApiResponse<AppointmentDto>>

    @PUT("me/patient/appointments/{id}/reschedule")
    suspend fun rescheduleAppointment(
        @Path("id") id: String,
        @Body request: RescheduleAppointmentRequest
    ): Response<ApiResponse<AppointmentDto>>

    // ── Lab Results ───────────────────────────────────────────────────────────
    @GET("me/patient/lab-results")
    suspend fun getLabResults(
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 20
    ): Response<ApiResponse<List<LabResultDto>>>

    @GET("me/patient/lab-results/{id}")
    suspend fun getLabResult(@Path("id") id: String): Response<ApiResponse<LabResultDto>>

    // ── Medications ───────────────────────────────────────────────────────────
    @GET("me/patient/medications")
    suspend fun getMedications(): Response<ApiResponse<List<MedicationDto>>>

    @GET("me/patient/prescriptions")
    suspend fun getPrescriptions(
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 20
    ): Response<ApiResponse<List<PrescriptionDto>>>

    @POST("me/patient/refills")
    suspend fun requestRefill(
        @Body request: RefillRequest
    ): Response<ApiResponse<RefillDto>>

    // ── Billing ───────────────────────────────────────────────────────────────
    @GET("me/patient/billing/invoices")
    suspend fun getInvoices(
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 20
    ): Response<ApiResponse<List<InvoiceDto>>>

    @GET("me/patient/billing/invoices/{id}")
    suspend fun getInvoice(@Path("id") id: String): Response<ApiResponse<InvoiceDto>>

    // ── Vitals ────────────────────────────────────────────────────────────────
    @GET("me/patient/vitals")
    suspend fun getVitals(
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 20
    ): Response<ApiResponse<List<VitalSignDto>>>

    @POST("me/patient/vitals")
    suspend fun recordVital(@Body request: RecordVitalRequest): Response<ApiResponse<VitalSignDto>>

    // ── Care Team ─────────────────────────────────────────────────────────────
    @GET("me/patient/care-team")
    suspend fun getCareTeam(): Response<ApiResponse<CareTeamDto>>

    // ── Encounters / Visits ───────────────────────────────────────────────────
    @GET("me/patient/encounters")
    suspend fun getEncounters(
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 20
    ): Response<ApiResponse<List<EncounterDto>>>

    @GET("me/patient/after-visit-summaries")
    suspend fun getAfterVisitSummaries(
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 20
    ): Response<ApiResponse<List<DischargeSummaryDto>>>

    // ── Documents ─────────────────────────────────────────────────────────────
    @GET("me/patient/documents")
    suspend fun getDocuments(
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 20
    ): Response<ApiResponse<List<DocumentDto>>>

    // ── Health Records ────────────────────────────────────────────────────────
    @GET("me/patient/immunizations")
    suspend fun getImmunizations(): Response<ApiResponse<List<ImmunizationDto>>>

    @GET("me/patient/referrals")
    suspend fun getReferrals(
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 20
    ): Response<ApiResponse<List<ReferralDto>>>

    @GET("me/patient/treatment-plans")
    suspend fun getTreatmentPlans(): Response<ApiResponse<List<TreatmentPlanDto>>>

    // ── Notifications ─────────────────────────────────────────────────────────
    @GET("notifications")
    suspend fun getNotifications(
        @Query("read") read: Boolean? = null,
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 20
    ): Response<PageDto<NotificationDto>>

    @POST("notifications/{id}/read")
    suspend fun markNotificationRead(@Path("id") id: String): Response<Unit>

    // ── Chat / Messages ───────────────────────────────────────────────────────
    @GET("chat/conversations/{userId}")
    suspend fun getChatConversations(
        @Path("userId") userId: String,
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 20
    ): Response<List<ChatConversationDto>>

    @GET("chat/history/{user1Id}/{user2Id}")
    suspend fun getChatHistory(
        @Path("user1Id") user1Id: String,
        @Path("user2Id") user2Id: String,
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 50
    ): Response<List<ChatMessageDto>>

    @POST("chat/send")
    suspend fun sendChatMessage(
        @Body request: SendChatMessageRequest
    ): Response<ChatMessageDto>

    // ── Consents / Privacy ────────────────────────────────────────────────────
    @GET("me/patient/consents")
    suspend fun getConsents(): Response<ApiResponse<List<ConsentDto>>>

    @POST("me/patient/consents/{id}/grant")
    suspend fun grantConsent(
        @Path("id") id: String,
        @Body request: GrantConsentRequest
    ): Response<ApiResponse<ConsentDto>>

    @POST("me/patient/consents/{id}/revoke")
    suspend fun revokeConsent(@Path("id") id: String): Response<ApiResponse<ConsentDto>>

    @GET("me/patient/access-log")
    suspend fun getAccessLog(
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 20
    ): Response<ApiResponse<List<AccessLogDto>>>

    // ── Proxy / Family Access ─────────────────────────────────────────────────
    @GET("me/patient/proxies")
    suspend fun getProxiesGrantedByMe(): Response<ApiResponse<List<ProxyResponse>>>

    @GET("me/patient/proxy-access")
    suspend fun getProxyAccessIHave(): Response<ApiResponse<List<ProxyResponse>>>

    @POST("me/patient/proxies")
    suspend fun grantProxy(@Body request: GrantProxyRequest): Response<ApiResponse<ProxyResponse>>

    @DELETE("me/patient/proxies/{id}")
    suspend fun revokeProxy(@Path("id") id: String): Response<ApiResponse<Unit>>

    // ── Proxy data-viewing ────────────────────────────────────────────────────
    @GET("me/patient/proxy-access/{patientId}/appointments")
    suspend fun getProxyAppointments(@Path("patientId") patientId: String): Response<ApiResponse<List<AppointmentDto>>>

    @GET("me/patient/proxy-access/{patientId}/medications")
    suspend fun getProxyMedications(@Path("patientId") patientId: String, @Query("limit") limit: Int = 50): Response<ApiResponse<List<MedicationDto>>>

    @GET("me/patient/proxy-access/{patientId}/lab-results")
    suspend fun getProxyLabResults(@Path("patientId") patientId: String, @Query("limit") limit: Int = 50): Response<ApiResponse<List<LabResultDto>>>

    @GET("me/patient/proxy-access/{patientId}/billing")
    suspend fun getProxyBilling(@Path("patientId") patientId: String): Response<ApiResponse<PageDto<InvoiceDto>>>

    @GET("me/patient/proxy-access/{patientId}/records")
    suspend fun getProxyRecords(@Path("patientId") patientId: String): Response<ApiResponse<HealthSummaryDto>>
}
