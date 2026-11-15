package com.example.hms.service.maternity;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.BabyOutcome;
import com.example.hms.enums.DeliveryMode;
import com.example.hms.enums.FetalDeathEventSource;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
import com.example.hms.model.labor.DeliveryRecord;
import com.example.hms.model.labor.FetalDeathEvent;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.repository.DeliveryRecordRepository;
import com.example.hms.repository.FetalDeathEventRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.service.AuditEventLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** QA D1714: a recorded stillbirth creates a linked fetal-death event, once, and a correction never deletes it. */
class FetalDeathEventServiceTest {

    private static final LocalDateTime BORN = LocalDateTime.of(2026, 11, 13, 4, 20);

    private final FetalDeathEventRepository events = mock(FetalDeathEventRepository.class);
    private final DeliveryRecordRepository deliveries = mock(DeliveryRecordRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final AuditEventLogService audit = mock(AuditEventLogService.class);
    private final FetalDeathEventService service = new FetalDeathEventService(events, deliveries, users, audit);

    /** The rows the repository holds, superseded ones included. */
    private final List<FetalDeathEvent> stored = new ArrayList<>();
    private Hospital hospital;
    private Patient mother;
    private User recorder;

    @BeforeEach
    void setUp() {
        hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        mother = new Patient();
        mother.setId(UUID.randomUUID());
        recorder = new User();
        recorder.setId(UUID.randomUUID());
        when(users.getReferenceById(recorder.getId())).thenReturn(recorder);
        when(events.save(any(FetalDeathEvent.class))).thenAnswer(inv -> {
            FetalDeathEvent row = inv.getArgument(0);
            if (row.getId() == null) {
                row.setId(UUID.randomUUID());
                stored.add(row);
            }
            return row;
        });
        when(events.findFirstByDeliveryRecord_IdAndBirthOrderAndSupersededAtIsNull(any(), anyInt()))
            .thenAnswer(inv -> stored.stream()
                .filter(e -> e.getDeliveryRecord().getId().equals(inv.getArgument(0))
                    && e.getBirthOrder() == (int) inv.getArgument(1) && !e.isSuperseded())
                .findFirst());
        when(events.existsByDeliveryRecord_Id(any())).thenAnswer(inv -> stored.stream()
            .anyMatch(e -> e.getDeliveryRecord().getId().equals(inv.getArgument(0))));
    }

    private DeliveryRecord delivery(Integer gestationWeeks, BabyOutcome... outcomes) {
        DeliveryRecord record = DeliveryRecord.builder()
            .patient(mother)
            .hospital(hospital)
            .birthDateTime(BORN)
            .deliveryMode(DeliveryMode.SPONTANEOUS_VAGINAL)
            .numberOfInfants(outcomes.length)
            .gestationalAgeWeeksAtBirth(gestationWeeks)
            .documentedBy(recorder)
            .build();
        record.setId(UUID.randomUUID());
        for (int i = 0; i < outcomes.length; i++) {
            record.putBabyOutcome(i + 1, outcomes[i]);
        }
        return record;
    }

    private List<AuditEventType> audited() {
        ArgumentCaptor<AuditEventRequestDTO> captor = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(audit, atLeastOnce()).logEvent(captor.capture());
        return captor.getAllValues().stream().map(AuditEventRequestDTO::getEventType).toList();
    }

    @Test
    @DisplayName("a single stillbirth creates one event with every recorded fact")
    void singleStillbirthCreatesOneEvent() {
        DeliveryRecord delivery = delivery(34, BabyOutcome.STILLBORN);

        List<FetalDeathEvent> created = service.recordOutcomes(delivery, recorder.getId());

        assertThat(created).singleElement().satisfies(event -> {
            assertThat(event.getDeliveryRecord()).isSameAs(delivery);
            assertThat(event.getMotherPatient()).isSameAs(mother);
            assertThat(event.getHospital()).isSameAs(hospital);
            assertThat(event.getBirthOrder()).isEqualTo(1);
            assertThat(event.getOccurredAt()).isEqualTo(BORN);
            assertThat(event.getGestationalAgeWeeks()).isEqualTo(34);
            assertThat(event.isNoSignsOfLife()).isTrue();
            assertThat(event.getSource()).isEqualTo(FetalDeathEventSource.DELIVERY);
            assertThat(event.getRecordedBy()).isSameAs(recorder);
        });
        assertThat(delivery.isLiveBirth()).isFalse();
        assertThat(audited()).containsExactly(AuditEventType.FETAL_DEATH_EVENT_RECORDED);
    }

    @Test
    @DisplayName("mixed twins: only the stillborn baby creates an event")
    void mixedTwinsCreateOneEventForTheStillbornBaby() {
        DeliveryRecord delivery = delivery(37, BabyOutcome.LIVE, BabyOutcome.STILLBORN);

        List<FetalDeathEvent> created = service.recordOutcomes(delivery, recorder.getId());

        assertThat(created).singleElement().extracting(FetalDeathEvent::getBirthOrder).isEqualTo(2);
        assertThat(delivery.isLiveBirth()).as("a delivery with a live baby stays a live birth").isTrue();
        assertThat(delivery.liveOrders()).containsExactly(1);
    }

    @Test
    @DisplayName("a live birth creates no event")
    void liveBirthCreatesNone() {
        assertThat(service.recordOutcomes(delivery(39, BabyOutcome.LIVE), recorder.getId())).isEmpty();
        verify(events, never()).save(any());
    }

    @Test
    @DisplayName("a re-save creates no duplicate")
    void reSaveCreatesNoDuplicate() {
        DeliveryRecord delivery = delivery(30, BabyOutcome.STILLBORN, BabyOutcome.STILLBORN);

        service.recordOutcomes(delivery, recorder.getId());
        List<FetalDeathEvent> again = service.recordOutcomes(delivery, recorder.getId());

        assertThat(again).isEmpty();
        assertThat(stored).hasSize(2).extracting(FetalDeathEvent::getBirthOrder).containsExactly(1, 2);
    }

    @Test
    @DisplayName("stillborn to live supersedes the event, keeps the reason, who and when, and audits it")
    void correctionSupersedesAndKeepsTheReason() {
        DeliveryRecord delivery = delivery(29, BabyOutcome.STILLBORN);
        FetalDeathEvent event = service.recordOutcomes(delivery, recorder.getId()).get(0);

        FetalDeathEventService.Correction correction =
            service.correctOutcome(delivery, 1, BabyOutcome.LIVE, "  Heartbeat found at resuscitation  ", recorder.getId());

        assertThat(correction.changed()).isTrue();
        assertThat(correction.superseded()).isSameAs(event);
        assertThat(event.isSuperseded()).isTrue();
        assertThat(event.getSupersedeReason()).isEqualTo("Heartbeat found at resuscitation");
        assertThat(event.getSupersededBy()).isSameAs(recorder);
        assertThat(event.getSupersededAt()).isNotNull();
        assertThat(stored).as("never deleted").containsExactly(event);
        verify(events, never()).delete(any());
        verify(events, never()).deleteById(any());
        assertThat(delivery.outcomeOf(1)).isEqualTo(BabyOutcome.LIVE);
        assertThat(delivery.isLiveBirth()).isTrue();
        assertThat(audited()).containsExactly(AuditEventType.FETAL_DEATH_EVENT_RECORDED,
            AuditEventType.FETAL_DEATH_EVENT_SUPERSEDED, AuditEventType.DELIVERY_BABY_OUTCOME_CORRECTED);
    }

    @Test
    @DisplayName("live to stillborn creates an event carrying the reason; back again supersedes it")
    void liveToStillbornCreatesAnEventWithTheReason() {
        DeliveryRecord delivery = delivery(38, BabyOutcome.LIVE, BabyOutcome.LIVE);

        FetalDeathEventService.Correction correction =
            service.correctOutcome(delivery, 2, BabyOutcome.STILLBORN, "Recorded on the wrong twin", recorder.getId());

        assertThat(correction.created()).isNotNull();
        assertThat(correction.created().getBirthOrder()).isEqualTo(2);
        assertThat(correction.created().getCreationReason()).isEqualTo("Recorded on the wrong twin");
        assertThat(delivery.liveOrders()).containsExactly(1);

        service.correctOutcome(delivery, 2, BabyOutcome.LIVE, "Second look", recorder.getId());
        assertThat(stored).singleElement().satisfies(event -> assertThat(event.isSuperseded()).isTrue());
    }

    @Test
    @DisplayName("a correction to the outcome already recorded changes nothing")
    void sameOutcomeIsANoOp() {
        DeliveryRecord delivery = delivery(33, BabyOutcome.STILLBORN);
        service.recordOutcomes(delivery, recorder.getId());

        assertThat(service.correctOutcome(delivery, 1, BabyOutcome.STILLBORN, "again", recorder.getId()).changed())
            .isFalse();
        assertThat(stored).hasSize(1);
    }

    @Test
    @DisplayName("a correction needs a reason and a baby of this delivery")
    void correctionNeedsAReasonAndAKnownBaby() {
        DeliveryRecord delivery = delivery(33, BabyOutcome.STILLBORN);

        assertThatThrownBy(() -> service.correctOutcome(delivery, 1, BabyOutcome.LIVE, "  ", recorder.getId()))
            .isInstanceOf(FieldBusinessException.class).hasFieldOrPropertyWithValue("field", "reason");
        assertThatThrownBy(() -> service.correctOutcome(delivery, 2, BabyOutcome.LIVE, "why", recorder.getId()))
            .isInstanceOf(FieldBusinessException.class).hasFieldOrPropertyWithValue("field", "birthOrder");
    }

    @Test
    @DisplayName("a delivery recorded before V293 keeps its other babies' outcomes when one is corrected")
    void legacyFlagIsWrittenOutBeforeACorrection() {
        DeliveryRecord legacy = DeliveryRecord.builder().patient(mother).hospital(hospital).birthDateTime(BORN)
            .liveBirth(true).numberOfInfants(2).build();
        legacy.setId(UUID.randomUUID());

        service.correctOutcome(legacy, 2, BabyOutcome.STILLBORN, "Wrong twin", recorder.getId());

        assertThat(legacy.outcomeOf(1)).isEqualTo(BabyOutcome.LIVE);
        assertThat(legacy.outcomeOf(2)).isEqualTo(BabyOutcome.STILLBORN);
    }

    // ── The historical backfill ─────────────────────────────────────────

    private DeliveryRecord historical(boolean liveBirth, int infants, Integer gestationWeeks) {
        DeliveryRecord record = DeliveryRecord.builder().patient(mother).hospital(hospital).birthDateTime(BORN)
            .liveBirth(liveBirth).numberOfInfants(infants).gestationalAgeWeeksAtBirth(gestationWeeks)
            .documentedBy(recorder).build();
        record.setId(UUID.randomUUID());
        when(deliveries.findById(record.getId())).thenReturn(Optional.of(record));
        return record;
    }

    @Test
    @DisplayName("backfill: one event from the recorded facts only; no gestation stays missing; twins are not guessed")
    void backfillInventsNothing() {
        DeliveryRecord twins = historical(false, 2, null);

        Optional<FetalDeathEvent> event = service.backfillHistorical(twins.getId());

        assertThat(event).hasValueSatisfying(created -> {
            assertThat(created.getBirthOrder()).isEqualTo(1);
            assertThat(created.getGestationalAgeWeeks()).as("missing stays missing, never 0").isNull();
            assertThat(created.getOccurredAt()).isEqualTo(BORN);
            assertThat(created.getSource()).isEqualTo(FetalDeathEventSource.DELIVERY_HISTORICAL);
            assertThat(created.getRecordedBy()).isSameAs(recorder);
        });
        assertThat(stored).as("a single flag yields one event").hasSize(1);
    }

    @Test
    @DisplayName("backfill is idempotent and leaves live births alone")
    void backfillIsIdempotent() {
        DeliveryRecord stillbirth = historical(false, 1, 31);
        DeliveryRecord live = historical(true, 1, 39);

        assertThat(service.backfillHistorical(stillbirth.getId())).isPresent();
        assertThat(service.backfillHistorical(stillbirth.getId())).isEmpty();
        assertThat(service.backfillHistorical(live.getId())).isEmpty();
        assertThat(stored).singleElement().extracting(FetalDeathEvent::getGestationalAgeWeeks).isEqualTo(31);
    }

    @Test
    @DisplayName("backfill never recreates an event a correction superseded")
    void backfillSkipsACorrectedDelivery() {
        DeliveryRecord stillbirth = historical(false, 1, 31);
        service.backfillHistorical(stillbirth.getId());
        stored.get(0).setSupersededAt(BORN.plusDays(1));
        stored.get(0).setSupersedeReason("corrected");

        assertThat(service.backfillHistorical(stillbirth.getId())).isEmpty();
        verify(events, never()).delete(any());
        assertThat(stored).hasSize(1);
        verify(deliveries, never()).save(eq(stillbirth));
    }

    // ── Rex on #1144: every correction audits its reason and actor ──

    private AuditEventRequestDTO lastCorrectionAudit() {
        ArgumentCaptor<AuditEventRequestDTO> captor = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(audit, atLeastOnce()).logEvent(captor.capture());
        return captor.getAllValues().stream()
            .filter(e -> e.getEventType() == AuditEventType.DELIVERY_BABY_OUTCOME_CORRECTED)
            .reduce((a, b) -> b).orElseThrow();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aLiveToLiveCorrectionStillAuditsTheReasonAndTheActor() {
        DeliveryRecord delivery = delivery(38, BabyOutcome.LIVE);

        service.correctOutcome(delivery, 1, BabyOutcome.LIVE, "Checked again", recorder.getId());

        AuditEventRequestDTO event = lastCorrectionAudit();
        assertThat(event.getUserId()).isEqualTo(recorder.getId());
        assertThat((java.util.Map<String, Object>) event.getDetails()).containsEntry("reason", "Checked again")
            .containsEntry("changed", false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aCorrectionWithNoEventToSupersedeStillAuditsTheReason() {
        DeliveryRecord legacy = DeliveryRecord.builder().patient(mother).hospital(hospital).birthDateTime(BORN)
            .liveBirth(false).numberOfInfants(2).build();
        legacy.setId(UUID.randomUUID());

        // Baby 2 of a single-flag stillbirth has no outcome and no event: made live, nothing is superseded.
        service.correctOutcome(legacy, 2, BabyOutcome.LIVE, "Twin B cried at birth", recorder.getId());

        assertThat(stored).isEmpty();
        assertThat((java.util.Map<String, Object>) lastCorrectionAudit().getDetails())
            .containsEntry("reason", "Twin B cried at birth");
    }
}
