package com.bitnesttechs.hms.patient.core.network

import com.bitnesttechs.hms.patient.core.models.*
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
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

    // ── Medical & family history (read-only for the patient, like the web) ──
    @GET("me/patient/medical-history")
    suspend fun getMyMedicalHistory(): Response<ApiResponse<List<PatientDiagnosisSummary>>>

    @GET("me/patient/surgical-history")
    suspend fun getMySurgicalHistory(): Response<ApiResponse<List<SurgicalHistoryEntry>>>

    @GET("me/patient/family-history")
    suspend fun getMyFamilyHistory(): Response<ApiResponse<List<FamilyHistoryEntry>>>

    /** `data` is null when no active social history is on record. */
    @GET("me/patient/social-history")
    suspend fun getMySocialHistory(): Response<ApiResponse<SocialHistory>>

    // ── Patient education, the web's My Education ────────────────────────────
    @GET("me/patient/education")
    suspend fun getMyEducation(): Response<ApiResponse<List<EducationItemDto>>>

    @PUT("me/patient/education/{resourceId}/progress")
    suspend fun updateEducationProgress(
        @Path("resourceId") resourceId: String,
        @Body update: EducationProgressUpdate
    ): Response<ApiResponse<EducationItemDto>>

    @GET("me/patient/education/questions")
    suspend fun getEducationQuestions(): Response<ApiResponse<List<EducationQuestionDto>>>

    @POST("me/patient/education/questions")
    suspend fun submitEducationQuestion(@Body request: EducationQuestionSubmit): Response<ApiResponse<EducationQuestionDto>>

    // ── PRO self-screenings (unwrapped DTOs, like the web's ProScreeningService) ──
    @GET("me/patient/pro-screenings")
    suspend fun getMyScreenings(): Response<ProSelfReport>

    @GET("me/patient/pro-instruments/{code}")
    suspend fun getScreeningInstrument(
        @Path("code") code: String,
        @Query("language") language: String?
    ): Response<ProInstrumentView>

    @POST("me/patient/pro-screenings")
    suspend fun submitScreening(@Body request: ProResponseCreate): Response<ProScreeningEntry>

    // ── Appointments ──────────────────────────────────────────────────────────
    /** Patient appointments — API returns list, not paginated */
    @GET("me/patient/appointments")
    suspend fun getAppointments(
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 50
    ): Response<ApiResponse<List<AppointmentDto>>>

    /**
     * Self-scheduling goes through the patient portal endpoint, which verifies
     * the registration and the hospital/department pairing; the staff endpoint
     * (POST /appointments) skips both.
     */
    @POST("me/patient/appointments")
    suspend fun bookAppointment(@Body request: BookAppointmentRequest): Response<ApiResponse<AppointmentDto>>

    // The booking wizard's three lists, same as the web's.
    @GET("me/patient/booking/hospitals")
    suspend fun getBookingHospitals(): Response<ApiResponse<List<BookingHospitalDto>>>

    @GET("me/patient/booking/hospitals/{hospitalId}/departments")
    suspend fun getBookingDepartments(
        @Path("hospitalId") hospitalId: String
    ): Response<ApiResponse<List<BookingDepartmentDto>>>

    @GET("me/patient/booking/hospitals/{hospitalId}/departments/{departmentId}/providers")
    suspend fun getBookingProviders(
        @Path("hospitalId") hospitalId: String,
        @Path("departmentId") departmentId: String
    ): Response<ApiResponse<List<BookingProviderDto>>>

    // Pre-check-in, same two calls as the web's form.
    @GET("me/patient/appointments/{appointmentId}/questionnaires")
    suspend fun getAppointmentQuestionnaires(
        @Path("appointmentId") appointmentId: String
    ): Response<ApiResponse<List<QuestionnaireDto>>>

    @POST("me/patient/appointments/{appointmentId}/pre-checkin")
    suspend fun submitPreCheckIn(
        @Path("appointmentId") appointmentId: String,
        @Body request: PreCheckInRequest
    ): Response<ApiResponse<PreCheckInResponse>>

    @PUT("me/patient/appointments/cancel")
    suspend fun cancelAppointment(
        @Body request: CancelAppointmentRequest
    ): Response<ApiResponse<AppointmentDto>>

    @PUT("me/patient/appointments/reschedule")
    suspend fun rescheduleAppointment(
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

    @GET("me/patient/refills")
    suspend fun getRefills(
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 50
    ): Response<ApiResponse<PageDto<RefillDto>>>

    @PUT("me/patient/refills/{refillId}/cancel")
    suspend fun cancelRefill(@Path("refillId") refillId: String): Response<ApiResponse<RefillDto>>

    // ── Billing ───────────────────────────────────────────────────────────────
    @GET("me/patient/billing/invoices")
    suspend fun getInvoices(
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 20
    ): Response<ApiResponse<List<InvoiceDto>>>

    @GET("me/patient/billing/invoices/{id}")
    suspend fun getInvoice(@Path("id") id: String): Response<ApiResponse<InvoiceDto>>

    @POST("me/patient/billing/invoices/{invoiceId}/pay")
    suspend fun payInvoice(
        @Path("invoiceId") invoiceId: String,
        @Body request: PatientPaymentRequest
    ): Response<ApiResponse<InvoiceDto>>

    @GET("me/patient/pharmacy/payments")
    suspend fun getPharmacyPayments(
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 50
    ): Response<ApiResponse<PageDto<PharmacyPaymentDto>>>

    @GET("me/patient/pharmacy/claims")
    suspend fun getPharmacyClaims(
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 50
    ): Response<ApiResponse<PageDto<PharmacyClaimDto>>>

    // ── Vitals ────────────────────────────────────────────────────────────────
    @GET("me/patient/vitals")
    suspend fun getVitals(
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 20
    ): Response<ApiResponse<List<VitalSignDto>>>

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
    /** The backend returns a Spring Page (an object with `content`), never a bare list. */
    @GET("me/patient/documents")
    suspend fun getDocuments(
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 50,
        @Query("sort") sort: String = "createdAt,desc"
    ): Response<ApiResponse<PageDto<DocumentDto>>>

    /**
     * Multipart like the web's FormData: `file` + `documentType` are required
     * parts, `collectionDate` (ISO date) and `notes` are sent only when set.
     */
    @Multipart
    @POST("me/patient/documents")
    suspend fun uploadDocument(
        @Part file: MultipartBody.Part,
        @Part("documentType") documentType: RequestBody,
        @Part("collectionDate") collectionDate: RequestBody?,
        @Part("notes") notes: RequestBody?
    ): Response<ApiResponse<DocumentDto>>

    /** Soft delete; the backend only checks that the document belongs to the caller. */
    @DELETE("me/patient/documents/{documentId}")
    suspend fun deleteDocument(@Path("documentId") documentId: String): Response<ApiResponse<Unit>>

    /**
     * Document bytes have no public URL: the backend streams them only to
     * their owner, so the download goes through the authenticated client
     * and is handed to a viewer from the app's cache.
     */
    @Streaming
    @GET("me/patient/documents/{documentId}/download")
    suspend fun downloadDocument(@Path("documentId") documentId: String): Response<ResponseBody>

    // ── Health Records ────────────────────────────────────────────────────────
    @GET("me/patient/immunizations")
    suspend fun getImmunizations(): Response<ApiResponse<List<ImmunizationDto>>>

    @GET("me/patient/referrals")
    suspend fun getReferrals(
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 20
    ): Response<ApiResponse<List<ReferralDto>>>

    @GET("me/patient/treatment-plans")
    suspend fun getTreatmentPlans(
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 20
    ): Response<ApiResponse<PageDto<TreatmentPlanDto>>>

    // ── Notifications ─────────────────────────────────────────────────────────
    @GET("me/patient/notifications")
    suspend fun getNotifications(
        @Query("read") read: Boolean? = null,
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 20
    ): Response<ApiResponse<PageDto<NotificationDto>>>

    @GET("me/patient/notifications/unread-count")
    suspend fun getUnreadNotificationCount(): Response<ApiResponse<Map<String, Long>>>

    @PUT("me/patient/notifications/{id}/read")
    suspend fun markNotificationRead(@Path("id") id: String): Response<ApiResponse<Unit>>

    @PUT("me/patient/notifications/read-all")
    suspend fun markAllNotificationsRead(): Response<ApiResponse<Map<String, Int>>>

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
    suspend fun getConsents(
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 50
    ): Response<ApiResponse<PageDto<ConsentDto>>>

    @POST("me/patient/consents/{id}/grant")
    suspend fun grantConsent(
        @Path("id") id: String,
        @Body request: GrantConsentRequest
    ): Response<ApiResponse<ConsentDto>>

    @DELETE("me/patient/consents")
    suspend fun revokeConsent(
        @Query("fromHospitalId") fromHospitalId: String,
        @Query("toHospitalId") toHospitalId: String
    ): Response<ApiResponse<Unit>>

    @GET("me/patient/access-log")
    suspend fun getAccessLog(
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 20
    ): Response<ApiResponse<PageDto<AccessLogDto>>>

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
