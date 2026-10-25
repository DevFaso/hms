package com.example.hms.service.pharmacy;

import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.model.Hospital;
import com.example.hms.model.Prescription;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.repository.MedicationCatalogItemRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * QA D678: catalog-controlled morphine written without the prescriber's tick
 * was treated as an ordinary drug everywhere but the prescription response.
 * The shared resolver makes it controlled for pharmacist verification (and
 * the chart, history and PDF, pinned in their own tests); signing is still
 * not blocked (user decision D654).
 */
class CatalogControlledPrescriptionTest {

    private static final UUID HOSPITAL = UUID.randomUUID();
    private static final UUID OTHER_HOSPITAL = UUID.randomUUID();
    private static final UUID MORPHINE_ITEM = UUID.randomUUID();

    private final MedicationCatalogItemRepository catalog = mock(MedicationCatalogItemRepository.class);
    private final PrescriptionControlledResolver resolver = new PrescriptionControlledResolver(catalog);
    private PharmacistVerificationService verification;

    @BeforeEach
    void setUp() {
        MedicationCatalogItem morphine = new MedicationCatalogItem();
        morphine.setControlled(true);
        when(catalog.findByIdAndHospital_Id(MORPHINE_ITEM, HOSPITAL)).thenReturn(Optional.of(morphine));
        when(catalog.findByIdAndHospital_Id(MORPHINE_ITEM, OTHER_HOSPITAL)).thenReturn(Optional.empty());
        when(catalog.findByHospitalIdAndCode(any(), any())).thenReturn(Optional.empty());
        verification = new PharmacistVerificationService(mock(PrescriptionRepository.class),
            mock(UserRepository.class), mock(RoleValidator.class), Clock.systemUTC(), new com.example.hms.service.EndedStayOrderGuard(org.mockito.Mockito.mock(com.example.hms.repository.AdmissionRepository.class)));
        verification.setControlledResolver(resolver);
    }

    static Prescription morphineWithoutTick(UUID hospitalId) {
        Hospital h = new Hospital();
        h.setId(hospitalId);
        Prescription rx = new Prescription();
        rx.setId(UUID.randomUUID());
        rx.setHospital(h);
        rx.setMedicationName("Morphine");
        rx.setMedicationCatalogItemId(MORPHINE_ITEM);
        rx.setControlledSubstance(false);
        rx.setStatus(PrescriptionStatus.SIGNED);
        return rx;
    }

    @Test
    void catalogControlledMorphineWithoutTheTickNeedsPharmacistVerification() {
        Prescription rx = morphineWithoutTick(HOSPITAL);
        assertThat(resolver.isControlled(rx)).isTrue();
        assertThat(verification.requiresVerification(rx)).isTrue();
        assertThat(verification.blocksAdministration(rx)).isTrue();
    }

    @Test
    void theCatalogIsReadAtThePrescriptionsOwnHospitalOnly() {
        Prescription rx = morphineWithoutTick(OTHER_HOSPITAL);
        assertThat(resolver.isControlled(rx)).isFalse();
        assertThat(verification.requiresVerification(rx)).isFalse();
    }

    @Test
    void theTickAloneStillCounts() {
        Prescription rx = morphineWithoutTick(HOSPITAL);
        rx.setMedicationCatalogItemId(null);
        rx.setControlledSubstance(true);
        assertThat(resolver.isControlled(rx)).isTrue();
    }

    @Test
    void signingIsStillNotBlockedByTheCatalogFlag() {
        // D654: the two-factor block stays on the prescriber's tick until a two-factor step exists.
        Prescription rx = morphineWithoutTick(HOSPITAL);
        assertThatCode(() -> new ControlledSubstanceGuard().requireSafeguardsFor(rx, PrescriptionStatus.SIGNED))
            .doesNotThrowAnyException();
    }
}
