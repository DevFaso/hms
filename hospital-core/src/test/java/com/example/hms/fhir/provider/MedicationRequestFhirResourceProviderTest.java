package com.example.hms.fhir.provider;

import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.fhir.mapper.MedicationRequestFhirMapper;
import com.example.hms.model.Prescription;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.service.support.PrescriptionIssuance;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.junit.jupiter.api.Test;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * QA D1537 review (round 40): the MedicationRequest search took the newest 200 rows and then dropped
 * the never-issued ones, so a patient with 200 newer drafts showed none of their issued orders. The
 * issued orders are chosen in the query; the read refuses what the search leaves out.
 */
class MedicationRequestFhirResourceProviderTest {

    private final PrescriptionRepository repository = mock(PrescriptionRepository.class);
    private final MedicationRequestFhirResourceProvider provider =
        new MedicationRequestFhirResourceProvider(repository, new MedicationRequestFhirMapper());

    private static Prescription rx(PrescriptionStatus... statuses) {
        Prescription p = new Prescription();
        p.setId(UUID.randomUUID());
        for (PrescriptionStatus status : statuses) {
            p.setStatus(status);
        }
        return p;
    }

    @Test
    void theSearchReturnsTheIssuedOrdersBehindAFullPageOfDrafts() {
        UUID patientId = UUID.randomUUID();
        List<Prescription> newestTwoHundred = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            newestTwoHundred.add(rx(PrescriptionStatus.DRAFT));
        }
        Prescription issued = rx(PrescriptionStatus.SIGNED);
        // What the old read fetched: the newest 200 rows, all drafts.
        lenient().when(repository.findByPatient_Id(eq(patientId), any(Pageable.class)))
            .thenReturn(new PageImpl<>(newestTwoHundred));
        when(repository.findIssuedByPatientId(eq(patientId), eq(PrescriptionIssuance.PROPOSALS), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(issued)));

        List<MedicationRequest> found = provider.search(new ReferenceParam("Patient/" + patientId), null);

        assertThat(found).extracting(MedicationRequest::getIdElement).extracting(IdType::getIdPart)
            .containsExactly(issued.getId().toString());
    }

    @Test
    void theReadRefusesADiscardedDraft() {
        Prescription discarded = rx(PrescriptionStatus.DRAFT, PrescriptionStatus.CANCELLED);
        when(repository.findWithEncounterById(discarded.getId())).thenReturn(Optional.of(discarded));

        assertThatThrownBy(() -> provider.read(new IdType("MedicationRequest", discarded.getId().toString())))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void theReadReturnsAStoppedOrderAsStopped() {
        Prescription stopped = rx(PrescriptionStatus.SIGNED, PrescriptionStatus.DISCONTINUED);
        when(repository.findWithEncounterById(stopped.getId())).thenReturn(Optional.of(stopped));

        assertThat(provider.read(new IdType("MedicationRequest", stopped.getId().toString())).getStatus())
            .isEqualTo(MedicationRequest.MedicationRequestStatus.STOPPED);
    }
}
