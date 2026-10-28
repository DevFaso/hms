package com.example.hms.cdshooks.rules;

import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.model.Prescription;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.repository.MedicationCatalogItemRepository;
import com.example.hms.repository.PatientVitalSignRepository;
import com.example.hms.repository.PrescriptionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * QA round 16: the CDS rule engine on an order whose catalogue item is known
 * (D922/D935) and on an edit of an existing order (D970).
 */
class CdsRuleEngineRound16Test {

    private final PrescriptionRepository prescriptionRepository = mock(PrescriptionRepository.class);
    private final MedicationCatalogItemRepository catalogRepository = mock(MedicationCatalogItemRepository.class);
    private final PatientVitalSignRepository vitalSignRepository = mock(PatientVitalSignRepository.class);

    private final UUID hospitalId = UUID.randomUUID();

    private Patient patientAged(int years) {
        Patient p = new Patient();
        p.setId(UUID.randomUUID());
        p.setDateOfBirth(LocalDate.now().minusYears(years).minusDays(10));
        return p;
    }

    private void weighs(Patient p, double kg) {
        PatientVitalSign v = new PatientVitalSign();
        v.setWeightKg(kg);
        when(vitalSignRepository.findFirstByPatient_IdAndHospital_IdOrderByRecordedAtDesc(p.getId(), hospitalId))
            .thenReturn(Optional.of(v));
    }

    private MedicationCatalogItem paracetamolWithCeiling(String mgPerKg) {
        MedicationCatalogItem item = new MedicationCatalogItem();
        item.setId(UUID.randomUUID());
        item.setNameFr("Paracétamol");
        item.setGenericName("paracetamol");
        item.setPediatricMaxDoseMgPerKg(new BigDecimal(mgPerKg));
        return item;
    }

    /**
     * Every {@link CdsRule} in the codebase, each built with mocked
     * collaborators: the guard below runs over all of them, so a new rule that
     * compares an edit with its own stored row fails here.
     */
    private List<CdsRule> everyRule() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(CdsRule.class));
        List<CdsRule> rules = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("com.example.hms")) {
            Class<?> type = Class.forName(definition.getBeanClassName());
            if (type.isInterface() || java.lang.reflect.Modifier.isAbstract(type.getModifiers())) {
                continue;
            }
            Constructor<?> constructor = type.getDeclaredConstructors()[0];
            Object[] args = new Object[constructor.getParameterCount()];
            for (int i = 0; i < args.length; i++) {
                args[i] = mock(constructor.getParameterTypes()[i]);
            }
            constructor.setAccessible(true);
            rules.add((CdsRule) constructor.newInstance(args));
        }
        return rules;
    }

    /**
     * QA D970 guard, over every rule: editing the patient's only active order
     * (notes or route changed) raises no card. "Doublon possible" fired
     * against the order itself and kept the edit modal open.
     */
    @Test
    void d970_noRuleRaisesACardAgainstTheOrderBeingEdited() throws Exception {
        List<CdsRule> rules = everyRule();
        assertThat(rules).extracting(CdsRule::id)
            .contains("duplicate-medication-order", "pediatric-dose");
        Patient adult = patientAged(30);
        Prescription edited = Prescription.builder()
            .medicationName("Sulfadoxine-pyriméthamine")
            .status(PrescriptionStatus.DRAFT)
            .build();
        edited.setId(UUID.randomUUID());
        edited.setCreatedAt(LocalDateTime.now().minusHours(1));
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(adult.getId(), hospitalId))
            .thenReturn(List.of(edited));
        CdsRuleEngine engine = new CdsRuleEngine(rules, prescriptionRepository, catalogRepository, vitalSignRepository);

        List<CdsCard> onEdit = engine.evaluateProposedPrescription(adult, hospitalId,
            "Sulfadoxine-pyriméthamine", null, "3 comprimés", null, edited.getId());
        List<CdsCard> onCreate = engine.evaluateProposedPrescription(adult, hospitalId,
            "Sulfadoxine-pyriméthamine", null, "3 comprimés", null, null);

        assertThat(onEdit).isEmpty();
        // The same order written again IS a duplicate: the exclusion is only the edited row.
        assertThat(onCreate).extracting(CdsCard::indicator).contains(CdsCard.Indicator.WARNING);
    }

    /**
     * QA D935 replay (u37.js): paracetamol 1 g for a 19 kg child is 52.6 mg/kg
     * per dose. With the catalogue item handed in (the form's pick, or the
     * name the server resolved), the ceiling fires as CRITICAL.
     */
    @Test
    void d935_theResolvedCatalogItemsCeilingFires() {
        Patient child = patientAged(6);
        weighs(child, 19d);
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(child.getId(), hospitalId)).thenReturn(List.of());
        CdsRuleEngine engine = new CdsRuleEngine(List.of(new PediatricDoseRule()),
            prescriptionRepository, catalogRepository, vitalSignRepository);

        List<CdsCard> cards = engine.evaluateProposedPrescription(child, hospitalId, "Paracétamol", null,
            "1 g", paracetamolWithCeiling("15"), null);

        assertThat(cards).extracting(CdsCard::indicator).containsExactly(CdsCard.Indicator.CRITICAL);
    }

    /** QA D935: "15 mg/kg" is 15 mg per kg of the child's weight, not 15 mg. */
    @Test
    void d935_aWeightBasedDoseIsReadPerKilogram() {
        Patient child = patientAged(6);
        weighs(child, 19d);
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(child.getId(), hospitalId)).thenReturn(List.of());
        CdsRuleEngine engine = new CdsRuleEngine(List.of(new PediatricDoseRule()),
            prescriptionRepository, catalogRepository, vitalSignRepository);

        assertThat(engine.evaluateProposedPrescription(child, hospitalId, "Paracétamol", null,
            "15 mg/kg", paracetamolWithCeiling("15"), null)).isEmpty();
        assertThat(engine.evaluateProposedPrescription(child, hospitalId, "Paracétamol", null,
            "20 mg/kg", paracetamolWithCeiling("15"), null))
            .extracting(CdsCard::indicator).containsExactly(CdsCard.Indicator.CRITICAL);
    }

    @Test
    void d935_parsesWeightBasedDoses() {
        assertThat(CdsRuleEngine.parseDoseMg("15 mg/kg", 19d)).isCloseTo(285d, offset(0.001));
        assertThat(CdsRuleEngine.parseDoseMg("15 mg / kg toutes les 6 h", 20d)).isCloseTo(300d, offset(0.001));
        assertThat(CdsRuleEngine.parseDoseMg("15 mg/kg", null)).isNull();
        assertThat(CdsRuleEngine.parseDoseMg("250 mg", 19d)).isCloseTo(250d, offset(0.001));
    }
}
