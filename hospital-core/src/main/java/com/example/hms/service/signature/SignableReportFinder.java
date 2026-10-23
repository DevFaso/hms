package com.example.hms.service.signature;

import com.example.hms.enums.SignatureStatus;
import com.example.hms.enums.SignatureType;
import com.example.hms.model.Patient;
import com.example.hms.model.signature.DigitalSignature;
import com.example.hms.payload.dto.signature.SignableReportDTO;
import com.example.hms.repository.DigitalSignatureRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The reports the sign modal offers (QA D627): the signer's own records, in
 * the hospital they act in, of a type {@link SignableReportLocator} can
 * resolve, that they have not already signed. "Own" is the record's
 * responsible clinician: the discharging provider, the interpreting
 * radiologist, the consultant, the note's author, the prescriber, the
 * ordering provider, or the lab result's recording assignment.
 *
 * <p>This only shapes the picker. {@code signReport} still checks the
 * hospital, the signer and the duplicate on every request.
 */
@Component
@RequiredArgsConstructor
public class SignableReportFinder {

    /** Per type, newest first; the picker is for recent work, not an archive. */
    static final int PER_TYPE_LIMIT = 50;

    @PersistenceContext
    private EntityManager entityManager;

    private final DigitalSignatureRepository signatureRepository;

    @Transactional(readOnly = true)
    public List<SignableReportDTO> find(UUID hospitalId, UUID staffId, UUID userId) {
        Set<String> alreadySigned = new HashSet<>();
        for (DigitalSignature s : signatureRepository.findBySignedBy_IdAndStatusOrderBySignatureDateTimeDesc(
                staffId, SignatureStatus.SIGNED)) {
            alreadySigned.add(s.getReportType() + ":" + s.getReportId());
        }
        List<SignableReportDTO> out = new ArrayList<>();
        add(out, SignatureType.DISCHARGE_SUMMARY,
            "SELECT r.id, r.patient, r.createdAt FROM DischargeSummary r "
                + "WHERE r.hospital.id = :h AND r.dischargingProvider.id = :s ORDER BY r.createdAt DESC",
            hospitalId, staffId);
        add(out, SignatureType.LAB_RESULT,
            "SELECT r.id, r.labOrder.patient, r.createdAt FROM LabResult r "
                + "WHERE r.labOrder.hospital.id = :h AND r.assignment.user.id = :s ORDER BY r.createdAt DESC",
            hospitalId, userId);
        add(out, SignatureType.IMAGING_REPORT,
            "SELECT r.id, r.imagingOrder.patient, r.createdAt FROM ImagingReport r "
                + "WHERE r.hospital.id = :h AND r.interpretingProvider.id = :s ORDER BY r.createdAt DESC",
            hospitalId, staffId);
        add(out, SignatureType.CONSULTATION_NOTE,
            "SELECT r.id, r.patient, r.createdAt FROM Consultation r "
                + "WHERE r.hospital.id = :h AND r.consultant.id = :s ORDER BY r.createdAt DESC",
            hospitalId, staffId);
        // PROGRESS_NOTE and ED_NOTE resolve to the same store; offer each note once.
        add(out, SignatureType.PROGRESS_NOTE,
            "SELECT r.id, r.patient, r.createdAt FROM EncounterNote r "
                + "WHERE r.hospital.id = :h AND r.authorStaff.id = :s ORDER BY r.createdAt DESC",
            hospitalId, staffId);
        add(out, SignatureType.MEDICATION_ORDER,
            "SELECT r.id, r.patient, r.createdAt FROM Prescription r "
                + "WHERE r.hospital.id = :h AND r.staff.id = :s ORDER BY r.createdAt DESC",
            hospitalId, staffId);
        add(out, SignatureType.PROCEDURE_REPORT,
            "SELECT r.id, r.patient, r.createdAt FROM ProcedureOrder r "
                + "WHERE r.hospital.id = :h AND r.orderingProvider.id = :s ORDER BY r.createdAt DESC",
            hospitalId, staffId);
        out.removeIf(r -> alreadySigned.contains(r.reportType() + ":" + r.reportId())
            || (r.reportType() == SignatureType.PROGRESS_NOTE
                && alreadySigned.contains(SignatureType.ED_NOTE + ":" + r.reportId())));
        out.sort(Comparator.comparing(SignableReportDTO::createdAt,
            Comparator.nullsLast(Comparator.reverseOrder())));
        return out;
    }

    private void add(List<SignableReportDTO> out, SignatureType type, String jpql, UUID hospitalId, UUID ownerId) {
        if (ownerId == null) {
            return;
        }
        List<Object[]> rows = entityManager.createQuery(jpql, Object[].class)
            .setParameter("h", hospitalId)
            .setParameter("s", ownerId)
            .setMaxResults(PER_TYPE_LIMIT)
            .getResultList();
        for (Object[] row : rows) {
            Patient patient = (Patient) row[1];
            out.add(new SignableReportDTO(type, (UUID) row[0],
                patient == null ? null : patient.getFullName(), (LocalDateTime) row[2]));
        }
    }
}
