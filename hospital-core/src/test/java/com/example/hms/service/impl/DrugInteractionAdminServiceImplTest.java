package com.example.hms.service.impl;

import com.example.hms.enums.InteractionSeverity;
import com.example.hms.exception.BusinessException;
import com.example.hms.mapper.DrugInteractionMapper;
import com.example.hms.model.medication.DrugInteraction;
import com.example.hms.payload.dto.medication.DrugInteractionDTO;
import com.example.hms.repository.DrugInteractionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Knowledge-base curation (P2 #14).
 *
 * <p>The seed migration populates the table once; this is what lets the people
 * qualified to maintain clinical content maintain it. A knowledge base that can
 * only be changed by a developer writing SQL is one that stops being updated.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DrugInteractionAdminServiceImplTest {

    @Mock private DrugInteractionRepository repository;

    private DrugInteractionAdminServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new DrugInteractionAdminServiceImpl(repository, new DrugInteractionMapper());
        when(repository.save(any(DrugInteraction.class))).thenAnswer(i -> i.getArgument(0));
        when(repository.findAnyInteractionBetween(anyString(), anyString())).thenReturn(Optional.empty());
    }

    private DrugInteractionDTO valid() {
        DrugInteractionDTO dto = new DrugInteractionDTO();
        dto.setDrug1Code("11289");
        dto.setDrug1Name("warfarin");
        dto.setDrug2Code("1191");
        dto.setDrug2Name("aspirin");
        dto.setSeverity(InteractionSeverity.MAJOR);
        dto.setDescription("Additive bleeding risk.");
        dto.setRecommendation("Avoid unless a specific indication exists.");
        return dto;
    }

    @Test
    void createStoresAnInteraction() {
        assertThat(service.create(valid())).isNotNull();
        verify(repository).save(any(DrugInteraction.class));
    }

    @Test
    void createRejectsADuplicatePairInEitherOrder() {
        // An interaction is a property of the PAIR. Storing A+B and B+A
        // separately means one of them eventually drifts out of step.
        DrugInteraction existing = new DrugInteraction();
        existing.setActive(true);
        when(repository.findAnyInteractionBetween(anyString(), anyString()))
            .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.create(valid()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("already exists");
        verify(repository, never()).save(any());
    }

    @Test
    void createRefusesToShadowARetiredPairAndPointsAtReactivate() {
        // The old guard used the active-filtered lookup, so a deactivated pair
        // slipped past it and re-creating produced TWO rows for one pair — one
        // inactive, one active — instead of a clean error.
        DrugInteraction retired = new DrugInteraction();
        retired.setActive(false);
        when(repository.findAnyInteractionBetween(anyString(), anyString()))
            .thenReturn(Optional.of(retired));

        assertThatThrownBy(() -> service.create(valid()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("reactivate");
        verify(repository, never()).save(any());
    }

    @Test
    void createRejectsADrugInteractingWithItself() {
        DrugInteractionDTO dto = valid();
        dto.setDrug2Code(dto.getDrug1Code());

        assertThatThrownBy(() -> service.create(dto))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("two different drugs");
    }

    @Test
    void createRejectsAnAlertWithNoRecommendation() {
        // An alert that says "these interact" and nothing else is the kind
        // clinicians learn to dismiss without reading.
        DrugInteractionDTO dto = valid();
        dto.setRecommendation("  ");

        assertThatThrownBy(() -> service.create(dto))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("recommendation is required");
    }

    @Test
    void actionFlagsAreDerivedFromSeverityRatherThanTrusted() {
        // A CONTRAINDICATED pair that does not require avoidance is a data bug
        // waiting to reach a prescriber, so the flags cannot contradict severity.
        DrugInteractionDTO dto = valid();
        dto.setSeverity(InteractionSeverity.CONTRAINDICATED);

        service.create(dto);

        org.mockito.ArgumentCaptor<DrugInteraction> captor =
            org.mockito.ArgumentCaptor.forClass(DrugInteraction.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().isRequiresAvoidance()).isTrue();
        assertThat(captor.getValue().isRequiresDoseAdjustment()).isTrue();
    }

    @Test
    void moderateSeverityDoesNotDemandAvoidance() {
        DrugInteractionDTO dto = valid();
        dto.setSeverity(InteractionSeverity.MODERATE);

        service.create(dto);

        org.mockito.ArgumentCaptor<DrugInteraction> captor =
            org.mockito.ArgumentCaptor.forClass(DrugInteraction.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().isRequiresAvoidance()).isFalse();
    }

    @Test
    void deactivateRetiresRatherThanDeletes() {
        // One that has fired is part of the record of what a prescriber was
        // shown; deleting the row rewrites that history.
        UUID id = UUID.randomUUID();
        DrugInteraction entity = new DrugInteraction();
        entity.setId(id);
        entity.setActive(true);
        when(repository.findById(id)).thenReturn(Optional.of(entity));

        service.deactivate(id);

        assertThat(entity.isActive()).isFalse();
        verify(repository, never()).delete(any());
    }

    @Test
    void reactivatePutsARetiredPairBackIntoEveryCheckingLayer() {
        // Before this method existed, deactivation was a one-way door: every
        // read hard-filters active = true and nothing ever set the flag back —
        // platform-wide, since the KB has no tenant column.
        UUID id = UUID.randomUUID();
        DrugInteraction entity = new DrugInteraction();
        entity.setId(id);
        entity.setActive(false);
        when(repository.findById(id)).thenReturn(Optional.of(entity));

        DrugInteractionDTO dto = service.reactivate(id);

        assertThat(entity.isActive()).isTrue();
        assertThat(dto.isActive()).isTrue();
    }

    @Test
    void includeRetiredMeansActivePlusRetired_neverRetiredOnly() {
        // ?severity=MAJOR&activeOnly=false used to pass activeOnly into the
        // ACTIVE column predicate, returning only the INACTIVE majors — the one
        // query an admin needs to find a row to reactivate excluded every
        // active one.
        DrugInteraction active = new DrugInteraction();
        active.setActive(true);
        active.setSeverity(InteractionSeverity.MAJOR);
        DrugInteraction retired = new DrugInteraction();
        retired.setActive(false);
        retired.setSeverity(InteractionSeverity.MAJOR);
        when(repository.findBySeverityOrderByDrug1NameAsc(InteractionSeverity.MAJOR))
            .thenReturn(java.util.List.of(active, retired));

        assertThat(service.list(InteractionSeverity.MAJOR, false)).hasSize(2);
        assertThat(service.list(InteractionSeverity.MAJOR, true)).hasSize(1);
    }

    @Test
    void curationNoteAndMonitoringIntervalSurviveTheWritePath() {
        // Both fields existed on the entity and the read side while the write
        // path silently dropped them.
        DrugInteractionDTO dto = valid();
        dto.setNotes("Retired duplicate of the amiodarone set; see BNF 86.");
        dto.setMonitoringIntervalHours(48);

        service.create(dto);

        org.mockito.ArgumentCaptor<DrugInteraction> captor =
            org.mockito.ArgumentCaptor.forClass(DrugInteraction.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getNotes()).contains("BNF 86");
        assertThat(captor.getValue().getMonitoringIntervalHours()).isEqualTo(48);
    }
}
