package com.example.hms.service.allergy;

import com.example.hms.cdshooks.CdsCriticalBlockException;
import com.example.hms.enums.AllergyReviewDecision;
import com.example.hms.enums.AllergySeverity;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientAllergy;
import com.example.hms.model.Prescription;
import com.example.hms.model.User;
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
    void addingOrEditingAnotherMatchingAllergyVoidsThePrescribersReview() {
        Prescription rx = order();
        PatientAllergy severe = allergy("Penicillin", AllergySeverity.SEVERE);
        PatientAllergy other = allergy("Amoxicillin", AllergySeverity.MILD);
        PatientAllergyRepository repository = mock(PatientAllergyRepository.class);
        CurrentAllergyRecheck check = new CurrentAllergyRecheck(repository, null, null);
        when(repository.findByPatient_Id(rx.getPatient().getId())).thenReturn(List.of(severe));
        review(check, rx, AllergyReviewDecision.CONTINUE);
        assertThat(check.requirePrescriberReview(CurrentAllergyRecheck.Gate.DISPENSE, rx, "Amoxicillin")).isNotNull();

        when(repository.findByPatient_Id(rx.getPatient().getId())).thenReturn(List.of(severe, other));
        assertHeld(check, rx);
        review(check, rx, AllergyReviewDecision.CONTINUE);
        other.setReaction("New reaction documented");
        assertHeld(check, rx);

        review(check, rx, AllergyReviewDecision.CONTINUE);
        when(repository.findByPatient_Id(rx.getPatient().getId())).thenReturn(List.of(other, severe));
        assertThat(check.requirePrescriberReview(CurrentAllergyRecheck.Gate.DISPENSE, rx, "Amoxicillin")).isNotNull();
    }

    @Test
    void aChangeOfTheOrderAfterTheReviewHoldsItAgain() {
        Prescription rx = order();
        rx.setDosage("500");
        rx.setRoute("PO");
        rx.setFrequency("TID");
        PatientAllergyRepository repository = mock(PatientAllergyRepository.class);
        when(repository.findByPatient_Id(rx.getPatient().getId()))
            .thenReturn(List.of(allergy("Penicillin", AllergySeverity.SEVERE)));
        CurrentAllergyRecheck check = new CurrentAllergyRecheck(repository, null, null);
        for (java.util.function.Consumer<Prescription> change : List.<java.util.function.Consumer<Prescription>>of(
                r -> r.setDosage("1000"), r -> r.setRoute("IV"), r -> r.setFrequency("QID"),
                r -> r.setDoseUnit("g"), r -> r.setAsNeeded(true), r -> r.setDuration("10 days"),
                r -> r.setMedicationCatalogItemId(UUID.randomUUID()))) {
            review(check, rx, AllergyReviewDecision.CONTINUE);
            assertThat(check.requirePrescriberReview(CurrentAllergyRecheck.Gate.MAR_DOSE, rx, "Amoxicillin")).isNotNull();
            change.accept(rx);
            assertHeld(check, rx);
        }
    }

    @Test
    void overflowingNotesAreRefusedWithoutDiscardingEitherPartAtAnyGate() {
        MessageUtil.setMessageSource(com.example.hms.i18n.TestMessageSources.bundles());
        PatientAllergy allergy = allergy("Penicillin", AllergySeverity.SEVERE);
        CurrentAllergyRecheck.Stop stop = CurrentAllergyRecheck.stop(UUID.randomUUID(),
            "Amoxicillin", List.of(), List.of(allergy)).orElseThrow();
        for (CurrentAllergyRecheck.Gate gate : CurrentAllergyRecheck.Gate.values()) {
            CurrentAllergyRecheck.Acknowledgement ack = new CurrentAllergyRecheck.Acknowledgement(
                gate, stop, "token", UUID.randomUUID(), java.time.LocalDateTime.of(2026, 11, 10, 9, 30));
            for (int limit : List.of(1000, 1024, 2000)) {
                assertThatThrownBy(() -> CurrentAllergyRecheck.withRecordLine("N".repeat(limit), ack, limit, "note"))
                    .isExactlyInstanceOf(com.example.hms.exception.FieldBusinessException.class)
                    .hasFieldOrPropertyWithValue("field", "note")
                    .hasMessage(MessageUtil.resolveOrRaw("allergy.recheck.noteTooLong", limit));
                String line = CurrentAllergyRecheck.recordLine(ack);
                String fitting = "N".repeat(limit - line.length() - 1);
                assertThat(CurrentAllergyRecheck.withRecordLine(fitting, ack, limit, "note"))
                    .isEqualTo(line + "\n" + fitting).hasSize(limit);
            }
        }
    }

    @Test
    void recordOverflowResponseDoesNotReopenTheAllergyDialog() {
        MessageUtil.setMessageSource(com.example.hms.i18n.TestMessageSources.bundles());
        CurrentAllergyRecheck.Stop stop = CurrentAllergyRecheck.stop(UUID.randomUUID(),
            "Amoxicillin", List.of(), List.of(allergy("Penicillin", AllergySeverity.SEVERE))).orElseThrow();
        CurrentAllergyRecheck.Acknowledgement ack = new CurrentAllergyRecheck.Acknowledgement(
            CurrentAllergyRecheck.Gate.PHARMACIST_VERIFICATION, stop, "token", null, null);
        com.example.hms.exception.FieldBusinessException error = org.junit.jupiter.api.Assertions.assertThrows(
            com.example.hms.exception.FieldBusinessException.class,
            () -> CurrentAllergyRecheck.withRecordLine("N".repeat(1000), ack, 1000, "note"));
        var response = new com.example.hms.exception.GlobalExceptionHandler().handleFieldBusinessException(error,
            new org.springframework.web.context.request.ServletWebRequest(
                new org.springframework.mock.web.MockHttpServletRequest()));
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isInstanceOfSatisfying(java.util.Map.class, body -> {
            assertThat(body.get("message")).isEqualTo(error.getMessage());
            assertThat(body.get("field")).isEqualTo("note");
            assertThat(body.get("fieldErrors")).isEqualTo(java.util.Map.of("note", error.getMessage()));
            assertThat(body).doesNotContainKey("cdsAdvisories");
        });
    }

    @Test
    void eachGateHoldsAnUnreviewedConflictAndPassesOnlyUnderTheReview() {
        MessageUtil.setMessageSource(com.example.hms.i18n.TestMessageSources.bundles());
        Prescription rx = order();
        PatientAllergyRepository repository = mock(PatientAllergyRepository.class);
        when(repository.findByPatient_Id(rx.getPatient().getId()))
            .thenReturn(List.of(allergy("Penicillin", AllergySeverity.SEVERE)));
        CurrentAllergyRecheck check = new CurrentAllergyRecheck(repository, null, null);
        for (CurrentAllergyRecheck.Gate gate : CurrentAllergyRecheck.Gate.values()) {
            rx.setAllergyReviewDecision(null);
            rx.setAllergyReviewToken(null);
            assertThatThrownBy(() -> check.requirePrescriberReview(gate, rx, "Amoxicillin"))
                .isInstanceOf(CdsCriticalBlockException.class)
                .hasFieldOrPropertyWithValue("field", CurrentAllergyRecheck.HOLD_FIELD)
                .hasMessageContaining("awaiting prescriber review")
                .satisfies(e -> assertThat(((CdsCriticalBlockException) e).getCards()).hasSize(1));
            // A token for another conflict (an earlier one, or one a screen made up) releases nothing.
            rx.setAllergyReviewDecision(AllergyReviewDecision.CONTINUE);
            rx.setAllergyReviewToken("not-this-conflict");
            assertHeld(check, rx);

            User reviewer = review(check, rx, AllergyReviewDecision.CONTINUE);
            CurrentAllergyRecheck.Acknowledgement passed = check.requirePrescriberReview(gate, rx, "Amoxicillin");
            assertThat(passed).isNotNull();
            assertThat(passed.reviewerId()).isEqualTo(reviewer.getId());
            assertThat(passed.reviewToken()).isEqualTo(rx.getAllergyReviewToken());
            assertThat(passed.gate()).isEqualTo(gate);
        }
    }

    @Test
    void aStopDecisionRefusesEveryGateAndSendsStaffToThePrescriber() {
        MessageUtil.setMessageSource(com.example.hms.i18n.TestMessageSources.bundles());
        Prescription rx = order();
        PatientAllergyRepository repository = mock(PatientAllergyRepository.class);
        when(repository.findByPatient_Id(rx.getPatient().getId()))
            .thenReturn(List.of(allergy("Penicillin", AllergySeverity.SEVERE)));
        var notices = mock(com.example.hms.service.InBasketService.class);
        CurrentAllergyRecheck check = new CurrentAllergyRecheck(repository, null, null);
        check.setNotices(notices);
        review(check, rx, AllergyReviewDecision.STOP);
        for (CurrentAllergyRecheck.Gate gate : CurrentAllergyRecheck.Gate.values()) {
            assertThatThrownBy(() -> check.requirePrescriberReview(gate, rx, "Amoxicillin"))
                .isInstanceOf(CdsCriticalBlockException.class)
                .hasFieldOrPropertyWithValue("field", CurrentAllergyRecheck.HOLD_FIELD)
                .hasMessageContaining("contact the prescriber");
        }
        // A decision already taken: nobody is asked again.
        org.mockito.Mockito.verifyNoInteractions(notices);
    }

    @Test
    void theHoldTellsThePrescriberTheSignerAndTheClinicianOnceEachAndNamesThem() {
        MessageUtil.setMessageSource(com.example.hms.i18n.TestMessageSources.bundles());
        Prescription rx = order();
        var hospital = new com.example.hms.model.Hospital();
        hospital.setId(UUID.randomUUID());
        rx.setHospital(hospital);
        com.example.hms.model.Staff prescriber = staff("Awa", "Traore");
        com.example.hms.model.Staff clinician = staff("Issa", "Kabore");
        rx.setStaff(prescriber);
        rx.setSignedBy(prescriber);
        var encounter = new com.example.hms.model.Encounter();
        encounter.setId(UUID.randomUUID());
        encounter.setStaff(clinician);
        rx.setEncounter(encounter);
        PatientAllergyRepository repository = mock(PatientAllergyRepository.class);
        when(repository.findByPatient_Id(rx.getPatient().getId()))
            .thenReturn(List.of(allergy("Penicillin", AllergySeverity.SEVERE)));
        var notices = mock(com.example.hms.service.InBasketService.class);
        CurrentAllergyRecheck check = new CurrentAllergyRecheck(repository, null, null);
        check.setNotices(notices);

        assertThatThrownBy(() -> check.requirePrescriberReview(CurrentAllergyRecheck.Gate.MAR_DOSE, rx, "Amoxicillin"))
            .isInstanceOf(CdsCriticalBlockException.class)
            .hasMessageContaining("Awa Traore").hasMessageContaining("Issa Kabore");
        var filed = org.mockito.ArgumentCaptor.forClass(com.example.hms.payload.dto.clinical.CreateInBasketItemRequest.class);
        org.mockito.Mockito.verify(notices, org.mockito.Mockito.times(2)).createItemInNewTransaction(filed.capture());
        assertThat(filed.getAllValues()).extracting(com.example.hms.payload.dto.clinical.CreateInBasketItemRequest::getRecipientUserId)
            .containsExactly(prescriber.getUser().getId(), clinician.getUser().getId());
        assertThat(filed.getAllValues()).allSatisfy(item -> {
            // One item per order and recipient: the in-basket never files a second (InBasketServiceImpl#fileItem),
            // so a refusal repeated at every gate tells each of them once; a recorded review clears them.
            assertThat(item.getReferenceId()).isEqualTo(rx.getId());
            assertThat(item.getReferenceType()).isEqualTo(CurrentAllergyRecheck.NOTICE_REFERENCE_TYPE);
            assertThat(item.getPriority()).isEqualTo(com.example.hms.enums.InBasketPriority.URGENT);
            assertThat(item.getHospitalId()).isEqualTo(hospital.getId());
        });
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
        assertThat(new CurrentAllergyRecheck(null, null, null).requirePrescriberReview(
            CurrentAllergyRecheck.Gate.MAR_DOSE, null, "Amoxicillin")).isNull();
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
    void auditWaitsForCommitAndKeepsTheReviewItPassedUnder() {
        var audit = mock(com.example.hms.service.AuditEventLogService.class);
        CurrentAllergyRecheck check = new CurrentAllergyRecheck(null, null, audit);
        var stop = CurrentAllergyRecheck.stop(UUID.randomUUID(), "Amoxicillin", List.of(),
            List.of(allergy("Penicillin", AllergySeverity.SEVERE))).orElseThrow();
        UUID reviewer = UUID.randomUUID();
        var ack = new CurrentAllergyRecheck.Acknowledgement(CurrentAllergyRecheck.Gate.DISPENSE, stop, "review-token",
            reviewer, java.time.LocalDateTime.of(2026, 11, 10, 9, 30));
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
        assertThat(event.getDetails().toString()).contains("review-token", reviewer.toString(), "2026-11-10T09:30");
    }

    @Test
    void auditDoesNotRunAfterRollback() {
        var audit = mock(com.example.hms.service.AuditEventLogService.class);
        CurrentAllergyRecheck check = new CurrentAllergyRecheck(null, null, audit);
        var stop = CurrentAllergyRecheck.stop(UUID.randomUUID(), "Amoxicillin", List.of(),
            List.of(allergy("Penicillin", AllergySeverity.SEVERE))).orElseThrow();
        var ack = new CurrentAllergyRecheck.Acknowledgement(CurrentAllergyRecheck.Gate.MAR_DOSE, stop, "t", null, null);
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
        assertThat(CurrentAllergyRecheck.withRecordLine("Existing", null, 1000, "note")).isEqualTo("Existing");
        var stop = CurrentAllergyRecheck.stop(UUID.randomUUID(), "Amoxicillin", List.of(),
            List.of(allergy("Penicillin", AllergySeverity.SEVERE))).orElseThrow();
        var ack = new CurrentAllergyRecheck.Acknowledgement(CurrentAllergyRecheck.Gate.DISPENSE, stop, "t", null,
            java.time.LocalDateTime.of(2026, 11, 10, 9, 30));
        assertThat(CurrentAllergyRecheck.withRecordLine("Existing", ack, 1000, "note"))
            .contains("Existing", "Penicillin", "2026-11-10 09:30");
        assertThat(CurrentAllergyRecheck.withRecordLine(null, ack, 1000, "note")).isEqualTo(CurrentAllergyRecheck.recordLine(ack));
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
            assertThat(check.requirePrescriberReview(gate, rx, "Amoxicillin")).isNull();
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

    private static void assertHeld(CurrentAllergyRecheck check, Prescription rx) {
        assertThatThrownBy(() -> check.requirePrescriberReview(CurrentAllergyRecheck.Gate.DISPENSE, rx, "Amoxicillin"))
            .isInstanceOf(CdsCriticalBlockException.class)
            .hasFieldOrPropertyWithValue("field", CurrentAllergyRecheck.HOLD_FIELD);
    }

    /** A prescriber's review of the conflict the order meets now, as PrescriptionServiceImpl records it. */
    static User review(CurrentAllergyRecheck check, Prescription rx, AllergyReviewDecision decision) {
        User reviewer = new User();
        reviewer.setId(UUID.randomUUID());
        rx.setAllergyReviewDecision(decision);
        rx.setAllergyReviewToken(check.currentConflict(rx).orElseThrow().reviewToken());
        rx.setAllergyReviewRationale("Reaction was a rash; graded challenge");
        rx.setAllergyReviewedBy(reviewer);
        rx.setAllergyReviewedAt(java.time.LocalDateTime.of(2026, 11, 10, 9, 30));
        return reviewer;
    }

    private static Prescription order() {
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        Prescription rx = new Prescription();
        rx.setId(UUID.randomUUID());
        rx.setPatient(patient);
        rx.setMedicationName("Amoxicillin");
        return rx;
    }

    private static com.example.hms.model.Staff staff(String first, String last) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setFirstName(first);
        user.setLastName(last);
        com.example.hms.model.Staff staff = new com.example.hms.model.Staff();
        staff.setId(UUID.randomUUID());
        staff.setUser(user);
        return staff;
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
