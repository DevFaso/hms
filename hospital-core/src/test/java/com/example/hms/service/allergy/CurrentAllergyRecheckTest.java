package com.example.hms.service.allergy;

import com.example.hms.cdshooks.CdsCriticalBlockException;
import com.example.hms.enums.AllergySeverity;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientAllergy;
import com.example.hms.model.Prescription;
import com.example.hms.repository.PatientAllergyRepository;
import com.example.hms.utility.MessageUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CurrentAllergyRecheckTest {

    @AfterEach
    void resetMessages() {
        MessageUtil.setMessageSource(null);
    }

    @Test
    void addingOrEditingAnotherMatchingAllergyInvalidatesTheAcknowledgement() {
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        Prescription rx = new Prescription();
        rx.setId(UUID.randomUUID());
        rx.setPatient(patient);
        PatientAllergy severe = allergy("Penicillin", AllergySeverity.SEVERE);
        PatientAllergy other = allergy("Amoxicillin", AllergySeverity.MILD);
        PatientAllergyRepository repository = mock(PatientAllergyRepository.class);
        CurrentAllergyRecheck check = new CurrentAllergyRecheck(repository, null, null);
        when(repository.findByPatient_Id(patient.getId())).thenReturn(List.of(severe));
        String token = check.cardsFor(rx, "Amoxicillin").getFirst().exceptionKey();

        when(repository.findByPatient_Id(patient.getId())).thenReturn(List.of(severe, other));
        assertStale(check, rx, token);
        String updated = check.cardsFor(rx, "Amoxicillin").getFirst().exceptionKey();
        other.setReaction("New reaction documented");
        assertStale(check, rx, updated);

        String fresh = check.cardsFor(rx, "Amoxicillin").getFirst().exceptionKey();
        when(repository.findByPatient_Id(patient.getId())).thenReturn(List.of(other, severe));
        assertThat(check.requireAcknowledged(CurrentAllergyRecheck.Gate.DISPENSE, rx,
            "Amoxicillin", "Prescriber consulted", fresh)).isNotNull();
    }

    @Test
    void overflowingNotesAreRefusedWithoutDiscardingEitherPartAtAnyGate() {
        MessageUtil.setMessageSource(com.example.hms.i18n.TestMessageSources.bundles());
        PatientAllergy allergy = allergy("Penicillin", AllergySeverity.SEVERE);
        CurrentAllergyRecheck.Stop stop = CurrentAllergyRecheck.stop(UUID.randomUUID(),
            "Amoxicillin", List.of(), List.of(allergy)).orElseThrow();
        for (CurrentAllergyRecheck.Gate gate : CurrentAllergyRecheck.Gate.values()) {
            CurrentAllergyRecheck.Acknowledgement ack = new CurrentAllergyRecheck.Acknowledgement(
                gate, stop, "Prescriber consulted: graded challenge ordered");
            for (int limit : List.of(1000, 1024, 2000)) {
                assertThatThrownBy(() -> CurrentAllergyRecheck.withRecordLine("N".repeat(limit), ack, limit))
                    .isInstanceOf(com.example.hms.exception.FieldBusinessException.class)
                    .hasFieldOrPropertyWithValue("field", CurrentAllergyRecheck.ACK_FIELD);
                String line = CurrentAllergyRecheck.recordLine(ack);
                String fitting = "N".repeat(limit - line.length() - 1);
                assertThat(CurrentAllergyRecheck.withRecordLine(fitting, ack, limit))
                    .isEqualTo(line + "\n" + fitting).hasSize(limit);
            }
        }
    }

    @Test
    void eachGateRejectsMissingReasonsAndStaleTokensButAcceptsTheCurrentCard() {
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        Prescription rx = new Prescription();
        rx.setId(UUID.randomUUID());
        rx.setPatient(patient);
        PatientAllergyRepository repository = mock(PatientAllergyRepository.class);
        when(repository.findByPatient_Id(patient.getId()))
            .thenReturn(List.of(allergy("Penicillin", AllergySeverity.SEVERE)));
        CurrentAllergyRecheck check = new CurrentAllergyRecheck(repository, null, null);
        String token = check.cardsFor(rx, "Amoxicillin").getFirst().exceptionKey();
        for (CurrentAllergyRecheck.Gate gate : CurrentAllergyRecheck.Gate.values()) {
            for (String reason : java.util.Arrays.asList(null, "", "   ")) {
                assertThatThrownBy(() -> check.requireAcknowledged(gate, rx, "Amoxicillin", reason, token))
                    .isInstanceOf(CdsCriticalBlockException.class);
            }
            assertThatThrownBy(() -> check.requireAcknowledged(gate, rx, "Amoxicillin", "Reviewed", null))
                .isInstanceOf(CdsCriticalBlockException.class);
            assertThat(check.requireAcknowledged(gate, rx, "Amoxicillin", "  Reviewed  ", token).reason())
                .isEqualTo("Reviewed");
            assertThat(check.requireAcknowledged(gate, rx, "Amoxicillin", "Reviewed",
                token.substring(CurrentAllergyRecheck.KEY_PREFIX.length()))).isNotNull();
        }
    }

    @Test
    void inactiveAllergiesDoNotBlockAndUnrecognisedDrugsRequireReview() {
        PatientAllergy penicillin = allergy("Penicillin", AllergySeverity.SEVERE);
        var stop = CurrentAllergyRecheck.stop(UUID.randomUUID(), "Unknown local product",
            List.of(), List.of(penicillin)).orElseThrow();
        assertThat(stop.kind()).isEqualTo("unverifiable");
        assertThat(stop.card().indicator()).isEqualTo(com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard.Indicator.CRITICAL);
        penicillin.setActive(false);
        assertThat(CurrentAllergyRecheck.stop(UUID.randomUUID(), "Amoxicillin", List.of(), List.of(penicillin))).isEmpty();
        assertThat(CurrentAllergyRecheck.stop(null, null, List.of(), List.of(penicillin))).isEmpty();
        assertThat(CurrentAllergyRecheck.stop(null, "  ", List.of(), List.of(penicillin))).isEmpty();
        assertThat(CurrentAllergyRecheck.stop(null, "Amoxicillin", List.of(), null)).isEmpty();
        assertThat(new CurrentAllergyRecheck(null, null, null).requireAcknowledged(
            CurrentAllergyRecheck.Gate.MAR_DOSE, null, "Amoxicillin", null, null)).isNull();
    }

    @Test
    void crossReactivityRemainsAnAdvisoryWithoutAnAcknowledgementStop() {
        var cards = CurrentAllergyRecheck.cards(UUID.randomUUID(), "Ceftriaxone", List.of(),
            List.of(allergy("Penicillin", AllergySeverity.SEVERE)));
        assertThat(cards).isNotEmpty().allSatisfy(card -> {
            assertThat(card.indicator()).isEqualTo(com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard.Indicator.WARNING);
            assertThat(card.exceptionKey()).isNull();
        });
    }

    @Test
    void queueAllergiesAreGroupedByPatientWithoutIncludingUnattachedRows() {
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        PatientAllergy a = allergy("Penicillin", AllergySeverity.SEVERE);
        a.setPatient(patient);
        PatientAllergyRepository repository = mock(PatientAllergyRepository.class);
        when(repository.findByPatient_IdIn(List.of(patient.getId())))
            .thenReturn(List.of(a, allergy("Other", AllergySeverity.MILD)));
        CurrentAllergyRecheck check = new CurrentAllergyRecheck(repository, null, null);
        assertThat(check.currentAllergies(List.of(patient.getId()))).containsOnlyKeys(patient.getId());
        assertThat(check.currentAllergies(List.of(patient.getId())).get(patient.getId())).containsExactly(a);
        assertThat(check.currentAllergies(List.<UUID>of())).isEmpty();
        assertThat(check.currentAllergies((java.util.Collection<UUID>) null)).isEmpty();
        assertThat(check.currentAllergies((Prescription) null)).isEmpty();
        assertThat(check.cardsFor(new Prescription(), "Amoxicillin")).isEmpty();
    }

    @Test
    void catalogueUsesBothTheLinkedItemAndTheTypedDrugWithoutDuplicates() {
        var hospital = new com.example.hms.model.Hospital();
        hospital.setId(UUID.randomUUID());
        var rx = new Prescription();
        rx.setHospital(hospital);
        var linked = new com.example.hms.model.medication.MedicationCatalogItem();
        linked.setId(UUID.randomUUID());
        linked.setNameFr("Amoxicillin");
        rx.setMedicationCatalogItemId(linked.getId());
        var other = new com.example.hms.model.medication.MedicationCatalogItem();
        other.setId(UUID.randomUUID());
        other.setNameFr("Paracetamol");
        var catalogue = mock(com.example.hms.repository.MedicationCatalogItemRepository.class);
        when(catalogue.findByIdAndHospital_Id(linked.getId(), hospital.getId())).thenReturn(java.util.Optional.of(linked));
        when(catalogue.findActivePage(org.mockito.ArgumentMatchers.eq(hospital.getId()), org.mockito.ArgumentMatchers.any()))
            .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(linked, other)));
        CurrentAllergyRecheck check = new CurrentAllergyRecheck(null, catalogue, null);
        assertThat(check.itemsFor(rx, "Amoxicillin")).containsExactly(linked);
        assertThat(check.itemsFor(rx, "Paracetamol")).containsExactly(linked, other);
        assertThat(CurrentAllergyRecheck.itemsFor(null, linked.getId(), java.util.Arrays.asList(null, linked, other)))
            .containsExactly(linked);
        assertThat(CurrentAllergyRecheck.itemsFor("Amoxicillin", null, null)).isEmpty();
        assertThat(check.activeCatalogue(null)).isEmpty();
    }

    @Test
    void auditWaitsForCommitAndKeepsIdsAndDigestRatherThanTheReason() {
        var audit = mock(com.example.hms.service.AuditEventLogService.class);
        CurrentAllergyRecheck check = new CurrentAllergyRecheck(null, null, audit);
        var stop = CurrentAllergyRecheck.stop(UUID.randomUUID(), "Amoxicillin", List.of(),
            List.of(allergy("Penicillin", AllergySeverity.SEVERE))).orElseThrow();
        var ack = new CurrentAllergyRecheck.Acknowledgement(CurrentAllergyRecheck.Gate.DISPENSE, stop, "Reviewed treatment plan");
        UUID actor = UUID.randomUUID();
        UUID hospital = UUID.randomUUID();
        UUID patient = UUID.randomUUID();
        UUID resource = UUID.randomUUID();
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            check.auditAfterCommit(ack, actor, hospital, patient, resource);
            org.mockito.Mockito.verifyNoInteractions(audit);
            org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()
                .forEach(org.springframework.transaction.support.TransactionSynchronization::afterCommit);
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
        var captured = org.mockito.ArgumentCaptor.forClass(com.example.hms.payload.dto.AuditEventRequestDTO.class);
        org.mockito.Mockito.verify(audit).logEvent(captured.capture());
        var event = captured.getValue();
        assertThat(event.getUserId()).isEqualTo(actor);
        assertThat(event.getHospitalId()).isEqualTo(hospital);
        assertThat(event.getPatientId()).isEqualTo(patient);
        assertThat(event.getResourceId()).isEqualTo(resource.toString());
        assertThat(event.getEntityType()).isEqualTo("DISPENSE");
        assertThat(event.getEventType()).isEqualTo(com.example.hms.enums.AuditEventType.DISPENSE_ALLERGY_ACKNOWLEDGED);
        assertThat(event.getDetails().toString()).contains("reasonDigest", stop.token()).doesNotContain(ack.reason());
    }

    @Test
    void auditDoesNotRunAfterRollback() {
        var audit = mock(com.example.hms.service.AuditEventLogService.class);
        CurrentAllergyRecheck check = new CurrentAllergyRecheck(null, null, audit);
        var stop = CurrentAllergyRecheck.stop(UUID.randomUUID(), "Amoxicillin", List.of(),
            List.of(allergy("Penicillin", AllergySeverity.SEVERE))).orElseThrow();
        var ack = new CurrentAllergyRecheck.Acknowledgement(CurrentAllergyRecheck.Gate.MAR_DOSE, stop, "Reviewed");
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            check.auditAfterCommit(ack, null, null, null, null);
            org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()
                .forEach(callback -> callback.afterCompletion(org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK));
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
        org.mockito.Mockito.verifyNoInteractions(audit);
    }

    @Test
    void notesWithoutAnAcknowledgementAreUnchangedAndShortNotesRetainBothParts() {
        MessageUtil.setMessageSource(com.example.hms.i18n.TestMessageSources.bundles());
        assertThat(CurrentAllergyRecheck.withRecordLine("Existing", null, 1000)).isEqualTo("Existing");
        var stop = CurrentAllergyRecheck.stop(UUID.randomUUID(), "Amoxicillin", List.of(),
            List.of(allergy("Penicillin", AllergySeverity.SEVERE))).orElseThrow();
        var ack = new CurrentAllergyRecheck.Acknowledgement(CurrentAllergyRecheck.Gate.DISPENSE, stop, "Reviewed");
        assertThat(CurrentAllergyRecheck.withRecordLine("Existing", ack, 1000)).contains("Existing", "Reviewed");
        assertThat(CurrentAllergyRecheck.withRecordLine(null, ack, 1000)).isEqualTo(CurrentAllergyRecheck.recordLine(ack));
    }

    @Test
    void noAllergiesSkipCatalogueWorkAtEveryGate() {
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        Prescription rx = new Prescription();
        rx.setPatient(patient);
        var hospital = new com.example.hms.model.Hospital();
        hospital.setId(UUID.randomUUID());
        rx.setHospital(hospital);
        rx.setMedicationCatalogItemId(UUID.randomUUID());
        var repository = mock(PatientAllergyRepository.class);
        var catalogue = mock(com.example.hms.repository.MedicationCatalogItemRepository.class);
        when(repository.findByPatient_Id(patient.getId())).thenReturn(List.of());
        CurrentAllergyRecheck check = new CurrentAllergyRecheck(repository, catalogue, null);
        for (var gate : CurrentAllergyRecheck.Gate.values()) {
            assertThat(check.requireAcknowledged(gate, rx, "Amoxicillin", null, null)).isNull();
        }
        org.mockito.Mockito.verifyNoInteractions(catalogue);
    }

    @Test
    void drugChangesInvalidateTokensEvenWhenTheSameAllergyStillMatches() {
        UUID order = UUID.randomUUID();
        var allergies = List.of(allergy("Penicillin", AllergySeverity.SEVERE));
        var original = CurrentAllergyRecheck.stop(order, "Amoxicillin", List.of(), allergies).orElseThrow();
        var edited = CurrentAllergyRecheck.stop(order, "Ampicillin", List.of(), allergies).orElseThrow();
        assertThat(edited.token()).isNotEqualTo(original.token());
        var item = new com.example.hms.model.medication.MedicationCatalogItem();
        item.setId(UUID.randomUUID());
        item.setGenericName("Amoxicillin");
        var linked = CurrentAllergyRecheck.stop(order, "Amoxicillin", List.of(item), allergies).orElseThrow();
        item.setGenericName("Ampicillin");
        assertThat(CurrentAllergyRecheck.stop(order, "Amoxicillin", List.of(item), allergies).orElseThrow().token())
            .isNotEqualTo(linked.token());
    }

    @Test
    void pageAndGateUseTheSameInactiveLinkedIdentityWithoutMatchingOtherInactiveItems() {
        var hospital = new com.example.hms.model.Hospital();
        hospital.setId(UUID.randomUUID());
        var item = new com.example.hms.model.medication.MedicationCatalogItem();
        item.setId(UUID.randomUUID());
        item.setNameFr("Local product");
        item.setGenericName("Amoxicillin");
        item.setActive(false);
        Prescription rx = new Prescription();
        rx.setId(UUID.randomUUID());
        rx.setHospital(hospital);
        rx.setMedicationCatalogItemId(item.getId());
        var catalogue = mock(com.example.hms.repository.MedicationCatalogItemRepository.class);
        when(catalogue.findByIdAndHospital_Id(item.getId(), hospital.getId())).thenReturn(java.util.Optional.of(item));
        when(catalogue.findByHospital_IdAndIdIn(hospital.getId(), List.of(item.getId()))).thenReturn(List.of(item));
        CurrentAllergyRecheck check = new CurrentAllergyRecheck(null, catalogue, null);
        var page = check.catalogueForOrders(hospital.getId(), List.of(rx));
        var pageItems = CurrentAllergyRecheck.itemsFor("Local product", item.getId(), page);
        var gateItems = check.itemsFor(rx, "Local product");
        var allergies = List.of(allergy("Penicillin", AllergySeverity.SEVERE));
        assertThat(CurrentAllergyRecheck.cards(rx.getId(), "Local product", pageItems, allergies).getFirst().exceptionKey())
            .isEqualTo(CurrentAllergyRecheck.cards(rx.getId(), "Local product", gateItems, allergies).getFirst().exceptionKey());
        assertThat(CurrentAllergyRecheck.itemsFor("Local product", null, page)).isEmpty();
    }

    private static void assertStale(CurrentAllergyRecheck check, Prescription rx, String token) {
        assertThatThrownBy(() -> check.requireAcknowledged(CurrentAllergyRecheck.Gate.DISPENSE,
            rx, "Amoxicillin", "Prescriber consulted", token))
            .isInstanceOf(CdsCriticalBlockException.class)
            .hasFieldOrPropertyWithValue("field", CurrentAllergyRecheck.ACK_FIELD);
    }

    private static PatientAllergy allergy(String name, AllergySeverity severity) {
        PatientAllergy allergy = new PatientAllergy();
        allergy.setId(UUID.randomUUID());
        allergy.setActive(true);
        allergy.setAllergenDisplay(name);
        allergy.setSeverity(severity);
        return allergy;
    }
}
