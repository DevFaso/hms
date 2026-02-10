package com.example.hms.mapper;

import com.example.hms.model.BillingInvoice;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientInsurance;
import com.example.hms.payload.dto.BillingInvoiceRequestDTO;
import com.example.hms.payload.dto.BillingInvoiceResponseDTO;
import com.example.hms.payload.dto.PatientInsuranceSummaryDTO;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Component
public class BillingInvoiceMapper {

    public BillingInvoiceResponseDTO toBillingInvoiceResponseDTO(BillingInvoice invoice) {
        if (invoice == null) return null;

        BillingInvoiceResponseDTO dto = new BillingInvoiceResponseDTO();
        dto.setId(invoice.getId() != null ? invoice.getId().toString() : null);

        // Patient info
        populatePatientInfo(dto, invoice.getPatient());

        // Hospital info
        populateHospitalInfo(dto, invoice.getHospital());

        // Encounter info
        populateEncounterInfo(dto, invoice.getEncounter());

        // Invoice details
        populateInvoiceDetails(dto, invoice);

        return dto;
    }

    private void populatePatientInfo(BillingInvoiceResponseDTO dto, Patient patient) {
        if (patient == null) return;

        String fullName = buildPatientName(patient);
        dto.setPatientFullName(fullName);
        dto.setPatientName(fullName);
        dto.setPatientEmail(patient.getEmail());
        try {
            dto.setPatientPhone(patient.getPhoneNumberPrimary());
        } catch (Exception ignored) {
            dto.setPatientPhone(null);
        }

        // Insurance information
        if (patient.getPatientInsurances() != null && !patient.getPatientInsurances().isEmpty()) {
            List<PatientInsuranceSummaryDTO> insuranceSummaries = patient.getPatientInsurances().stream()
                .map(this::toPatientInsuranceSummaryDTO)
                .toList();
            dto.setPatientInsurances(insuranceSummaries);

            BigDecimal totalCoverage = insuranceSummaries.stream()
                .map(summary -> summary.getCoverageAmount() != null ? summary.getCoverageAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            dto.setInsuranceCoverageAmount(totalCoverage);
        }
    }

    private void populateHospitalInfo(BillingInvoiceResponseDTO dto, Hospital hospital) {
        if (hospital == null) return;

        dto.setHospitalName(hospital.getName());
        dto.setHospitalCode(hospital.getCode());
        dto.setHospitalAddress(hospital.getAddress());
    }

    private void populateEncounterInfo(BillingInvoiceResponseDTO dto, Encounter encounter) {
        if (encounter == null) return;

        dto.setEncounterDescription(encounter.getNotes());
        dto.setEncounterType(encounter.getEncounterType() != null ? encounter.getEncounterType().name() : null);
        dto.setEncounterStatus(encounter.getStatus() != null ? encounter.getStatus().name() : null);
        dto.setEncounterDate(encounter.getEncounterDate() != null ? encounter.getEncounterDate().toString() : null);
        dto.setEncounterTime(encounter.getEncounterDate() != null ? encounter.getEncounterDate().toLocalTime().toString() : null);
    }

    private void populateInvoiceDetails(BillingInvoiceResponseDTO dto, BillingInvoice invoice) {
        dto.setInvoiceNumber(invoice.getInvoiceNumber());
        dto.setInvoiceDate(invoice.getInvoiceDate() != null ? invoice.getInvoiceDate().toString() : null);
        dto.setDueDate(invoice.getDueDate() != null ? invoice.getDueDate().toString() : null);
        dto.setTotalAmount(invoice.getTotalAmount());
        dto.setAmountPaid(invoice.getAmountPaid());

        // Calculate balance due
        BigDecimal balanceDue = (invoice.getTotalAmount() != null ? invoice.getTotalAmount() : BigDecimal.ZERO)
            .subtract(invoice.getAmountPaid() != null ? invoice.getAmountPaid() : BigDecimal.ZERO);
        dto.setBalanceDue(balanceDue);

        // Calculate patient responsibility
        BigDecimal patientResponsibility = balanceDue.subtract(
            dto.getInsuranceCoverageAmount() != null ? dto.getInsuranceCoverageAmount() : BigDecimal.ZERO);
        dto.setPatientResponsibilityAmount(patientResponsibility.max(BigDecimal.ZERO));

        dto.setStatus(invoice.getStatus() != null ? invoice.getStatus().name() : null);
        dto.setNotes(invoice.getNotes());
        dto.setCreatedAt(invoice.getCreatedAt() != null ? invoice.getCreatedAt().toString() : null);
        dto.setUpdatedAt(invoice.getUpdatedAt() != null ? invoice.getUpdatedAt().toString() : null);
    }

    private PatientInsuranceSummaryDTO toPatientInsuranceSummaryDTO(PatientInsurance insurance) {
        if (insurance == null) return null;

        return PatientInsuranceSummaryDTO.builder()
            .id(insurance.getId() != null ? insurance.getId().toString() : null)
            .providerName(insurance.getProviderName())
            .policyNumber(insurance.getPolicyNumber())
            .groupNumber(insurance.getGroupNumber())
            .primary(insurance.isPrimary())
            .subscriberName(insurance.getSubscriberName())
            .subscriberRelationship(insurance.getSubscriberRelationship())
            .effectiveDate(insurance.getEffectiveDate())
            .expirationDate(insurance.getExpirationDate())
            .coverageAmount(BigDecimal.ZERO)
            .coPayAmount(BigDecimal.ZERO)
            .coInsurancePercentage(BigDecimal.ZERO)
            .deductibleRemaining(BigDecimal.ZERO)
            .build();
    }

    public BillingInvoice toBillingInvoice(BillingInvoiceRequestDTO dto,
                                           Patient patient,
                                           Hospital hospital,
                                           Encounter encounter) {
        if (dto == null) return null;

        BillingInvoice invoice = new BillingInvoice();
        invoice.setPatient(patient);
        invoice.setHospital(hospital);
        invoice.setEncounter(encounter);
        invoice.setInvoiceNumber(dto.getInvoiceNumber());
        invoice.setInvoiceDate(dto.getInvoiceDate());
        invoice.setDueDate(dto.getDueDate());
        invoice.setTotalAmount(dto.getTotalAmount());
        invoice.setAmountPaid(dto.getAmountPaid());
        invoice.setStatus(dto.getStatus());
        invoice.setNotes(dto.getNotes());
        return invoice;
    }

    public void updateEntityFromDto(BillingInvoice entity,
                                    BillingInvoiceRequestDTO dto,
                                    Patient patient,
                                    Hospital hospital,
                                    Encounter encounter) {
        if (entity == null || dto == null) return;

        entity.setPatient(patient);
        entity.setHospital(hospital);
        entity.setEncounter(encounter);
        entity.setInvoiceNumber(dto.getInvoiceNumber());
        entity.setInvoiceDate(dto.getInvoiceDate());
        entity.setDueDate(dto.getDueDate());
        entity.setTotalAmount(dto.getTotalAmount());
        entity.setAmountPaid(dto.getAmountPaid());
        entity.setStatus(dto.getStatus());
        entity.setNotes(dto.getNotes());
    }

    private String buildPatientName(Patient p) {
        String first  = p.getFirstName()  == null ? "" : p.getFirstName().trim();
        String middle = p.getMiddleName() == null ? "" : p.getMiddleName().trim();
        String last   = p.getLastName()   == null ? "" : p.getLastName().trim();
        String full   = (first + " " + middle + " " + last).replaceAll("\\s+", " ").trim();
        return full.isEmpty() ? null : full;
    }
}
