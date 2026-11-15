package com.example.hms.service.support;

import com.example.hms.payload.dto.GeneralReferralResponseDTO;
import com.example.hms.payload.dto.PatientConsentResponseDTO;
import com.example.hms.payload.dto.consultation.ConsultationResponseDTO;
import com.example.hms.payload.dto.imaging.ImagingOrderResponseDTO;
import com.example.hms.payload.dto.imaging.ImagingReportResponseDTO;
import com.example.hms.payload.dto.procedure.ProcedureOrderResponseDTO;
import com.example.hms.payload.dto.ultrasound.UltrasoundOrderResponseDTO;
import com.example.hms.payload.dto.ultrasound.UltrasoundReportResponseDTO;

/**
 * QA D1960: what a patient, or a proxy reading for one, receives of an order-type record. The staff
 * DTOs carry the care team's working text (workflow and scheduling notes, the pre-authorisation, why
 * a duplicate warning was overridden, contrast-allergy details, why an order was cancelled or a
 * consult declined, review notes), and a patient read used to send them whole.
 *
 * <p>The orders a patient attends (imaging, procedure, ultrasound) are rebuilt from a list of the
 * fields the patient needs: the study, the body part, the status, when and where to come, who
 * ordered it. A field added to the staff DTO later stays off the patient's copy until someone adds
 * it here. The records the patient portal already shows field by field (referrals, consultations,
 * consents) lose their staff-only fields instead, so the portal keeps what it reads.
 *
 * <p>{@code PatientReachableStaffFieldGuardTest} fills every field of every view's input and fails
 * when a staff-only field comes out, and fails when a patient-reachable endpoint returns a type with a
 * staff-only field that is neither projected here nor listed as the patient's by design.
 */
public final class PatientSafeView {

    private PatientSafeView() {
    }

    /** An imaging order as its patient reads it: the study and the appointment, no working notes. */
    public static ImagingOrderResponseDTO imagingOrder(ImagingOrderResponseDTO staff) {
        if (staff == null) {
            return null;
        }
        return ImagingOrderResponseDTO.builder()
            .id(staff.getId())
            .patientId(staff.getPatientId())
            .patientDisplayName(staff.getPatientDisplayName())
            .patientMrn(staff.getPatientMrn())
            .hospitalId(staff.getHospitalId())
            .hospitalName(staff.getHospitalName())
            .modality(staff.getModality())
            .studyType(staff.getStudyType())
            .bodyRegion(staff.getBodyRegion())
            .laterality(staff.getLaterality())
            .status(staff.getStatus())
            .scheduledDate(staff.getScheduledDate())
            .scheduledTime(staff.getScheduledTime())
            .appointmentLocation(staff.getAppointmentLocation())
            .orderedAt(staff.getOrderedAt())
            .orderingProviderName(staff.getOrderingProviderName())
            .encounterId(staff.getEncounterId())
            .cancelledAt(staff.getCancelledAt())
            .completedAt(staff.getCompletedAt())
            .updatedAt(staff.getUpdatedAt())
            .build();
    }

    /**
     * A released imaging report as its patient reads it: the read itself (technique, findings,
     * impression, recommendations) and who signed it, without the status trail, the lock, the
     * signature value, the critical-result escalation or the measurement and attachment working set.
     */
    public static ImagingReportResponseDTO imagingReport(ImagingReportResponseDTO staff) {
        if (staff == null) {
            return null;
        }
        return ImagingReportResponseDTO.builder()
            .id(staff.getId())
            .imagingOrderId(staff.getImagingOrderId())
            .patientId(staff.getPatientId())
            .patientDisplayName(staff.getPatientDisplayName())
            .patientMrn(staff.getPatientMrn())
            .hospitalId(staff.getHospitalId())
            .reportNumber(staff.getReportNumber())
            .reportStatus(staff.getReportStatus())
            .reportVersion(staff.getReportVersion())
            .latestVersion(staff.getLatestVersion())
            .modality(staff.getModality())
            .bodyRegion(staff.getBodyRegion())
            .reportTitle(staff.getReportTitle())
            .interpretingProviderName(staff.getInterpretingProviderName())
            .signedByName(staff.getSignedByName())
            .performedAt(staff.getPerformedAt())
            .completedAt(staff.getCompletedAt())
            .signedAt(staff.getSignedAt())
            .technique(staff.getTechnique())
            .findings(staff.getFindings())
            .impression(staff.getImpression())
            .recommendations(staff.getRecommendations())
            .comparisonStudies(staff.getComparisonStudies())
            .contrastAdministered(staff.getContrastAdministered())
            .signed(staff.getSigned())
            .updatedAt(staff.getUpdatedAt())
            .build();
    }

    /** A procedure order as its patient reads it: the procedure and the appointment, no clinical notes. */
    public static ProcedureOrderResponseDTO procedureOrder(ProcedureOrderResponseDTO staff) {
        if (staff == null) {
            return null;
        }
        return ProcedureOrderResponseDTO.builder()
            .id(staff.getId())
            .patientId(staff.getPatientId())
            .patientName(staff.getPatientName())
            .patientMrn(staff.getPatientMrn())
            .hospitalId(staff.getHospitalId())
            .hospitalName(staff.getHospitalName())
            .encounterId(staff.getEncounterId())
            .procedureCode(staff.getProcedureCode())
            .procedureName(staff.getProcedureName())
            .procedureCategory(staff.getProcedureCategory())
            .laterality(staff.getLaterality())
            .scheduledDatetime(staff.getScheduledDatetime())
            .estimatedDurationMinutes(staff.getEstimatedDurationMinutes())
            .status(staff.getStatus())
            .orderedAt(staff.getOrderedAt())
            .orderingProviderName(staff.getOrderingProviderName())
            .cancelledAt(staff.getCancelledAt())
            .completedAt(staff.getCompletedAt())
            .createdAt(staff.getCreatedAt())
            .updatedAt(staff.getUpdatedAt())
            .build();
    }

    /**
     * An ultrasound order as its patient reads it: the scan and the appointment, and its report
     * through {@link #ultrasoundReport} (the caller has already left out a report not released).
     */
    public static UltrasoundOrderResponseDTO ultrasoundOrder(UltrasoundOrderResponseDTO staff) {
        if (staff == null) {
            return null;
        }
        return UltrasoundOrderResponseDTO.builder()
            .id(staff.getId())
            .patientId(staff.getPatientId())
            .patientDisplayName(staff.getPatientDisplayName())
            .patientMrn(staff.getPatientMrn())
            .hospitalId(staff.getHospitalId())
            .hospitalName(staff.getHospitalName())
            .scanType(staff.getScanType())
            .status(staff.getStatus())
            .orderedDate(staff.getOrderedDate())
            .orderedBy(staff.getOrderedBy())
            .scheduledDate(staff.getScheduledDate())
            .scheduledTime(staff.getScheduledTime())
            .appointmentLocation(staff.getAppointmentLocation())
            .report(ultrasoundReport(staff.getReport()))
            .cancelledAt(staff.getCancelledAt())
            .createdAt(staff.getCreatedAt())
            .updatedAt(staff.getUpdatedAt())
            .build();
    }

    /** A released ultrasound report as its patient reads it: the provider's review notes come off. */
    public static UltrasoundReportResponseDTO ultrasoundReport(UltrasoundReportResponseDTO staff) {
        if (staff != null) {
            staff.setProviderReviewNotes(null);
        }
        return staff;
    }

    /**
     * A referral as its patient reads it: the receiving clinician's acknowledgement note, why it was
     * cancelled, the insurer's authorisation number, the triage score and the integration metadata
     * come off. The reason for the referral and the follow-up advice are the patient's.
     */
    public static GeneralReferralResponseDTO referral(GeneralReferralResponseDTO staff) {
        if (staff != null) {
            staff.setAcknowledgementNotes(null);
            staff.setCancellationReason(null);
            staff.setInsuranceAuthNumber(null);
            staff.setPriorityScore(null);
            staff.setMetadata(null);
        }
        return staff;
    }

    /**
     * A consultation as its patient reads it: why the consultant declined it or why it was cancelled
     * is between the clinicians. The consultant's text before completion is withheld separately
     * ({@code ConsultationServiceImpl.asReadByPatient}, D1910).
     */
    public static ConsultationResponseDTO consultation(ConsultationResponseDTO staff) {
        if (staff != null) {
            staff.setDeclineReason(null);
            staff.setCancellationReason(null);
        }
        return staff;
    }

    /** A consent as its patient reads it: the embedded chart loses the care team's notes and why it is restricted. */
    public static PatientConsentResponseDTO consent(PatientConsentResponseDTO staff) {
        if (staff != null && staff.getPatient() != null) {
            staff.getPatient().setCareTeamNotes(null);
            staff.getPatient().setChartRestrictionReason(null);
        }
        return staff;
    }
}
