package com.example.hms.service.pharmacy;

import com.example.hms.enums.PharmacyClaimStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.model.pharmacy.PharmacyClaim;
import com.example.hms.repository.pharmacy.PharmacyClaimRepository;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * T-48: batch claim export. Produces:
 *  - CSV for AMU reconciliation workflows (spreadsheet-friendly)
 *  - A minimal FHIR R4 Claim JSON bundle (no HAPI dependency required)
 *
 * Export scope is the caller's active hospital and any status set the caller
 * chooses (defaults to {@code SUBMITTED} + {@code ACCEPTED} for AMU batches).
 */
@Service
@RequiredArgsConstructor
public class PharmacyClaimExportService {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final PharmacyClaimRepository claimRepository;
    private final RoleValidator roleValidator;

    @Transactional(readOnly = true)
    public byte[] exportCsv(List<PharmacyClaimStatus> statuses) {
        List<PharmacyClaim> claims = loadClaims(statuses);
        StringBuilder sb = new StringBuilder();
        sb.append("id,dispense_id,patient_id,hospital_id,coverage_reference,")
          .append("claim_status,amount,currency,submitted_at,notes\n");
        for (PharmacyClaim c : claims) {
            sb.append(csv(c.getId()))
              .append(',').append(csv(id(c.getDispense() != null ? c.getDispense().getId() : null)))
              .append(',').append(csv(id(c.getPatient() != null ? c.getPatient().getId() : null)))
              .append(',').append(csv(id(c.getHospital() != null ? c.getHospital().getId() : null)))
              .append(',').append(csvEscape(c.getCoverageReference()))
              .append(',').append(csv(c.getClaimStatus()))
              .append(',').append(csv(c.getAmount()))
              .append(',').append(csv(c.getCurrency()))
              .append(',').append(csv(c.getSubmittedAt() != null ? c.getSubmittedAt().format(ISO) : ""))
              .append(',').append(csvEscape(c.getNotes()))
              .append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Transactional(readOnly = true)
    public byte[] exportFhirBundle(List<PharmacyClaimStatus> statuses) {
        List<PharmacyClaim> claims = loadClaims(statuses);
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"resourceType\":\"Bundle\",\"type\":\"collection\",\"entry\":[");
        boolean first = true;
        for (PharmacyClaim c : claims) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"resource\":")
              .append(toFhirClaim(c))
              .append('}');
        }
        sb.append("]}");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private String toFhirClaim(PharmacyClaim c) {
        return "{"
                + "\"resourceType\":\"Claim\","
                + "\"id\":" + quote(String.valueOf(c.getId())) + ","
                + "\"status\":" + quote(fhirStatus(c.getClaimStatus())) + ","
                + "\"use\":\"claim\","
                + "\"created\":" + quote(c.getCreatedAt() != null ? c.getCreatedAt().format(ISO) : "") + ","
                + "\"patient\":{\"reference\":" + quote("Patient/" + id(c.getPatient() != null ? c.getPatient().getId() : null)) + "},"
                + "\"provider\":{\"reference\":" + quote("Organization/" + id(c.getHospital() != null ? c.getHospital().getId() : null)) + "},"
                + "\"total\":{"
                + "\"value\":" + (c.getAmount() != null ? c.getAmount().toPlainString() : "0")
                + ",\"currency\":" + quote(c.getCurrency() != null ? c.getCurrency() : "XOF")
                + "}"
                + "}";
    }

    private List<PharmacyClaim> loadClaims(List<PharmacyClaimStatus> statuses) {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (statuses == null || statuses.isEmpty()) {
            throw new BusinessException("At least one claim status is required for export");
        }
        return claimRepository.findByHospitalIdAndClaimStatusIn(hospitalId, statuses);
    }

    private static String fhirStatus(PharmacyClaimStatus s) {
        if (s == null) {
            return "draft";
        }
        return switch (s) {
            case DRAFT -> "draft";
            case SUBMITTED, ACCEPTED, PAID -> "active";
            case REJECTED -> "cancelled";
        };
    }

    private static String csv(Object v) {
        return v == null ? "" : v.toString();
    }

    private static String id(UUID id) {
        return id == null ? "" : id.toString();
    }

    private static String csvEscape(String v) {
        if (v == null || v.isEmpty()) {
            return "";
        }
        String cleaned = v.replace("\r", " ").replace("\n", " ");
        if (cleaned.contains(",") || cleaned.contains("\"")) {
            return "\"" + cleaned.replace("\"", "\"\"") + "\"";
        }
        return cleaned;
    }

    private static String quote(String v) {
        return "\"" + (v == null ? "" : v.replace("\\", "\\\\").replace("\"", "\\\"")) + "\"";
    }
}
