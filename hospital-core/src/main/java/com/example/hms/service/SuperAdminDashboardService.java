package com.example.hms.service;

import com.example.hms.payload.dto.AdmissionResponseDTO;
import com.example.hms.payload.dto.EncounterResponseDTO;
import com.example.hms.payload.dto.GeneralReferralResponseDTO;
import com.example.hms.payload.dto.LabOrderResponseDTO;
import com.example.hms.payload.dto.LabResultResponseDTO;
import com.example.hms.payload.dto.LabTestDefinitionResponseDTO;
import com.example.hms.payload.dto.PatientConsentResponseDTO;
import com.example.hms.payload.dto.PrescriptionResponseDTO;
import com.example.hms.payload.dto.RecentActivityDTO;
import com.example.hms.payload.dto.StaffAvailabilityResponseDTO;
import com.example.hms.payload.dto.SuperAdminSummaryDTO;
import com.example.hms.payload.dto.clinical.treatment.TreatmentPlanResponseDTO;
import com.example.hms.payload.dto.consultation.ConsultationResponseDTO;

import java.util.List;
import java.util.Locale;

public interface SuperAdminDashboardService {
    SuperAdminSummaryDTO getSummary(int recentAuditLimit);

    List<EncounterResponseDTO> getRecentEncounters(int limit, Locale locale);

    List<StaffAvailabilityResponseDTO> getRecentStaffAvailability(int limit);

    List<PatientConsentResponseDTO> getRecentPatientConsents(int limit);

    List<ConsultationResponseDTO> getRecentConsultations(int limit);

    List<LabOrderResponseDTO> getRecentLabOrders(int limit, Locale locale);

    List<LabResultResponseDTO> getRecentLabResults(int limit, Locale locale);

    List<LabTestDefinitionResponseDTO> getRecentLabTestDefinitions(int limit);

    List<AdmissionResponseDTO> getRecentAdmissions(int limit);

    List<PrescriptionResponseDTO> getRecentPrescriptions(int limit, Locale locale);

    List<TreatmentPlanResponseDTO> getRecentTreatmentPlans(int limit);

    List<GeneralReferralResponseDTO> getRecentReferrals(int limit);

    /**
     * Aggregate recent-activity feed: composes the nine per-resource
     * {@code getRecent*} feeds into a single {@link RecentActivityDTO}
     * so the super-admin dashboard can collapse its eight-subscription
     * fan-out (current behaviour after commit {@code f7e5a973}'s
     * streaming refactor) into a single round-trip — F5 from
     * {@code docs/super-admin-cross-tenant-design.md}.
     *
     * @param limit  per-feed row cap (each list independently bounded;
     *               server clamps to the same limit it would apply on
     *               the per-feed endpoint).
     * @param locale request {@code Accept-Language} forwarded to feeds
     *               that surface message-source-driven fields
     *               (lab orders / results / encounters / prescriptions).
     */
    RecentActivityDTO getRecentActivity(int limit, Locale locale);
}
