package com.example.hms.cdshooks.rules;

import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.enums.ProblemSeverity;
import com.example.hms.enums.ProblemStatus;
import com.example.hms.model.Admission;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientProblem;
import com.example.hms.model.Prescription;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.repository.AdmissionRepository;
import com.example.hms.repository.MedicationCatalogItemRepository;
import com.example.hms.repository.PatientProblemRepository;
import com.example.hms.repository.PrescriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Which orders and problems count: "paracetamol from all active orders", coded problems only. */
class ParacetamolFactsReaderTest {

    private final PrescriptionRepository prescriptions = mock(PrescriptionRepository.class);
    private final MedicationCatalogItemRepository catalogue = mock(MedicationCatalogItemRepository.class);
    private final PatientProblemRepository problems = mock(PatientProblemRepository.class);
    private final AdmissionRepository admissions = mock(AdmissionRepository.class);
    private final ParacetamolFactsReader reader =
        new ParacetamolFactsReader(prescriptions, catalogue, problems, admissions);

    private final UUID hospitalId = UUID.randomUUID();
    private final List<Prescription> orders = new ArrayList<>();
    private final List<PatientProblem> rows = new ArrayList<>();
    private final List<MedicationCatalogItem> items = new ArrayList<>();
    private Patient adult;
    private MedicationCatalogItem doliprane;

    @BeforeEach
    void setUp() {
        adult = Patient.builder().dateOfBirth(LocalDate.now().minusYears(40)).build();
        adult.setId(UUID.randomUUID());
        doliprane = item("Paracétamol", "Doliprane", "1000");
        items.add(doliprane);
        items.add(item("Paracétamol + codéine", "Codoliprane", "500/30"));
        items.add(item("Amoxicilline", "Clamoxyl", "500"));
        when(prescriptions.findByPatient_IdAndHospital_Id(adult.getId(), hospitalId)).thenReturn(orders);
        when(problems.findByPatient_IdAndHospital_Id(adult.getId(), hospitalId)).thenReturn(rows);
        when(catalogue.findActivePage(eq(hospitalId), any(Pageable.class))).thenAnswer(i -> new PageImpl<>(items));
        when(admissions.findByPatientIdOrderByAdmissionDateTimeDesc(adult.getId())).thenReturn(List.<Admission>of());
    }

    private static MedicationCatalogItem item(String generic, String brand, String strength) {
        MedicationCatalogItem item = MedicationCatalogItem.builder().code(brand.toUpperCase())
            .genericName(generic).brandName(brand).nameFr(generic).strength(strength).strengthUnit("mg").build();
        item.setId(UUID.randomUUID());
        return item;
    }

    private Prescription order(String name, PrescriptionStatus status) {
        Prescription rx = Prescription.builder().medicationName(name).dosage("1 g").frequency("3 fois par jour")
            .status(status).build();
        rx.setId(UUID.randomUUID());
        rx.setCreatedAt(LocalDateTime.now().minusDays(2));
        orders.add(rx);
        return rx;
    }

    private ParacetamolFacts read() {
        return reader.read(adult, hospitalId, "Paracétamol 500 mg", null, null);
    }

    @Test
    void aChildAndAnotherDrugReadNothing() {
        Patient child = Patient.builder().dateOfBirth(LocalDate.now().minusYears(17)).build();
        child.setId(UUID.randomUUID());
        assertThat(reader.read(child, hospitalId, "Amoxicilline", items.get(2), null)).isEqualTo(ParacetamolFacts.NONE);
        assertThat(reader.read(adult, hospitalId, "Amoxicilline", items.get(2), null)).isEqualTo(ParacetamolFacts.NONE);
        verifyNoInteractions(prescriptions, catalogue, problems, admissions);
    }

    @ParameterizedTest(name = "typed {0}: applies = {1}")
    @CsvSource({
        "Paracétamol 500 mg, true",
        "Acetaminophen 500 mg, true",
        "Doliprane 1000 mg, true",
        "Codoliprane, true",
        "Totapen, false",
        "Paracétamol + codéine, true",
        "Comprimé de paracétamol 1 g, true",
    })
    void aFreeTextNameIsJudgedThroughTheCatalogue(String typed, boolean applies) {
        assertThat(reader.read(adult, hospitalId, typed, null, null).applies()).isEqualTo(applies);
    }

    @Test
    void aBrandPickedFromTheCatalogueApplies() {
        assertThat(reader.read(adult, hospitalId, "Doliprane", doliprane, null).applies()).isTrue();
        ParacetamolFacts combination = reader.read(adult, hospitalId, "Codoliprane", items.get(1), null);
        assertThat(combination.applies()).isTrue();
        assertThat(combination.proposedCombination()).isTrue();
        assertThat(reader.read(adult, hospitalId, "Doliprane", doliprane, null).proposedCombination()).isFalse();
    }

    @ParameterizedTest(name = "{0}: counted = {1}")
    @CsvSource({
        "DRAFT, true",
        "PENDING_SIGNATURE, true",
        "SIGNED, true",
        "DISPENSED, true",
        "PARTIALLY_FILLED, true",
        "CANCELLED, false",
        "DISCONTINUED, false",
        "PARTNER_REJECTED, false",
    })
    void onlyActiveStatusesCount(PrescriptionStatus status, boolean counted) {
        order("Paracétamol", status);
        assertThat(read().otherOrders()).hasSize(counted ? 1 : 0);
    }

    @Test
    void theOrderBeingEditedIsNotAnotherOrder() {
        Prescription edited = order("Paracétamol", PrescriptionStatus.DRAFT);
        assertThat(reader.read(adult, hospitalId, "Paracétamol", null, edited.getId()).otherOrders()).isEmpty();
    }

    @ParameterizedTest(name = "{0} written {1} days ago: counted = {2}")
    @CsvSource({
        "5 days, 5, true",
        "5 days, 6, false",
        "5 jours, 5, true",
        "5 jours, 6, false",
        "pendant 5 jours, 6, false",
        "durante 5 días, 5, true",
        "durante 5 días, 6, false",
        "1 semaine, 7, true",
        "1 semaine, 8, false",
        "2 semanas, 14, true",
        "2 semanas, 15, false",
        "3 mois, 80, true",
        "3 mois, 100, false",
        "1 mes, 40, false",
        // No readable duration: counted inside the window the duplicate-order check uses.
        "'', 6, true",
        "'', 8, false",
        "au long cours, 6, true",
        "au long cours, 8, false",
    })
    void aPastCourseIsNotActive(String duration, int daysAgo, boolean counted) {
        Prescription rx = order("Paracétamol", PrescriptionStatus.DISPENSED);
        rx.setDuration(duration);
        rx.setCreatedAt(LocalDateTime.now().minusDays(daysAgo));
        assertThat(read().otherOrders()).hasSize(counted ? 1 : 0);
    }

    /** Past the window with no readable duration: named, not counted, never dropped. A stated end is over. */
    @Test
    void anOldOrderWithNoDurationMayStillBeInUseAndIsNamed() {
        Prescription old = order("Paracétamol 500 mg", PrescriptionStatus.DISPENSED);
        old.setCreatedAt(LocalDateTime.now().minusDays(30));
        Prescription finished = order("Paracétamol fini", PrescriptionStatus.DISPENSED);
        finished.setDuration("5 jours");
        finished.setCreatedAt(LocalDateTime.now().minusDays(30));
        Prescription recent = order("Paracétamol récent", PrescriptionStatus.SIGNED);
        ParacetamolFacts facts = read();
        assertThat(facts.mayStillBeInUse()).containsExactly("Paracétamol 500 mg");
        assertThat(facts.otherOrders()).extracting(ParacetamolFacts.OtherOrder::order).containsExactly(recent);
        assertThat(facts.otherOrders().get(0).durationRead()).isFalse();
        recent.setDuration("5 jours");
        assertThat(read().otherOrders().get(0).durationRead()).isTrue();
    }

    @Test
    void activeCombinationsAreNamedAndNotCounted() {
        order("Paracétamol + codéine 500/30", PrescriptionStatus.SIGNED);
        order("Codoliprane", PrescriptionStatus.SIGNED);
        order("Doliprane codéine", PrescriptionStatus.SIGNED);
        Prescription ended = order("Tramadol/paracétamol", PrescriptionStatus.SIGNED);
        ended.setDuration("5 jours");
        ended.setCreatedAt(LocalDateTime.now().minusDays(30));
        order("Tramadol/paracétamol", PrescriptionStatus.CANCELLED);
        ParacetamolFacts facts = read();
        assertThat(facts.combinations()).containsExactly("Paracétamol + codéine 500/30", "Codoliprane", "Doliprane codéine");
        assertThat(facts.otherOrders()).isEmpty();
    }

    /** H2: a paracetamol order is named as such wherever the name stands, and a link to it wins. */
    @Test
    void theNameShapeDoesNotMakeACombination() {
        order("Doliprane (paracétamol) 1 g", PrescriptionStatus.SIGNED);
        order("Comprimé de paracétamol 500 mg", PrescriptionStatus.SIGNED);
        order("Perfalgan paracétamol 1 g", PrescriptionStatus.SIGNED);
        Prescription linked = order("Paracétamol + codéine", PrescriptionStatus.SIGNED);
        linked.setMedicationCatalogItemId(doliprane.getId());
        ParacetamolFacts facts = read();
        assertThat(facts.combinations()).isEmpty();
        assertThat(facts.otherOrders()).hasSize(4);
    }

    /** M3: a child's facts hold the problems for the contraindication, and read no orders. */
    @Test
    void aChildReadsTheProblemsAndNoOrders() {
        Patient child = Patient.builder().dateOfBirth(LocalDate.now().minusYears(10)).build();
        child.setId(adult.getId());
        problem("K72.9", ProblemStatus.ACTIVE, false).setSeverity(ProblemSeverity.SEVERE);
        order("Paracétamol", PrescriptionStatus.SIGNED);
        ParacetamolFacts facts = reader.read(child, hospitalId, "Paracétamol", null, null);
        assertThat(facts.risks()).contains(ParacetamolRisk.SEVERE_HEPATIC);
        assertThat(facts.otherOrders()).isEmpty();
    }

    /** The row's severity is its own problem's, never a secondary code's. */
    @Test
    void aSecondaryHepaticCodeHasNoRecordedDegree() {
        PatientProblem row = problem("A41.9", ProblemStatus.ACTIVE, false);
        row.setSeverity(ProblemSeverity.SEVERE);
        row.setDiagnosisCodes(new ArrayList<>(List.of("K72.9")));
        assertThat(read().risks()).containsExactlyInAnyOrder(
            ParacetamolRisk.TWO_GRAM_GROUP, ParacetamolRisk.HEPATIC_DEGREE_UNRECORDED);
    }

    @Test
    void anOrderNamedPastItsDoseIsStillRead() {
        order("Paracétamol500mg", PrescriptionStatus.SIGNED);
        assertThat(read().otherOrders()).hasSize(1);
    }

    /** A secondary code does not erase the degree a hepatic problem records. */
    @ParameterizedTest(name = "{0}")
    @CsvSource({"MILD, TWO_GRAM_GROUP", "SEVERE, SEVERE_HEPATIC"})
    void aRepeatedHepaticCodeKeepsTheProblemsDegree(ProblemSeverity severity, ParacetamolRisk expected) {
        PatientProblem row = problem("K72.9", ProblemStatus.ACTIVE, false);
        row.setSeverity(severity);
        row.setDiagnosisCodes(new ArrayList<>(List.of("K72.9", "K70.4")));
        assertThat(read().risks()).containsExactly(expected);
    }

    /** L5. */
    @Test
    void withheldRowsAndNoHospitalAreCounted() {
        problem("F10.2", ProblemStatus.ACTIVE, false)
            .setSensitivityCategory(com.example.hms.enums.SensitivityCategory.SUBSTANCE_USE);
        assertThat(read().noActingHospital()).isTrue();
        assertThat(read().risks()).isEmpty();
        assertThat(reader.read(adult, null, "Paracétamol", null, null).noHospital()).isTrue();
        assertThat(read().noHospital()).isFalse();
    }

    @Test
    void noBirthDateStillReadsTheFacts() {
        Patient unknown = Patient.builder().build();
        unknown.setId(adult.getId());
        assertThat(reader.read(unknown, hospitalId, "Paracétamol", null, null).applies()).isTrue();
    }

    @Test
    void anOrderWithNoDurationOnAnOpenStayRunsOn() {
        Prescription rx = order("Paracétamol", PrescriptionStatus.SIGNED);
        rx.setCreatedAt(LocalDateTime.now().minusDays(30));
        assertThat(read().otherOrders()).isEmpty();
        rx.setEncounter(com.example.hms.model.Encounter.builder().admissionId(UUID.randomUUID()).build());
        assertThat(read().otherOrders()).hasSize(1);
    }

    @ParameterizedTest(name = "one-off {0}, written {1} h ago: counted = {2}")
    @CsvSource({"STAT, 23, true", "STAT, 25, false", "'', 23, true", "'', 25, false"})
    void aOneOffCountsForItsDay(String frequency, int hoursAgo, boolean counted) {
        Prescription rx = order("Paracétamol", PrescriptionStatus.DISPENSED);
        rx.setFrequency(frequency);
        rx.setCreatedAt(LocalDateTime.now().minusHours(hoursAgo));
        assertThat(read().otherOrders()).hasSize(counted ? 1 : 0);
    }

    @Test
    void anActingHospitalIsReadNotThePatientsFirstHospital() {
        UUID acting = UUID.randomUUID();
        Prescription here = Prescription.builder().medicationName("Paracétamol").dosage("1 g").frequency("q6h")
            .status(PrescriptionStatus.SIGNED).build();
        here.setId(UUID.randomUUID());
        here.setCreatedAt(LocalDateTime.now());
        when(prescriptions.findByPatient_IdAndHospital_Id(adult.getId(), acting)).thenReturn(List.of(here));
        when(problems.findByPatient_IdAndHospital_Id(adult.getId(), acting)).thenReturn(List.of());
        when(catalogue.findActivePage(eq(acting), any(Pageable.class))).thenAnswer(i -> new PageImpl<>(List.of()));
        order("Paracétamol", PrescriptionStatus.SIGNED);
        problem("F10.2", ProblemStatus.ACTIVE, false);
        com.example.hms.security.context.HospitalContextHolder.setContext(
            com.example.hms.security.context.HospitalContext.builder().activeHospitalId(acting).build());
        try {
            ParacetamolFacts facts = read();
            assertThat(facts.otherOrders()).extracting(ParacetamolFacts.OtherOrder::order).containsExactly(here);
            assertThat(facts.risks()).isEmpty();
        } finally {
            com.example.hms.security.context.HospitalContextHolder.clear();
        }
    }

    @Test
    void noActingHospitalWithholdsASensitiveRowAndNoHospitalReadsNothing() {
        problem("F10.2", ProblemStatus.ACTIVE, false)
            .setSensitivityCategory(com.example.hms.enums.SensitivityCategory.SUBSTANCE_USE);
        assertThat(read().risks()).isEmpty();
        problem("E80.4", ProblemStatus.ACTIVE, false);
        assertThat(read().risks()).containsExactly(ParacetamolRisk.TWO_GRAM_GROUP);
        order("Paracétamol", PrescriptionStatus.SIGNED);
        ParacetamolFacts none = reader.read(adult, null, "Paracétamol", null, null);
        assertThat(none.applies()).isTrue();
        assertThat(none.otherOrders()).isEmpty();
        assertThat(none.risks()).isEmpty();
    }

    @Test
    void anOrderWrittenDuringAFinishedStayIsNotActive() {
        Prescription rx = order("Paracétamol", PrescriptionStatus.SIGNED);
        rx.setHospital(hospitalOf());
        Admission stay = new Admission();
        stay.setHospital(rx.getHospital());
        stay.setAdmissionDateTime(LocalDateTime.now().minusDays(5));
        stay.setActualDischargeDateTime(LocalDateTime.now().minusDays(1));
        when(admissions.findByPatientIdOrderByAdmissionDateTimeDesc(adult.getId())).thenReturn(List.of(stay));
        assertThat(read().otherOrders()).isEmpty();
        rx.setTakeHome(true);
        assertThat(read().otherOrders()).hasSize(1);
    }

    private com.example.hms.model.Hospital hospitalOf() {
        com.example.hms.model.Hospital hospital = com.example.hms.model.Hospital.builder().build();
        hospital.setId(hospitalId);
        return hospital;
    }

    @Test
    void identityOfTheOtherOrders() {
        order("Doliprane 1000 mg", PrescriptionStatus.SIGNED);
        order("Acétaminophène", PrescriptionStatus.SIGNED);
        order("Paracetamol + codéine 500/30", PrescriptionStatus.SIGNED);
        order("Codoliprane", PrescriptionStatus.SIGNED);
        order("Amoxicilline 1 g", PrescriptionStatus.SIGNED);
        Prescription linked = order("Efferalgan", PrescriptionStatus.SIGNED);
        linked.setMedicationCatalogItemId(doliprane.getId());
        Prescription coded = order("Dafalgan", PrescriptionStatus.SIGNED);
        coded.setMedicationCode(doliprane.getCode());

        assertThat(read().otherOrders()).extracting(o -> o.order().getMedicationName())
            .containsExactly("Doliprane 1000 mg", "Acétaminophène", "Efferalgan", "Dafalgan");
        assertThat(read().otherOrders().get(0).item()).isSameAs(doliprane);
        assertThat(read().otherOrders().get(1).item()).isNull();
        // Its name contradicts the item: it still counts, never with the item's strength (QA D1223).
        assertThat(read().otherOrders().get(2).item()).isNull();
    }

    @Test
    void itemsOfDifferentStrengthsGiveNoStrength() {
        items.add(item("Paracétamol", "Doliprane", "500"));
        order("Doliprane", PrescriptionStatus.SIGNED);
        assertThat(read().otherOrders().get(0).item()).isNull();
        items.remove(items.size() - 1);
        items.add(item("Paracétamol", "Doliprane", "1000"));
        assertThat(read().otherOrders().get(0).item()).isNotNull();
    }

    private PatientProblem problem(String code, ProblemStatus status, boolean removed) {
        PatientProblem row = PatientProblem.builder().problemCode(code).problemDisplay("x").status(status)
            .enteredInError(removed).build();
        rows.add(row);
        return row;
    }

    @Test
    void onlyActiveRowsCount() {
        problem("E80.4", ProblemStatus.RESOLVED, false);
        problem("F10.2", ProblemStatus.INACTIVE, false);
        problem("K72.9", ProblemStatus.ACTIVE, true);
        assertThat(read().risks()).isEmpty();
        problem("E80.4", ProblemStatus.RECURRENCE, false);
        assertThat(read().risks()).containsExactly(ParacetamolRisk.TWO_GRAM_GROUP);
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(nullValues = "null", value = {
        "E43, null, MALNUTRITION_OR_DEHYDRATION",
        "E40, null, MALNUTRITION_OR_DEHYDRATION",
        "E46, null, MALNUTRITION_OR_DEHYDRATION",
        "E44.1, null, MALNUTRITION_OR_DEHYDRATION",
        "E86, null, MALNUTRITION_OR_DEHYDRATION",
        "E86.0, null, MALNUTRITION_OR_DEHYDRATION",
        "E80.4, null, TWO_GRAM_GROUP",
        "e80.4, null, TWO_GRAM_GROUP",
        "F10.2, null, TWO_GRAM_GROUP CHRONIC_ALCOHOLISM",
        "F10.20, null, TWO_GRAM_GROUP CHRONIC_ALCOHOLISM",
        "K72.9, MILD, TWO_GRAM_GROUP",
        "K72.1, MODERATE, TWO_GRAM_GROUP",
        "K72.9, null, TWO_GRAM_GROUP HEPATIC_DEGREE_UNRECORDED",
        "K72.9, UNKNOWN, TWO_GRAM_GROUP HEPATIC_DEGREE_UNRECORDED",
        "K72.9, SEVERE, SEVERE_HEPATIC",
        "K70.4, LIFE_THREATENING, SEVERE_HEPATIC",
        "K71.1, SEVERE, SEVERE_HEPATIC",
    })
    void theCodedGroups(String code, ProblemSeverity severity, String expected) {
        problem(code, ProblemStatus.ACTIVE, false).setSeverity(severity);
        assertThat(read().risks()).extracting(Enum::name).containsExactlyInAnyOrder(expected.split(" "));
    }

    @ParameterizedTest(name = "{0} is no group")
    @CsvSource({"E47", "E39", "E86.1", "E80.5", "F10.1", "F10.3", "K74.6", "K70.3", "K71.2", "K76.0", "I10"})
    void otherCodesAreNoGroup(String code) {
        problem(code, ProblemStatus.ACTIVE, false);
        assertThat(read().risks()).isEmpty();
    }

    @Test
    void freeTextAndIcd11AreNotRead() {
        PatientProblem display = PatientProblem.builder().problemDisplay("Alcoolisme chronique, cirrhose grave")
            .status(ProblemStatus.ACTIVE).build();
        rows.add(display);
        problem("E80.4", ProblemStatus.ACTIVE, false).setIcdVersion("ICD-11");
        assertThat(read().risks()).isEmpty();
    }

    @Test
    void theSecondaryCodesOfARowCount() {
        PatientProblem row = problem("I10", ProblemStatus.ACTIVE, false);
        row.setDiagnosisCodes(new ArrayList<>(List.of("E80.4")));
        assertThat(read().risks()).isEqualTo(Set.of(ParacetamolRisk.TWO_GRAM_GROUP));
    }

    @ParameterizedTest(name = "{0}: alone = {1}")
    @CsvSource(delimiter = '|', value = {
        "Paracetamol | true",
        "Paracétamol 500 mg comprimé | true",
        "PARACETAMOL | true",
        "Paracetamolo | true",
        "Paracétamol sirop 120 mg/5 ml | true",
        "Paracétamol 10 mg/ml | true",
        "Paracétamol (Doliprane) | true",
        "Paracetamol, 500 mg | true",
        "Acetaminophen | true",
        "Acétaminophène | true",
        "Acetaminofén 500 mg | true",
        "Paracétamol + codéine | false",
        "Paracétamol/codéine | false",
        "Paracétamol-codéine | false",
        "Paracétamol codéine 500 mg | false",
        "Paracétamol, codéine | false",
        "Paracetamol caféine | false",
        "Paracétamol 500 mg/codéine 30 mg | false",
        "Paracétamol 500 mg/cp | true",
        "Paracétamol 1 g/sachet | true",
        "Paracétamol-Sandoz | true",
        "Paracétamol 500 mg/tableta | true",
        "Paracétamol 500 mg/pastilla | true",
        "Paracétamol 15 mg/kg | true",
        "Paracétamol 1 g/j | true",
        "Paracétamol IV/PO | true",
        "Paracétamol 1 g comprimé et sirop | true",
        "Paracétamol 1 g avec eau | true",
        "Paracétamol500mg | true",
        "Paracétamol/xyz | false",
        "Acétylcystéine (intoxication au paracétamol) | false",
        "Doliprane (paracétamol) 1 g | true",
        "Comprimé de paracétamol 500 mg | true",
        "Perfalgan paracétamol 1 g | true",
        "Paracétamol et codéine | false",
        "Paracetamol 500/30 mg | false",
        "Tramadol/paracétamol | false",
        "Codéine paracétamol | false",
        "Paracetamolx | false",
        "Doliprane | false",
    })
    void nameTable(String name, boolean alone) {
        assertThat(ParacetamolIdentity.nameIsParacetamolAlone(name)).isEqualTo(alone);
    }

    @Test
    void identityReadsBothInputsAndNeverACombination() {
        MedicationCatalogItem byAtc = MedicationCatalogItem.builder().genericName("Efferalgan").atcCode("N02BE01").build();
        MedicationCatalogItem combo = MedicationCatalogItem.builder().genericName("Tramadol + paracétamol").build();
        assertThat(ParacetamolIdentity.names("Bristopen", doliprane)).isTrue();
        assertThat(ParacetamolIdentity.names("Paracétamol", items.get(2))).isTrue();
        assertThat(ParacetamolIdentity.names("Efferalgan", byAtc)).isTrue();
        assertThat(ParacetamolIdentity.names("Paracétamol", combo)).isFalse();
        // H2: ATC wins over the shape of the generic name, and a link to paracetamol alone over the typed name.
        MedicationCatalogItem atcOddName = MedicationCatalogItem.builder().genericName("Paracétamol et excipient").atcCode("N02BE01").build();
        assertThat(ParacetamolIdentity.isParacetamolAlone(atcOddName)).isTrue();
        assertThat(ParacetamolIdentity.isCombination(atcOddName)).isFalse();
        assertThat(ParacetamolIdentity.isCombinationName("Doliprane (paracétamol) 1 g")).isFalse();
        assertThat(ParacetamolIdentity.isCombinationName("Paracétamol + codéine")).isTrue();
        assertThat(ParacetamolIdentity.names(null, null)).isFalse();
        assertThat(ParacetamolIdentity.names("Paracétamol + codéine", doliprane)).isTrue();
        assertThat(ParacetamolIdentity.names("Paracétamol + codéine", null)).isFalse();
        assertThat(ParacetamolIdentity.isCombinationOrder("Paracétamol + codéine", null)).isTrue();
        assertThat(ParacetamolIdentity.isCombinationOrder("Paracétamol + codéine", doliprane)).isFalse();
        assertThat(ParacetamolIdentity.names("Paracétamol 500 mg - comprimé", null)).isTrue();
        assertThat(ParacetamolIdentity.names("Efferalgan-Odis", doliprane)).isTrue();
        assertThat(ParacetamolIdentity.namesCatalogueItem("Doliprane codéine", doliprane)).isFalse();
    }
}
