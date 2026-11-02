package com.example.hms.cdshooks.bpa;

import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.enums.ConsciousnessLevel;
import com.example.hms.model.BpaProtocol;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientProblem;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.repository.BpaProtocolRepository;
import com.example.hms.repository.PatientProblemRepository;
import com.example.hms.repository.PatientVitalSignRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * QA D1238 (round 25): an acknowledged BPA card hid every later reading for
 * 24 hours, because the card's id is its rule and level only. The class
 * guard: for EVERY BPA rule in the codebase, a triggering reading entered
 * after the acknowledgement brings the card back, and nothing new (or a
 * reading that does not trigger) keeps it acknowledged. A new rule without a
 * triggering reading here fails {@link #everyRuleIsCovered}.
 *
 * <p>The triggering readings are the ones each rule's own test fires on; no
 * clinical value is introduced here.
 */
class BpaAcknowledgementCoversWhatItSawTest {

    private static final LocalDateTime NOW = LocalDateTime.now();
    private static final LocalDateTime ACKNOWLEDGED = NOW.minusHours(2);
    private static final LocalDateTime BEFORE = NOW.minusHours(3);
    private static final LocalDateTime AFTER = NOW.minusMinutes(20);
    private static final LocalDate DUE_AT_30_WEEKS =
        LocalDate.now().minusWeeks(30).plusDays(PregnancyHypertensionProtocolRule.PREGNANCY_DAYS);

    /** A rule and a reading that triggers it, recorded at the given time. */
    record Case(String ruleClass, BpaRule rule, Function<LocalDateTime, PatientVitalSign> triggering,
                List<PatientProblem> problems, LocalDate dueDate) {
        @Override
        public String toString() {
            return ruleClass;
        }
    }

    private static BpaProtocolRepository protocols() {
        BpaProtocolRepository repo = mock(BpaProtocolRepository.class);
        when(repo.findByProtocolCodeAndActiveTrue(anyString())).thenAnswer(inv -> Optional.of(BpaProtocol.builder()
            .protocolCode(inv.getArgument(0)).name("Protocol").summary("Summary").active(true).build()));
        when(repo.findByProtocolCode(anyString())).thenReturn(Optional.empty());
        return repo;
    }

    static List<Case> cases() {
        BpaProtocolRepository repo = protocols();
        return List.of(
            new Case("MalariaFeverProtocolRule", new MalariaFeverProtocolRule(repo),
                at -> PatientVitalSign.builder().temperatureCelsius(39.2).recordedAt(at).build(), List.of(), null),
            new Case("NewsScoreProtocolRule", new NewsScoreProtocolRule(repo),
                at -> PatientVitalSign.builder().respiratoryRateBpm(22).spo2Percent(93).onOxygen(false)
                    .temperatureCelsius(38.5).systolicBpMmHg(120).heartRateBpm(80)
                    .consciousnessLevel(ConsciousnessLevel.ALERT).recordedAt(at).source("NURSE_STATION").build(),
                List.of(), null),
            new Case("ObHemorrhageProtocolRule", new ObHemorrhageProtocolRule(repo),
                at -> PatientVitalSign.builder().heartRateBpm(118).systolicBpMmHg(110).recordedAt(at).build(),
                List.of(PatientProblem.builder().problemCode("Z39.0")
                    .problemDisplay("Encounter for postpartum care and examination").build()), null),
            new Case("SepsisQsofaProtocolRule", new SepsisQsofaProtocolRule(repo),
                at -> PatientVitalSign.builder().respiratoryRateBpm(28).systolicBpMmHg(85)
                    .consciousnessLevel(ConsciousnessLevel.NEW_CONFUSION).recordedAt(at).build(), List.of(), null),
            new Case("PregnancyHypertensionProtocolRule", new PregnancyHypertensionProtocolRule(repo),
                at -> PatientVitalSign.builder().systolicBpMmHg(165).diastolicBpMmHg(100).recordedAt(at).build(),
                List.of(), DUE_AT_30_WEEKS));
    }

    private static BpaRuleEngine engine(BpaRule rule) {
        return new BpaRuleEngine(List.of(rule), mock(PatientVitalSignRepository.class),
            mock(PatientProblemRepository.class), mock(PrescriptionRepository.class));
    }

    private static BpaRuleContext context(Case c, PatientVitalSign... vitals) {
        Patient p = Patient.builder().build();
        p.setId(UUID.randomUUID());
        // Most recent first, as the engine builds it.
        List<PatientVitalSign> newestFirst = new ArrayList<>(List.of(vitals));
        newestFirst.sort((a, b) -> b.getRecordedAt().compareTo(a.getRecordedAt()));
        return new BpaRuleContext(p, UUID.randomUUID(), newestFirst, c.problems(), List.of(), c.dueDate());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void aTriggeringReadingAfterTheAcknowledgementBringsTheCardBack(Case c) {
        BpaRuleEngine engine = engine(c.rule());
        BpaRuleContext seen = context(c, c.triggering().apply(BEFORE));
        List<CdsCard> acknowledged = engine.evaluate(seen);
        assertThat(acknowledged).as("the rule fires on its triggering reading").hasSize(1);
        String cardUuid = acknowledged.get(0).uuid();

        assertThat(engine.raisedAgainSince(seen, cardUuid, ACKNOWLEDGED))
            .as("nothing recorded since the acknowledgement").isFalse();

        BpaRuleContext withNewReading = context(c, c.triggering().apply(BEFORE), c.triggering().apply(AFTER));
        assertThat(engine.raisedAgainSince(withNewReading, cardUuid, ACKNOWLEDGED))
            .as("a new triggering reading").isTrue();

        BpaRuleContext withQuietReading = context(c, c.triggering().apply(BEFORE),
            PatientVitalSign.builder().recordedAt(AFTER).build());
        assertThat(engine.raisedAgainSince(withQuietReading, cardUuid, ACKNOWLEDGED))
            .as("a new reading that does not trigger the rule").isFalse();
    }

    /** QA D1238: a worse level brings the card back; a milder one is not this card. */
    @Test
    void aWorseReadingBringsBackAnAcknowledgedMilderCard() {
        Case pregnancy = cases().get(4);
        BpaRuleEngine engine = engine(pregnancy.rule());
        PatientVitalSign mild = PatientVitalSign.builder().systolicBpMmHg(152).diastolicBpMmHg(102)
            .recordedAt(BEFORE).build();
        PatientVitalSign severe = PatientVitalSign.builder().systolicBpMmHg(162).diastolicBpMmHg(112)
            .recordedAt(AFTER).build();
        String warning = engine.evaluate(context(pregnancy, mild)).get(0).uuid();
        String critical = engine.evaluate(context(pregnancy, severe)).get(0).uuid();
        assertThat(warning).isNotEqualTo(critical);

        assertThat(engine.raisedAgainSince(context(pregnancy, mild, severe), warning, ACKNOWLEDGED)).isTrue();
        PatientVitalSign milderLater = PatientVitalSign.builder().systolicBpMmHg(152).diastolicBpMmHg(102)
            .recordedAt(AFTER).build();
        PatientVitalSign severeBefore = PatientVitalSign.builder().systolicBpMmHg(162).diastolicBpMmHg(112)
            .recordedAt(BEFORE).build();
        assertThat(engine.raisedAgainSince(context(pregnancy, severeBefore, milderLater), critical, ACKNOWLEDGED))
            .isFalse();
    }

    /** A reading entered after the acknowledgement counts, whatever clinical time it carries. */
    @Test
    void aReadingEnteredLateCountsByItsEntryTime() {
        Case pregnancy = cases().get(4);
        BpaRuleEngine engine = engine(pregnancy.rule());
        PatientVitalSign seen = pregnancy.triggering().apply(BEFORE);
        String cardUuid = engine.evaluate(context(pregnancy, seen)).get(0).uuid();
        PatientVitalSign backdated = pregnancy.triggering().apply(NOW.minusHours(4));
        backdated.setCreatedAt(AFTER);
        seen.setCreatedAt(BEFORE);
        assertThat(engine.raisedAgainSince(context(pregnancy, seen, backdated), cardUuid, ACKNOWLEDGED)).isTrue();
    }

    @Test
    void everyRuleIsCovered() throws IOException {
        Path main = Path.of("src/main/java/com/example/hms");
        Pattern implementsRule = Pattern.compile("class\\s+(\\w+)[^{]*\\bimplements\\s+[^{]*\\bBpaRule\\b");
        TreeSet<String> rules = new TreeSet<>();
        try (Stream<Path> files = Files.walk(main)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                Matcher m = implementsRule.matcher(SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8)));
                if (m.find()) {
                    rules.add(m.group(1));
                }
            }
        }
        TreeSet<String> covered = new TreeSet<>();
        cases().forEach(c -> covered.add(c.ruleClass()));
        assertThat(rules).as("BPA rules found").isNotEmpty();
        assertThat(covered).as("every BPA rule has a triggering reading in this guard (D1238)%s", SourceScan.MANDATE)
            .isEqualTo(rules);
    }
}
