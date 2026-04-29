package com.example.hms.cdshooks.rules;

import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.enums.InteractionSeverity;
import com.example.hms.model.medication.DrugInteraction;
import com.example.hms.repository.DrugInteractionRepository;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DrugDrugInteractionRuleTest {

    private final DrugInteractionRepository repo = mock(DrugInteractionRepository.class);
    private final DrugDrugInteractionRule rule = new DrugDrugInteractionRule(repo);

    private static CdsRuleContext contextWith(String proposedRx, String... activeRxnorms) {
        return new CdsRuleContext(
            null, UUID.randomUUID(),
            "Drug X", null, proposedRx, null,
            null, null,
            List.of(), Arrays.asList(activeRxnorms)
        );
    }

    @Test
    void emitsCriticalCardForContraindicatedPair() {
        DrugInteraction di = DrugInteraction.builder()
            .drug1Code("2551").drug1Name("clarithromycin")
            .drug2Code("36567").drug2Name("simvastatin")
            .severity(InteractionSeverity.CONTRAINDICATED)
            .description("CYP3A4 inhibition raises rhabdomyolysis risk.")
            .recommendation("Switch macrolide.")
            .active(true)
            .build();
        when(repo.findInteractionBetween("36567", "2551")).thenReturn(Optional.of(di));

        List<CdsCard> cards = rule.evaluate(contextWith("36567", "2551"));

        assertThat(cards).hasSize(1);
        CdsCard card = cards.get(0);
        assertThat(card.indicator()).isEqualTo(CdsCard.Indicator.CRITICAL);
        assertThat(card.summary())
            .contains("clarithromycin")
            .contains("simvastatin")
            .contains("CONTRAINDICATED");
        assertThat(card.detail()).contains("Switch macrolide");
    }

    @Test
    void emitsWarningCardForModerateSeverity() {
        DrugInteraction di = DrugInteraction.builder()
            .drug1Code("29046").drug1Name("lisinopril")
            .drug2Code("9997").drug2Name("spironolactone")
            .severity(InteractionSeverity.MODERATE)
            .active(true)
            .build();
        when(repo.findInteractionBetween("29046", "9997")).thenReturn(Optional.of(di));

        List<CdsCard> cards = rule.evaluate(contextWith("29046", "9997"));

        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).indicator()).isEqualTo(CdsCard.Indicator.WARNING);
    }

    @Test
    void silentWhenProposedRxnormMissing() {
        assertThat(rule.evaluate(contextWith(null, "11289"))).isEmpty();
    }

    @Test
    void silentWhenNoActivePrescriptions() {
        assertThat(rule.evaluate(contextWith("11289"))).isEmpty();
    }

    @Test
    void skipsBlankAndIdenticalRxnorms() {
        rule.evaluate(contextWith("11289", "", null, "11289"));
        // Only meaningful pairs would query; identical proposed-vs-existing
        // is filtered before the lookup.
        verify(repo, times(0)).findInteractionBetween(eq("11289"), anyString());
    }

    @Test
    void deduplicatesRepeatedPair() {
        DrugInteraction di = DrugInteraction.builder()
            .drug1Code("11289").drug1Name("warfarin")
            .drug2Code("1191").drug2Name("aspirin")
            .severity(InteractionSeverity.MAJOR)
            .active(true)
            .build();
        when(repo.findInteractionBetween("11289", "1191")).thenReturn(Optional.of(di));

        // Patient on aspirin twice (current + carry-over) — pair seen
        // once, only one card.
        List<CdsCard> cards = rule.evaluate(contextWith("11289", "1191", "1191"));

        assertThat(cards).hasSize(1);
        verify(repo, times(1)).findInteractionBetween("11289", "1191");
    }

    @Test
    void inactiveInteractionRowIsIgnored() {
        DrugInteraction di = DrugInteraction.builder()
            .drug1Code("11289").drug1Name("warfarin")
            .drug2Code("1191").drug2Name("aspirin")
            .severity(InteractionSeverity.MAJOR)
            .active(false)
            .build();
        when(repo.findInteractionBetween("11289", "1191")).thenReturn(Optional.of(di));

        assertThat(rule.evaluate(contextWith("11289", "1191"))).isEmpty();
    }

    @Test
    void hasStableId() {
        assertThat(rule.id()).isEqualTo("drug-drug-interaction");
    }
}
