package com.example.hms.service.emar;

import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.cdshooks.rules.CdsRuleEngine;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.repository.PrescriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The exposure read keeps to the order's hospital: another hospital's order is read for nobody, and fails nothing. */
@DisplayName("The exposure read is scoped to the order's hospital")
class ParacetamolExposureCheckTest {

    private final CdsRuleEngine engine = mock(CdsRuleEngine.class);
    private final PrescriptionRepository prescriptions = mock(PrescriptionRepository.class);
    private final ParacetamolExposureCheck check = new ParacetamolExposureCheck(engine, prescriptions);
    private final UUID hospitalId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final CdsCard card = mock(CdsCard.class);

    @BeforeEach
    void anOrderAtTheHospital() {
        Hospital hospital = Hospital.builder().build();
        hospital.setId(hospitalId);
        Prescription order = Prescription.builder().hospital(hospital).patient(new Patient()).build();
        order.setId(orderId);
        when(prescriptions.findById(orderId)).thenReturn(Optional.of(order));
    }

    @Test
    void anOrderOfAnotherHospitalGivesNoCardsAndNoFailure() {
        assertThat(check.cards(orderId, UUID.randomUUID(), true)).isEmpty();
        verifyNoInteractions(engine);
    }

    @Test
    void anOrderOfThisHospitalIsRead() {
        when(engine.paracetamolAtAdministration(any(), eq(hospitalId), any(), eq(true))).thenReturn(List.of(card));

        assertThat(check.cards(orderId, hospitalId, true)).containsExactly(card);
    }

    @Test
    void theGlobalViewReadsAtTheOrdersOwnHospital() {
        when(engine.paracetamolAtAdministration(any(), eq(hospitalId), any(), eq(false))).thenReturn(List.of(card));

        assertThat(check.cards(orderId, null, false)).containsExactly(card);
    }

    @Test
    void anOrderThatIsGoneGivesNoCards() {
        assertThat(check.cards(UUID.randomUUID(), hospitalId, true)).isEmpty();
    }
}
