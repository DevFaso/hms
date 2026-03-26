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
    @POST("files/upload")
    suspend fun uploadProfileImage(
        @Part file: MultipartBody.Part,
        @Part("entityType") entityType: okhttp3.RequestBody,
        @Part("entityId") entityId: okhttp3.RequestBody
    ): Response<ApiResponse<Any>>

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
    @GET("me/notifications")
    suspend fun getNotifications(
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 20
    ): Response<ApiResponse<List<NotificationDto>>>

    @PUT("me/notifications/{id}/read")
    suspend fun markNotificationRead(@Path("id") id: String): Response<ApiResponse<Unit>>

    @PUT("me/notifications/read-all")
    suspend fun markAllNotificationsRead(): Response<ApiResponse<Unit>>

    // ── Chat / Messages ───────────────────────────────────────────────────────
    @GET("me/chat/threads")
    suspend fun getChatThreads(): Response<ApiResponse<List<ChatThreadDto>>>

    @GET("me/chat/threads/{threadId}/messages")
    suspend fun getMessages(
        @Path("threadId") threadId: String,
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 50
    ): Response<ApiResponse<List<ChatMessageDto>>>

    @POST("me/chat/threads/{threadId}/messages")
    suspend fun sendMessage(
        @Path("threadId") threadId: String,
        @Body request: SendMessageRequest
    ): Response<ApiResponse<ChatMessageDto>>

    // ── Consents / Privacy ────────────────────────────────────────────────────
    @GET("me/patient/consents")
    suspend fun getConsents(): Response<ApiResponse<List<ConsentDto>>>

    @POST("me/patient/consents/{id}/grant")
    suspend fun grantConsent(
        @Path("id") id: String,
        @Body request: GrantConsentRequest
    ): Response<ApiResponse<ConsentDto>>

    @GET("me/patient/access-log")
    suspend fun getAccessLog(
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 20
    ): Response<ApiResponse<List<AccessLogDto>>>
}
