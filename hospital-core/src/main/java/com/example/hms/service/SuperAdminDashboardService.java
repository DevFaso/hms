package com.example.hms.service;

import com.example.hms.payload.dto.AdmissionResponseDTO;
import com.example.hms.payload.dto.EncounterResponseDTO;
import com.example.hms.payload.dto.GeneralReferralResponseDTO;
import com.example.hms.payload.dto.LabOrderResponseDTO;
import com.example.hms.payload.dto.LabResultResponseDTO;
import com.example.hms.payload.dto.LabTestDefinitionResponseDTO;
import com.example.hms.payload.dto.PatientConsentResponseDTO;
import com.example.hms.payload.dto.PrescriptionResponseDTO;
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
}
