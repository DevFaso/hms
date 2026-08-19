package com.example.hms.cdshooks.rules;

import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.cdshooks.dto.CdsHookDtos.Source;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.model.Prescription;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.repository.MedicationCatalogItemRepository;
import com.example.hms.repository.PatientVitalSignRepository;
import com.example.hms.repository.PrescriptionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CdsRuleEngineTest {

    private final PrescriptionRepository prescriptionRepository = mock(PrescriptionRepository.class);
    private final MedicationCatalogItemRepository catalogRepository = mock(MedicationCatalogItemRepository.class);
    private final PatientVitalSignRepository vitalSignRepository = mock(PatientVitalSignRepository.class);

    private CdsRuleEngine engineWith(List<CdsRule> rules) {
        return new CdsRuleEngine(rules, prescriptionRepository, catalogRepository, vitalSignRepository);
    }

    @Test
    void parsesSimpleMgDose() {
        assertThat(CdsRuleEngine.parseDoseMg("500 mg")).isEqualTo(500.0);
    }

    @Test
    void parsesGramsAsMg() {
        assertThat(CdsRuleEngine.parseDoseMg("0.25 g")).isCloseTo(250.0, offset(0.001));
    }

    @Test
    void parsesCommaDecimalSeparator() {
        assertThat(CdsRuleEngine.parseDoseMg("12,5 mg PO BID"))
            .isCloseTo(12.5, offset(0.001));
    }

    @Test
    void parsesUnknownFormatReturnsNull() {
        assertThat(CdsRuleEngine.parseDoseMg("BID after meals")).isNull();
        assertThat(CdsRuleEngine.parseDoseMg(null)).isNull();
        assertThat(CdsRuleEngine.parseDoseMg("")).isNull();
    }

    @Test
    void filtersTerminalStatusFromActivePrescriptionList() {
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        Prescription active = Prescription.builder()
            .medicationName("Amoxicillin")
            .status(PrescriptionStatus.SIGNED)
            .build();
        Prescription terminal = Prescription.builder()
            .medicationName("Ibuprofen")
            .status(PrescriptionStatus.CANCELLED)
            .build();
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId))
            .thenReturn(List.of(active, terminal));

        CdsRuleEngine engine = engineWith(List.of());
        List<Prescription> kept = engine.loadActivePrescriptions(patientId, hospitalId);

        assertThat(kept).containsExactly(active);
    }

    @Test
    void buildContextResolvesRxnormFromCatalog() {
        UUID hospitalId = UUID.randomUUID();
        MedicationCatalogItem item = MedicationCatalogItem.builder()
            .nameFr("amoxicilline").genericName("amoxicillin")
            .code("AMOX-500").rxnormCode("723")
            .build();
        when(catalogRepository.findByHospitalIdAndCode(hospitalId, "AMOX-500"))
            .thenReturn(Optional.of(item));
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(any(), eq(hospitalId)))
            .thenReturn(List.of());

        Patient p = Patient.builder().build();
        p.setId(UUID.randomUUID());
        p.setDateOfBirth(LocalDate.now().minusYears(40));

        CdsRuleEngine engine = engineWith(List.of());
        CdsRuleContext ctx = engine.buildContext(p, hospitalId,
            "Amoxicillin 500", "AMOX-500", "500 mg");

        assertThat(ctx.proposedRxnormCode()).isEqualTo("723");
        assertThat(ctx.proposedDoseMg()).isEqualTo(500.0);
        assertThat(ctx.proposedCatalogItem()).isSameAs(item);
    }

    @Test
    void buildContextLoadsLatestWeight() {
        UUID hospitalId = UUID.randomUUID();
        Patient p = Patient.builder().build();
        p.setId(UUID.randomUUID());
        p.setDateOfBirth(LocalDate.now().minusYears(5));
        PatientVitalSign vs = new PatientVitalSign();
        vs.setWeightKg(20.0);
        when(vitalSignRepository.findFirstByPatient_IdAndHospital_IdOrderByRecordedAtDesc(p.getId(), hospitalId))
            .thenReturn(Optional.of(vs));
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(p.getId(), hospitalId))
            .thenReturn(List.of());

        CdsRuleContext ctx = engineWith(List.of()).buildContext(p, hospitalId,
            "Amoxicillin", null, "500 mg");

        assertThat(ctx.patientWeightKg()).isEqualTo(20.0);
    }

    @Test
    void evaluateAggregatesAllRuleCards() {
        CdsCard cardA = card("from-A");
        CdsCard cardB = card("from-B");
        CdsRule a = stubRule("a", List.of(cardA));
        CdsRule b = stubRule("b", List.of(cardB));

        List<CdsCard> result = engineWith(List.of(a, b)).evaluate(emptyContext());

        assertThat(result).extracting(CdsCard::summary)
            .containsExactly("from-A", "from-B");
    }

    @Test
    void buggyRuleDoesNotBlockOtherRules() {
        CdsRule explosive = new CdsRule() {
            @Override public String id() { return "explosive"; }
            @Override public List<CdsCard> evaluate(CdsRuleContext c) {
                throw new RuntimeException("boom");
            }
        };
        CdsRule healthy = stubRule("healthy", List.of(card("ok")));

        List<CdsCard> result = engineWith(List.of(explosive, healthy)).evaluate(emptyContext());

        assertThat(result).extracting(CdsCard::summary).containsExactly("ok");
    }

    @Test
    void unpagedFallbackWhenHospitalIdMissing() {
        UUID patientId = UUID.randomUUID();
        Prescription active = Prescription.builder()
            .medicationName("Amox")
            .status(PrescriptionStatus.SIGNED)
            .build();
        Page<Prescription> page = new PageImpl<>(List.of(active));
        when(prescriptionRepository.findByPatient_Id(patientId, Pageable.unpaged()))
            .thenReturn(page);

        List<Prescription> active2 = engineWith(List.of()).loadActivePrescriptions(patientId, null);
        assertThat(active2).containsExactly(active);
    }

    /* ------------------------------------------------------------------ */

    private static CdsRule stubRule(String id, List<CdsCard> cards) {
        return new CdsRule() {
            @Override public String id() { return id; }
            @Override public List<CdsCard> evaluate(CdsRuleContext c) { return cards; }
        };
    }

    private static CdsCard card(String summary) {
        return new CdsCard(summary, null, CdsCard.Indicator.INFO,
            new Source("test", null, null), null, null, null, "uuid-" + summary);
    }

    private static CdsRuleContext emptyContext() {
        return new CdsRuleContext(null, null, null, null, null, null, null, null, List.of(), List.of());
    }
}
