package com.example.hms.architecture;

import com.example.hms.testsupport.GuardAllowlist;
import jakarta.persistence.Column;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1050: a specimen type over 50 characters failed the collection with a
 * generic "could not save" and the specimen was lost: the column is
 * VARCHAR(50) and {@code LabSpecimenRequestDTO.specimenType} had no
 * {@code @Size}, so the database, not the request, refused it.
 *
 * <p>The class: a request DTO's text field written into an entity column of a
 * fixed length, with no {@code @Size} that keeps it within that length. This
 * reads every request DTO ({@code *RequestDTO} / {@code *Request} in
 * {@code payload.dto}) whose name names an entity ({@code LabSpecimenRequestDTO}
 * names {@code LabSpecimen}) and, for each String field the entity has under
 * the same name with a {@code @Column(length = N)}, requires a {@code @Size}
 * whose max is at most N. The fields that break it today are pinned below,
 * each with its count; a new one fails, and a fixed one fails until its pin is
 * deleted.
 */
class RequestFieldColumnLengthGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");

    /** DTO#field, how many (always 1), and why it is excused today. */
    private static final List<Object> PINS = List.of(
        "AdmissionOrderSetRequestDTO#clinicalGuidelines", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "AdmissionOrderSetRequestDTO#description", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "AdmissionOrderSetRequestDTO#name", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "AdmissionRequestDTO#admissionNotes", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "AdmissionRequestDTO#admissionSource", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "AdmissionRequestDTO#chiefComplaint", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "AdmissionRequestDTO#insuranceAuthNumber", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "AdmissionRequestDTO#primaryDiagnosisCode", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "AdmissionRequestDTO#primaryDiagnosisDescription", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "AdmissionRequestDTO#roomBed", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "AdvanceDirectiveRequestDTO#description", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "AppointmentRequestDTO#notes", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "AppointmentRequestDTO#reason", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "ChatMessageRequestDTO#content", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "DepartmentTranslationRequestDTO#description", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "DepartmentTranslationRequestDTO#languageCode", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "DepartmentTranslationRequestDTO#name", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "Dhis2DataElementMappingRequestDTO#datasetUid", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "Dhis2DataElementMappingRequestDTO#dhis2CategoryOptionComboUid", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "Dhis2DataElementMappingRequestDTO#dhis2DataElementUid", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "DischargeSummaryRequestDTO#patientOrCaregiverSignature", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "EncounterNoteAddendumRequestDTO#authorCredentials", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "EncounterNoteAddendumRequestDTO#authorName", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "EncounterNoteLinkRequestDTO#artifactCode", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "EncounterNoteLinkRequestDTO#artifactDisplay", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "EncounterNoteLinkRequestDTO#artifactStatus", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "EncounterNoteRequestDTO#authorCredentials", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "EncounterNoteRequestDTO#authorName", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "EncounterRequestDTO#createdBy", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "EncounterRequestDTO#updatedBy", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "EncounterTreatmentRequestDTO#notes", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "FrontendAuditEventRequestDTO#actor", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "FrontendAuditEventRequestDTO#ipAddress", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "FrontendAuditEventRequestDTO#userAgent", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "GeneralReferralRequestDTO#anticipatedTreatment", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "GeneralReferralRequestDTO#clinicalIndication", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "GeneralReferralRequestDTO#clinicalQuestion", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "GeneralReferralRequestDTO#clinicalSummary", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "GeneralReferralRequestDTO#insuranceAuthNumber", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "GeneralReferralRequestDTO#referralReason", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "GeneralReferralRequestDTO#targetFacilityName", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "HospitalRequestDTO#address", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "HospitalRequestDTO#city", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "HospitalRequestDTO#country", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "HospitalRequestDTO#email", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "HospitalRequestDTO#poBox", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "HospitalRequestDTO#province", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "HospitalRequestDTO#region", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "HospitalRequestDTO#sector", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "HospitalRequestDTO#website", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "HospitalRequestDTO#zipCode", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "InvoiceItemRequestDTO#itemDescription", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "LabOrderRequestDTO#documentationReference", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "LabOrderRequestDTO#notes", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "LabOrderRequestDTO#orderChannelOther", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "LabOrderRequestDTO#orderingProviderNpi", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "LabOrderRequestDTO#primaryDiagnosisCode", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "LabOrderRequestDTO#priority", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "LabOrderRequestDTO#standingOrderReviewNotes", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "LabQcEventRequestDTO#analyzerId", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "LabQcEventRequestDTO#notes", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "LabReflexRuleRequestDTO#description", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "LabResultRequestDTO#notes", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "LabResultRequestDTO#resultUnit", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "LabResultRequestDTO#resultValue", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "PatientEducationProgressRequestDTO#clarificationRequest", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "PatientEducationProgressRequestDTO#feedback", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "PatientEducationProgressRequestDTO#providerNotes", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "PatientHospitalRegistrationRequestDTO#attendingPhysicianName", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "PatientHospitalRegistrationRequestDTO#currentBed", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "PatientHospitalRegistrationRequestDTO#currentRoom", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "PatientHospitalRegistrationRequestDTO#readyForDischargeNote", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "PatientInsuranceRequestDTO#groupNumber", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "PatientInsuranceRequestDTO#policyNumber", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "PatientInsuranceRequestDTO#providerName", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "PatientInsuranceRequestDTO#subscriberName", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "PatientInsuranceRequestDTO#subscriberRelationship", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "PatientPrimaryCareRequestDTO#notes", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "PatientRequestDTO#city", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "PatientRequestDTO#country", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "PatientRequestDTO#email", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "PatientRequestDTO#firstName", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "PatientRequestDTO#lastName", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "PatientRequestDTO#phoneNumberSecondary", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "PatientRequestDTO#preferredPharmacy", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "PatientRequestDTO#state", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "PatientRequestDTO#zipCode", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "PermissionRequestDTO#name", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "PostpartumObservationRequestDTO#followUpContactMethod", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "PostpartumObservationRequestDTO#lochiaNotes", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "PostpartumObservationRequestDTO#perineumFindings", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "PostpartumObservationRequestDTO#signoffCredentials", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "PostpartumObservationRequestDTO#signoffName", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "ServiceTranslationRequestDTO#description", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "ServiceTranslationRequestDTO#languageCode", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "ServiceTranslationRequestDTO#name", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "StaffShiftRequestDTO#notes", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "UltrasoundOrderRequestDTO#appointmentLocation", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "UltrasoundOrderRequestDTO#clinicalIndication", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "UltrasoundOrderRequestDTO#highRiskNotes", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "UltrasoundOrderRequestDTO#priority", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "UltrasoundOrderRequestDTO#scheduledTime", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "UltrasoundOrderRequestDTO#specialInstructions", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "UltrasoundReportRequestDTO#providerReviewNotes", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "UserMfaEnrollmentRequestDTO#channel", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "UserRecoveryContactRequestDTO#contactValue", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "UserRecoveryContactRequestDTO#notes", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "UserRoleHospitalAssignmentRequestDTO#assignmentCode", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "VisitEducationDocumentationRequestDTO#topicDiscussed", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "AdmissionOrderSetRequestDTO#clinicalGuidelines", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "AdmissionOrderSetRequestDTO#description", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "AdmissionOrderSetRequestDTO#name", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "AdmissionRequestDTO#admissionNotes", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "AdmissionRequestDTO#admissionSource", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "AdmissionRequestDTO#chiefComplaint", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "AdmissionRequestDTO#insuranceAuthNumber", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "AdmissionRequestDTO#primaryDiagnosisCode", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "AdmissionRequestDTO#primaryDiagnosisDescription", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "AdmissionRequestDTO#roomBed", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "AdvanceDirectiveRequestDTO#description", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "AppointmentRequestDTO#notes", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "AppointmentRequestDTO#reason", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "ChatMessageRequestDTO#content", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "DepartmentTranslationRequestDTO#description", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "DepartmentTranslationRequestDTO#languageCode", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "DepartmentTranslationRequestDTO#name", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "Dhis2DataElementMappingRequestDTO#datasetUid", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "Dhis2DataElementMappingRequestDTO#dhis2CategoryOptionComboUid", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "Dhis2DataElementMappingRequestDTO#dhis2DataElementUid", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "DischargeSummaryRequestDTO#patientOrCaregiverSignature", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "EncounterNoteAddendumRequestDTO#authorCredentials", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "EncounterNoteAddendumRequestDTO#authorName", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "EncounterNoteLinkRequestDTO#artifactCode", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "EncounterNoteLinkRequestDTO#artifactDisplay", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "EncounterNoteLinkRequestDTO#artifactStatus", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "EncounterNoteRequestDTO#authorCredentials", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "EncounterNoteRequestDTO#authorName", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "EncounterRequestDTO#createdBy", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "EncounterRequestDTO#updatedBy", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "EncounterTreatmentRequestDTO#notes", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "FrontendAuditEventRequestDTO#actor", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "FrontendAuditEventRequestDTO#ipAddress", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "FrontendAuditEventRequestDTO#userAgent", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "GeneralReferralRequestDTO#anticipatedTreatment", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "GeneralReferralRequestDTO#clinicalIndication", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "GeneralReferralRequestDTO#clinicalQuestion", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "GeneralReferralRequestDTO#clinicalSummary", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "GeneralReferralRequestDTO#insuranceAuthNumber", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "GeneralReferralRequestDTO#referralReason", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "GeneralReferralRequestDTO#targetFacilityName", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "HospitalRequestDTO#address", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "HospitalRequestDTO#city", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "HospitalRequestDTO#country", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "HospitalRequestDTO#email", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "HospitalRequestDTO#poBox", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "HospitalRequestDTO#province", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "HospitalRequestDTO#region", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "HospitalRequestDTO#sector", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "HospitalRequestDTO#website", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "HospitalRequestDTO#zipCode", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "InvoiceItemRequestDTO#itemDescription", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "LabOrderRequestDTO#documentationReference", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "LabOrderRequestDTO#notes", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "LabOrderRequestDTO#orderChannelOther", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "LabOrderRequestDTO#orderingProviderNpi", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "LabOrderRequestDTO#primaryDiagnosisCode", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "LabOrderRequestDTO#priority", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "LabOrderRequestDTO#standingOrderReviewNotes", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "LabQcEventRequestDTO#analyzerId", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "LabQcEventRequestDTO#notes", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "LabReflexRuleRequestDTO#description", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "LabResultRequestDTO#notes", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "LabResultRequestDTO#resultUnit", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "LabResultRequestDTO#resultValue", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "PatientEducationProgressRequestDTO#clarificationRequest", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "PatientEducationProgressRequestDTO#feedback", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "PatientEducationProgressRequestDTO#providerNotes", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "PatientHospitalRegistrationRequestDTO#attendingPhysicianName", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "PatientHospitalRegistrationRequestDTO#currentBed", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "PatientHospitalRegistrationRequestDTO#currentRoom", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "PatientHospitalRegistrationRequestDTO#readyForDischargeNote", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "PatientInsuranceRequestDTO#groupNumber", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "PatientInsuranceRequestDTO#policyNumber", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "PatientInsuranceRequestDTO#providerName", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "PatientInsuranceRequestDTO#subscriberName", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "PatientInsuranceRequestDTO#subscriberRelationship", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "PatientPrimaryCareRequestDTO#notes", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "PatientRequestDTO#city", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "PatientRequestDTO#country", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "PatientRequestDTO#email", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "PatientRequestDTO#firstName", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "PatientRequestDTO#lastName", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "PatientRequestDTO#phoneNumberSecondary", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "PatientRequestDTO#preferredPharmacy", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "PatientRequestDTO#state", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "PatientRequestDTO#zipCode", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "PermissionRequestDTO#name", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "PostpartumObservationRequestDTO#followUpContactMethod", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "PostpartumObservationRequestDTO#lochiaNotes", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "PostpartumObservationRequestDTO#perineumFindings", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "PostpartumObservationRequestDTO#signoffCredentials", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "PostpartumObservationRequestDTO#signoffName", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "ServiceTranslationRequestDTO#description", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "ServiceTranslationRequestDTO#languageCode", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "ServiceTranslationRequestDTO#name", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "StaffShiftRequestDTO#notes", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "UltrasoundOrderRequestDTO#appointmentLocation", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "UltrasoundOrderRequestDTO#clinicalIndication", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "UltrasoundOrderRequestDTO#highRiskNotes", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "UltrasoundOrderRequestDTO#priority", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "UltrasoundOrderRequestDTO#scheduledTime", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "UltrasoundOrderRequestDTO#specialInstructions", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "UltrasoundReportRequestDTO#providerReviewNotes", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "UserMfaEnrollmentRequestDTO#channel", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "UserRecoveryContactRequestDTO#contactValue", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "UserRecoveryContactRequestDTO#notes", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "UserRoleHospitalAssignmentRequestDTO#assignmentCode", 1, "pre-existing (QA D1050 class): no @Size for its column length",
        "VisitEducationDocumentationRequestDTO#topicDiscussed", 1, "pre-existing (QA D1050 class): no @Size for its column length");


    private static final Map<String, GuardAllowlist.Allowance> ALLOWED = GuardAllowlist.allow(
        PINS.toArray());

    @Test
    void everyRequestTextFieldFitsItsColumn() throws Exception {
        Map<String, Class<?>> entities = classesUnder("model");
        List<String> sites = new ArrayList<>();
        int pairs = 0;
        for (Class<?> dto : classesUnder("payload/dto").values()) {
            String name = dto.getSimpleName();
            String entityName = name.endsWith("RequestDTO") ? name.substring(0, name.length() - "RequestDTO".length())
                : name.endsWith("Request") ? name.substring(0, name.length() - "Request".length()) : null;
            Class<?> entity = entityName == null ? null : entities.get(entityName);
            if (entity == null || !entity.isAnnotationPresent(jakarta.persistence.Entity.class)) {
                continue;
            }
            pairs++;
            for (Field field : dto.getDeclaredFields()) {
                if (field.getType() != String.class) {
                    continue;
                }
                Field column = fieldOf(entity, field.getName());
                Column mapping = column == null || column.getType() != String.class ? null
                    : column.getAnnotation(Column.class);
                if (mapping == null || !mapping.columnDefinition().isEmpty()) {
                    continue;
                }
                Size size = field.getAnnotation(Size.class);
                if (size == null || size.max() > mapping.length()) {
                    sites.add(name + "#" + field.getName());
                }
            }
        }
        assertThat(pairs).as("the scan must pair request DTOs with entities").isGreaterThan(20);
        Map<String, Integer> counts = GuardAllowlist.counts(sites);
        assertThat(GuardAllowlist.overAllowance(counts, ALLOWED))
            .as("a request text field longer than its column reaches the database as a generic failure (QA D1050): "
                + "add @Size(max = <column length>)")
            .isEmpty();
        assertThat(GuardAllowlist.stale(counts, ALLOWED)).as("the site was fixed: delete its line").isEmpty();
    }

    private static Field fieldOf(Class<?> type, String name) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // up to the superclass
            }
        }
        return null;
    }

    private static Map<String, Class<?>> classesUnder(String dir) throws IOException {
        Map<String, Class<?>> out = new HashMap<>();
        try (Stream<Path> files = Files.walk(MAIN.resolve(dir))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String rel = MAIN.relativize(file).toString().replace('\\', '/');
                String fqcn = "com.example.hms." + rel.substring(0, rel.length() - ".java".length()).replace('/', '.');
                try {
                    Class<?> c = Class.forName(fqcn, false, RequestFieldColumnLengthGuardTest.class.getClassLoader());
                    out.putIfAbsent(c.getSimpleName(), c);
                } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
                    // a package-info or a file whose class name differs
                }
            }
        }
        return out;
    }

}
