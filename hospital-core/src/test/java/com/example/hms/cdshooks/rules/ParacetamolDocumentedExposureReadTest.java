package com.example.hms.cdshooks.rules;

import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.model.MedicationAdministrationRecord;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.repository.AdmissionRepository;
import com.example.hms.repository.MedicationAdministrationRecordRepository;
import com.example.hms.repository.MedicationCatalogItemRepository;
import com.example.hms.repository.PatientProblemRepository;
import com.example.hms.repository.PrescriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** CR-03: what the reader takes from the MAR. The query itself runs on PostgreSQL in MarAndOpenStayQueriesPostgresIT. */
@DisplayName("Documented paracetamol exposure: read from the doses, not from the orders")
class ParacetamolDocumentedExposureReadTest {

    private final PrescriptionRepository prescriptions = mock(PrescriptionRepository.class);
    private final MedicationCatalogItemRepository catalogue = mock(MedicationCatalogItemRepository.class);
    private final PatientProblemRepository problems = mock(PatientProblemRepository.class);
    private final AdmissionRepository admissions = mock(AdmissionRepository.class);
    private final MedicationAdministrationRecordRepository mar = mock(MedicationAdministrationRecordRepository.class);
    private final ParacetamolDosesGiven dosesGiven = new ParacetamolDosesGiven(mar);
    private final ParacetamolFactsReader reader =
        new ParacetamolFactsReader(prescriptions, catalogue, problems, admissions, dosesGiven);

    private final UUID hospitalId = UUID.randomUUID();
    private Patient adult;
    private MedicationCatalogItem tablet;

    @BeforeEach
    void setUp() {
        adult = Patient.builder().dateOfBirth(LocalDate.now(ZoneId.systemDefault()).minusYears(40)).build();
        adult.setId(UUID.randomUUID());
        tablet = item("Paracétamol", "Doliprane", "500");
        MedicationCatalogItem combination = item("Paracétamol + codéine", "Codoliprane", "500/30");
        MedicationCatalogItem other = item("Amoxicilline", "Clamoxyl", "500");
        when(catalogue.findActivePage(eq(hospitalId), any(Pageable.class)))
            .thenAnswer(i -> new PageImpl<>(List.of(tablet, combination, other)));
        when(prescriptions.findByPatient_IdAndHospital_Id(adult.getId(), hospitalId)).thenReturn(List.of());
        when(problems.findByPatient_IdAndHospital_Id(adult.getId(), hospitalId)).thenReturn(List.of());
    }

    private static MedicationCatalogItem item(String generic, String brand, String strength) {
        MedicationCatalogItem item = MedicationCatalogItem.builder().code(brand.toUpperCase(java.util.Locale.ROOT))
            .genericName(generic).brandName(brand).nameFr(generic).strength(strength).strengthUnit("mg").build();
        item.setId(UUID.randomUUID());
        return item;
    }

    private static MedicationAdministrationRecord given(String name, String dose, PrescriptionStatus orderStatus,
                                                        LocalDateTime at) {
        Prescription rx = Prescription.builder().medicationName(name).dosage(dose).status(orderStatus).build();
        rx.setId(UUID.randomUUID());
        return MedicationAdministrationRecord.builder().prescription(rx).dose(dose).administeredAt(at).build();
    }

    private ParacetamolFacts.Documented read() {
        return reader.read(adult, hospitalId, "Paracétamol 500 mg", tablet, null).documented();
    }

    @Test
    void aDiscontinuedOrdersRecentDoseCountsThoughTheOrderIsNotActive() {
        LocalDateTime at = LocalDateTime.now(ZoneId.systemDefault()).minusHours(3);
        when(mar.findGivenSince(eq(adult.getId()), eq(hospitalId), any()))
            .thenReturn(List.of(given("Paracétamol 500 mg", "500 mg", PrescriptionStatus.DISCONTINUED, at)));

        ParacetamolFacts.Documented documented = read();

        assertThat(documented.read()).isTrue();
        assertThat(documented.doses()).singleElement().satisfies(dose -> {
            assertThat(dose.dose()).isEqualTo("500 mg");
            assertThat(dose.givenAt()).isEqualTo(at);
        });
        assertThat(reader.read(adult, hospitalId, "Paracétamol 500 mg", tablet, null).otherOrders()).isEmpty();
    }

    @Test
    void theWindowIsTheLastTwentyFourHoursAcrossMidnightNotTheCalendarDay() {
        dosesGiven.setClock(Clock.fixed(LocalDateTime.of(2026, 11, 7, 0, 30).toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
        when(mar.findGivenSince(any(), any(), any())).thenReturn(List.of());

        read();

        verify(mar, org.mockito.Mockito.atLeastOnce())
            .findGivenSince(adult.getId(), hospitalId, LocalDateTime.of(2026, 11, 6, 0, 30));
    }

    @Test
    void aCombinationAndAnUnknownProductAreNamedAnotherDrugIsNot() {
        LocalDateTime at = LocalDateTime.now(ZoneId.systemDefault()).minusHours(1);
        when(mar.findGivenSince(any(), any(), any())).thenReturn(List.of(
            given("Codoliprane", "2 cp", PrescriptionStatus.SIGNED, at),
            given("Marque inconnue", "1 cp", PrescriptionStatus.SIGNED, at),
            given("Amoxicilline", "500 mg", PrescriptionStatus.SIGNED, at)));

        ParacetamolFacts.Documented documented = read();

        assertThat(documented.doses()).isEmpty();
        assertThat(documented.unreadable()).containsExactly("Codoliprane", "Marque inconnue");
    }

    @Test
    void aRowWithNoReadableDoseFallsBackOnTheOrders() {
        MedicationAdministrationRecord row = given("Paracétamol", "1 g", PrescriptionStatus.SIGNED,
            LocalDateTime.now(ZoneId.systemDefault()));
        row.setDose("See order");
        when(mar.findGivenSince(any(), any(), any())).thenReturn(List.of(row));

        assertThat(read().doses()).singleElement().satisfies(dose -> assertThat(dose.dose()).isEqualTo("1 g"));
    }

    @Test
    void noHospitalOrAChildReadsNoDoseAndTheFiguresStayUnknown() {
        assertThat(reader.read(adult, null, "Paracétamol 500 mg", tablet, null).documented().read()).isFalse();
        Patient child = Patient.builder().dateOfBirth(LocalDate.now(ZoneId.systemDefault()).minusYears(6)).build();
        child.setId(UUID.randomUUID());
        assertThat(reader.read(child, hospitalId, "Paracétamol 500 mg", tablet, null).documented().read()).isFalse();
        verifyNoInteractions(mar);
    }

    /** The review of this wave: an item taken off the list since must not make the dose vanish. */
    @Test
    void aDoseLinkedToAnItemNoLongerActiveIsNamedNotDropped() {
        MedicationAdministrationRecord row = given("Doliprane 1 g", "1 g", PrescriptionStatus.SIGNED,
            LocalDateTime.now(ZoneId.systemDefault()));
        row.getPrescription().setMedicationCatalogItemId(UUID.randomUUID());
        when(mar.findGivenSince(any(), any(), any())).thenReturn(List.of(row));

        ParacetamolFacts.Documented documented = read();

        assertThat(documented.doses().size() + documented.unreadable().size()).isEqualTo(1);
    }
}
