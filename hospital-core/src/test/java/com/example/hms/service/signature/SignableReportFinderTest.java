package com.example.hms.service.signature;

import com.example.hms.enums.SignatureStatus;
import com.example.hms.enums.SignatureType;
import com.example.hms.model.Patient;
import com.example.hms.model.signature.DigitalSignature;
import com.example.hms.payload.dto.signature.SignableReportDTO;
import com.example.hms.repository.DigitalSignatureRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** QA D627: what the picker keeps, drops and how it labels a row. */
class SignableReportFinderTest {

    private final DigitalSignatureRepository signatures = mock(DigitalSignatureRepository.class);
    private final EntityManager em = mock(EntityManager.class);
    private final SignableReportFinder finder = new SignableReportFinder(signatures);
    private final UUID hospital = UUID.randomUUID();
    private final UUID staff = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();

    @SuppressWarnings("unchecked")
    private void rowsFor(String entity, List<Object[]> rows) {
        TypedQuery<Object[]> q = mock(TypedQuery.class);
        when(q.setParameter(anyString(), any())).thenReturn(q);
        when(q.setMaxResults(anyInt())).thenReturn(q);
        when(q.getResultList()).thenReturn(rows);
        when(em.createQuery(org.mockito.ArgumentMatchers.contains("FROM " + entity + " "), eq(Object[].class)))
            .thenReturn(q);
    }

    private void noRowsAnywhere() {
        for (String e : new String[] {"DischargeSummary", "LabResult", "ImagingReport", "Consultation",
                "EncounterNote", "Prescription", "ProcedureOrder"}) {
            rowsFor(e, new ArrayList<>());
        }
    }

    private static DigitalSignature signed(SignatureType type, UUID id) {
        DigitalSignature s = new DigitalSignature();
        s.setReportType(type);
        s.setReportId(id);
        return s;
    }

    @Test
    void keepsUnsignedRowsNewestFirstAndDropsWhatTheSignerAlreadySigned() {
        ReflectionTestUtils.setField(finder, "entityManager", em);
        noRowsAnywhere();
        Patient patient = mock(Patient.class);
        when(patient.getFullName()).thenReturn("Awa Traore");
        UUID older = UUID.randomUUID();
        UUID newer = UUID.randomUUID();
        UUID signedOne = UUID.randomUUID();
        UUID noteSignedAsEd = UUID.randomUUID();
        List<Object[]> discharge = new ArrayList<>();
        discharge.add(new Object[] {older, patient, LocalDateTime.of(2026, 1, 1, 0, 0)});
        discharge.add(new Object[] {signedOne, null, LocalDateTime.of(2026, 3, 1, 0, 0)});
        rowsFor("DischargeSummary", discharge);
        List<Object[]> notes = new ArrayList<>();
        notes.add(new Object[] {newer, null, LocalDateTime.of(2026, 2, 1, 0, 0)});
        notes.add(new Object[] {noteSignedAsEd, null, null});
        rowsFor("EncounterNote", notes);
        when(signatures.findBySignedBy_IdAndStatusOrderBySignatureDateTimeDesc(staff, SignatureStatus.SIGNED))
            .thenReturn(List.of(signed(SignatureType.DISCHARGE_SUMMARY, signedOne),
                signed(SignatureType.ED_NOTE, noteSignedAsEd)));

        List<SignableReportDTO> out = finder.find(hospital, staff, user);

        assertThat(out).extracting(SignableReportDTO::reportId).containsExactly(newer, older);
        assertThat(out.get(0).reportType()).isEqualTo(SignatureType.PROGRESS_NOTE);
        assertThat(out.get(1).patientName()).isEqualTo("Awa Traore");
        assertThat(out.get(0).patientName()).isNull();
    }

    @Test
    void withoutAUserIdTheLabResultQueryIsNotRun() {
        ReflectionTestUtils.setField(finder, "entityManager", em);
        noRowsAnywhere();
        when(signatures.findBySignedBy_IdAndStatusOrderBySignatureDateTimeDesc(staff, SignatureStatus.SIGNED))
            .thenReturn(List.of());

        assertThat(finder.find(hospital, staff, null)).isEmpty();
        verify(em, never()).createQuery(org.mockito.ArgumentMatchers.contains("FROM LabResult "), eq(Object[].class));
    }
}
