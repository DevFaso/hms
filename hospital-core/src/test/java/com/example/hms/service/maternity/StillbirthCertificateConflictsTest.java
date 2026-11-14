package com.example.hms.service.maternity;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.BabyOutcome;
import com.example.hms.enums.DeliveryMode;
import com.example.hms.enums.InBasketItemType;
import com.example.hms.enums.InBasketPriority;
import com.example.hms.enums.PerinatalDeathType;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.DeathRecord;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
import com.example.hms.model.labor.DeliveryNewborn;
import com.example.hms.model.labor.DeliveryRecord;
import com.example.hms.model.labor.FetalDeathEvent;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.clinical.CreateInBasketItemRequest;
import com.example.hms.repository.DeathRecordRepository;
import com.example.hms.repository.DeliveryNewbornRepository;
import com.example.hms.repository.DeliveryRecordRepository;
import com.example.hms.repository.FetalDeathEventRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.InBasketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA D1802 (provisional, user decision 2026-11-14): a baby corrected to born alive while a death certificate declares
 * its stillbirth. The correction stands with its reason, the certificate is untouched, nobody is reactivated, both
 * records are audited, and an URGENT reconciliation task goes to the certificate reviewers until it is reconciled.
 */
class StillbirthCertificateConflictsTest {

    private final FetalDeathEventRepository events = mock(FetalDeathEventRepository.class);
    private final DeliveryRecordRepository deliveries = mock(DeliveryRecordRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final AuditEventLogService audit = mock(AuditEventLogService.class);
    private final DeathRecordRepository deathRecords = mock(DeathRecordRepository.class);
    private final DeliveryNewbornRepository newborns = mock(DeliveryNewbornRepository.class);
    private final UserRoleHospitalAssignmentRepository assignments = mock(UserRoleHospitalAssignmentRepository.class);
    private final InBasketService inBasket = mock(InBasketService.class);

    private final StillbirthCertificateConflicts conflicts = new StillbirthCertificateConflicts(deathRecords, newborns,
        deliveries, events, assignments, inBasket, audit);
    private final FetalDeathEventService service = new FetalDeathEventService(events, deliveries, users, audit);

    private final List<FetalDeathEvent> stored = new ArrayList<>();
    private final List<CreateInBasketItemRequest> filed = new ArrayList<>();
    private Hospital hospital;
    private Patient baby;
    private User actor;
    private User doctor;
    private User midwife;
    private DeliveryRecord delivery;
    private DeathRecord certificate;
    private LocalDateTime diedAt;

    @BeforeEach
    void setUp() {
        service.setCertificateConflicts(conflicts);
        hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        Patient mother = new Patient();
        mother.setId(UUID.randomUUID());
        baby = new Patient();
        baby.setId(UUID.randomUUID());
        diedAt = LocalDateTime.of(2026, 11, 14, 2, 0);
        baby.setDeceasedAt(diedAt);
        actor = user();
        doctor = user();
        midwife = user();
        when(users.getReferenceById(any())).thenAnswer(inv -> {
            User u = new User();
            u.setId(inv.getArgument(0));
            return u;
        });
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
                .filter(e -> e.getBirthOrder() == (int) inv.getArgument(1) && !e.isSuperseded()).findFirst());
        when(events.findByDeliveryRecord_IdOrderByBirthOrderAscCreatedAtAsc(any())).thenAnswer(inv -> List.copyOf(stored));

        delivery = DeliveryRecord.builder().patient(mother).hospital(hospital).birthDateTime(diedAt.minusHours(2))
            .deliveryMode(DeliveryMode.SPONTANEOUS_VAGINAL).numberOfInfants(1).gestationalAgeWeeksAtBirth(28).build();
        delivery.setId(UUID.randomUUID());
        delivery.putBabyOutcome(1, BabyOutcome.STILLBORN);
        service.recordOutcomes(delivery, actor.getId());
        FetalDeathEvent event = stored.get(0);

        certificate = DeathRecord.builder().patient(baby).hospital(hospital).diedAt(diedAt).perinatalDeath(true)
            .perinatalType(PerinatalDeathType.STILLBIRTH).fetalDeathEventId(event.getId()).build();
        certificate.setId(UUID.randomUUID());
        when(deathRecords.findLiveByFetalDeathEventIdIn(any())).thenAnswer(inv -> {
            Collection<UUID> ids = inv.getArgument(0);
            return !certificate.isEnteredInError() && ids.contains(certificate.getFetalDeathEventId())
                ? List.of(certificate) : List.of();
        });
        when(deathRecords.findByPatient_Id(baby.getId())).thenAnswer(inv ->
            certificate.isEnteredInError() ? Optional.empty() : Optional.of(certificate));
        when(newborns.findByDeliveryRecord_IdOrderByBirthOrderAsc(delivery.getId())).thenReturn(List.of(
            DeliveryNewborn.builder().deliveryRecord(delivery).newbornPatient(baby).hospital(hospital).birthOrder(1)
                .build()));
        when(assignments.findLiveUsersAtHospitalWithRoleCodes(hospital.getId(),
            StillbirthCertificateConflicts.REVIEWER_ROLE_CODES)).thenReturn(List.of(doctor, midwife));
        when(inBasket.createItem(any())).thenAnswer(inv -> {
            CreateInBasketItemRequest item = inv.getArgument(0);
            // The store's own rule: one item per reference and reader.
            if (filed.stream().noneMatch(f -> f.getReferenceId().equals(item.getReferenceId())
                && f.getRecipientUserId().equals(item.getRecipientUserId()))) {
                filed.add(item);
            }
            return null;
        });
        when(inBasket.removeAllItemsForReference(any(), anyString())).thenAnswer(inv -> {
            int before = filed.size();
            filed.removeIf(f -> f.getReferenceId().equals(inv.getArgument(0)));
            return before - filed.size();
        });
    }

    private static User user() {
        User u = new User();
        u.setId(UUID.randomUUID());
        return u;
    }

    private FetalDeathEventService.Correction correctToLive(String reason) {
        return service.correctOutcome(delivery, 1, BabyOutcome.LIVE, reason, actor.getId());
    }

    private List<AuditEventRequestDTO> audits() {
        ArgumentCaptor<AuditEventRequestDTO> captor = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(audit, atLeastOnce()).logEvent(captor.capture());
        return captor.getAllValues();
    }

    @Test
    @DisplayName("a blank reason is refused on the reason field, and nothing is filed")
    void aBlankReasonIsRefused() {
        assertThatThrownBy(() -> correctToLive("  "))
            .isInstanceOfSatisfying(FieldBusinessException.class, e -> assertThat(e.getField()).isEqualTo("reason"));
        assertThat(delivery.outcomeOf(1)).isEqualTo(BabyOutcome.STILLBORN);
        verify(inBasket, never()).createItem(any());
    }

    @Test
    @DisplayName("the correction stands, the certificate is untouched and the baby is not reactivated")
    void theCorrectionStandsAndTheCertificateIsKept() {
        FetalDeathEventService.Correction correction = correctToLive("Heartbeat heard at birth");

        assertThat(delivery.outcomeOf(1)).isEqualTo(BabyOutcome.LIVE);
        assertThat(correction.superseded()).isNotNull();
        assertThat(correction.contradictedCertificates()).containsExactly(certificate.getId());
        assertThat(certificate.isEnteredInError()).isFalse();
        assertThat(certificate.getPerinatalType()).isEqualTo(PerinatalDeathType.STILLBIRTH);
        assertThat(certificate.getFetalDeathEventId()).isEqualTo(stored.get(0).getId());
        assertThat(certificate.getAmendedAt()).isNull();
        assertThat(baby.getDeceasedAt()).as("born alive does not mean alive now").isEqualTo(diedAt);
        verify(deathRecords, never()).save(any());
    }

    @Test
    @DisplayName("both contradictory records are audited, each naming the other")
    void bothRecordsAreAuditedWithTheirLink() {
        correctToLive("Heartbeat heard at birth");

        List<AuditEventRequestDTO> conflictAudits = audits().stream()
            .filter(a -> a.getEventType() == AuditEventType.STILLBIRTH_CERTIFICATE_CONFLICT).toList();
        assertThat(conflictAudits).extracting(AuditEventRequestDTO::getEntityType)
            .containsExactlyInAnyOrder("DEATH_RECORD", "DELIVERY_RECORD");
        assertThat(conflictAudits).allSatisfy(a -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> details = (Map<String, Object>) a.getDetails();
            assertThat(details).containsEntry("deathRecordId", certificate.getId().toString())
                .containsEntry("deliveryRecordId", delivery.getId().toString())
                .containsEntry("birthOrder", 1);
        });
    }

    @Test
    @DisplayName("an URGENT reconciliation task goes to each certificate reviewer of the hospital, once per conflict")
    void anUrgentTaskIsFiledOncePerConflict() {
        correctToLive("Heartbeat heard at birth");
        // A re-saved correction to the same outcome files nothing more; nor does a repeated detection.
        correctToLive("Heartbeat heard at birth");
        conflicts.correctedToLive(delivery, 1, null, "again", actor.getId());

        assertThat(filed).hasSize(2);
        assertThat(filed).extracting(CreateInBasketItemRequest::getRecipientUserId)
            .containsExactlyInAnyOrder(doctor.getId(), midwife.getId());
        assertThat(filed).allSatisfy(item -> {
            assertThat(item.getPriority()).isEqualTo(InBasketPriority.URGENT);
            assertThat(item.getItemType()).isEqualTo(InBasketItemType.TASK);
            assertThat(item.getHospitalId()).isEqualTo(hospital.getId());
            assertThat(item.getReferenceType()).isEqualTo(StillbirthCertificateConflicts.REFERENCE_TYPE);
            assertThat(item.getReferenceId()).isEqualTo(StillbirthCertificateConflicts.reference(certificate.getId()));
            assertThat(item.getBodyArgs()).contains("Heartbeat heard at birth");
        });
    }

    @Test
    @DisplayName("correcting the outcome back to stillborn reconciles the conflict and clears the task")
    void correctingBackClearsTheTask() {
        correctToLive("Heartbeat heard at birth");
        assertThat(filed).isNotEmpty();

        service.correctOutcome(delivery, 1, BabyOutcome.STILLBORN, "Heartbeat was the mother's", actor.getId());

        assertThat(filed).isEmpty();
        assertThat(audits()).extracting(AuditEventRequestDTO::getEventType)
            .contains(AuditEventType.STILLBIRTH_CERTIFICATE_CONFLICT_RECONCILED);
    }

    @Test
    @DisplayName("marking the certificate entered in error clears the task")
    void voidingTheCertificateClearsTheTask() {
        correctToLive("Heartbeat heard at birth");
        certificate.setEnteredInErrorAt(LocalDateTime.now());

        conflicts.certificateChanged(certificate, doctor.getId());

        assertThat(filed).isEmpty();
    }

    @Test
    @DisplayName("an amendment to a neonatal death clears the task; one that keeps the stillbirth does not")
    void onlyAnAmendmentOutOfStillbirthClearsTheTask() {
        correctToLive("Heartbeat heard at birth");

        certificate.setNotes("autopsy pending");
        conflicts.certificateChanged(certificate, doctor.getId());
        assertThat(filed).as("still a stillbirth certificate: still contradicted").hasSize(2);

        certificate.setPerinatalType(PerinatalDeathType.EARLY_NEONATAL);
        conflicts.certificateChanged(certificate, doctor.getId());
        assertThat(filed).isEmpty();
    }

    @Test
    @DisplayName("the register's question: which babies does the delivery now record as born alive")
    void liveBornIdentitiesReadsTheDelivery() {
        correctToLive("Heartbeat heard at birth");
        when(deliveries.findAllById(Set.of(delivery.getId()))).thenReturn(List.of(delivery));

        assertThat(conflicts.liveBornIdentities(List.of(delivery.getId() + "#1")))
            .containsExactly(delivery.getId() + "#1");
        delivery.putBabyOutcome(1, BabyOutcome.STILLBORN);
        assertThat(conflicts.liveBornIdentities(List.of(delivery.getId() + "#1"))).isEmpty();
        verify(deliveries, atLeastOnce()).findAllById(eq(Set.of(delivery.getId())));
    }
}
