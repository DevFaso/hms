package com.example.hms.service.support;

import com.example.hms.enums.ImagingOrderStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ClientSafeAccessDeniedException;
import com.example.hms.model.ImagingOrder;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.imaging.ImagingOrderStatusUpdateRequestDTO;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Policy rule 3 (2026-11-20, provisional): the exam steps of an imaging technician and the exam record. */
class ImagingExamRecordTest {

    private static ImagingOrderStatusUpdateRequestDTO to(ImagingOrderStatus status) {
        return ImagingOrderStatusUpdateRequestDTO.builder().status(status).build();
    }

    @Test
    void aTechnicianStartsCompletesAndDiscontinuesAStartedExam() {
        for (ImagingOrderStatus placed : new ImagingOrderStatus[] {ImagingOrderStatus.ORDERED,
            ImagingOrderStatus.SCHEDULED, ImagingOrderStatus.PENDING_AUTHORIZATION}) {
            assertThatCode(() -> ImagingExamRecord.requireTechnicianStep(placed, to(ImagingOrderStatus.IN_PROGRESS)))
                .as("start from " + placed).doesNotThrowAnyException();
        }
        assertThatCode(() -> ImagingExamRecord.requireTechnicianStep(ImagingOrderStatus.IN_PROGRESS,
            to(ImagingOrderStatus.COMPLETED))).doesNotThrowAnyException();
        assertThatCode(() -> ImagingExamRecord.requireTechnicianStep(ImagingOrderStatus.IN_PROGRESS,
            to(ImagingOrderStatus.CANCELLED))).doesNotThrowAnyException();
    }

    @Test
    void aTechnicianNeverCancelsAnExamNotStartedNorMovesTheOrderAnywhereElse() {
        for (ImagingOrderStatus from : ImagingOrderStatus.values()) {
            if (from != ImagingOrderStatus.IN_PROGRESS) {
                ImagingOrderStatusUpdateRequestDTO cancel = to(ImagingOrderStatus.CANCELLED);
                assertThatThrownBy(() -> ImagingExamRecord.requireTechnicianStep(from, cancel))
                    .as("cancel from " + from).isInstanceOf(ClientSafeAccessDeniedException.class);
            }
            for (ImagingOrderStatus target : new ImagingOrderStatus[] {ImagingOrderStatus.DRAFT,
                ImagingOrderStatus.ORDERED, ImagingOrderStatus.PENDING_AUTHORIZATION, ImagingOrderStatus.SCHEDULED,
                ImagingOrderStatus.RESULTS_AVAILABLE}) {
                ImagingOrderStatusUpdateRequestDTO move = to(target);
                assertThatThrownBy(() -> ImagingExamRecord.requireTechnicianStep(from, move))
                    .as(from + " to " + target).isInstanceOf(ClientSafeAccessDeniedException.class);
            }
        }
        // A same-status write on a study in progress is how a schedule is recorded: not theirs.
        ImagingOrderStatusUpdateRequestDTO sameStatus = to(ImagingOrderStatus.IN_PROGRESS);
        assertThatThrownBy(() -> ImagingExamRecord.requireTechnicianStep(ImagingOrderStatus.IN_PROGRESS, sameStatus))
            .isInstanceOf(ClientSafeAccessDeniedException.class);
    }

    @Test
    void aTechniciansStepCarriesNoSchedulingOrPreAuthorisationField() {
        ImagingOrderStatusUpdateRequestDTO[] changing = {
            ImagingOrderStatusUpdateRequestDTO.builder().status(ImagingOrderStatus.IN_PROGRESS)
                .scheduledDate(LocalDate.now()).build(),
            ImagingOrderStatusUpdateRequestDTO.builder().status(ImagingOrderStatus.IN_PROGRESS)
                .scheduledTime("09:00").build(),
            ImagingOrderStatusUpdateRequestDTO.builder().status(ImagingOrderStatus.IN_PROGRESS)
                .appointmentLocation("Salle 2").build(),
            ImagingOrderStatusUpdateRequestDTO.builder().status(ImagingOrderStatus.IN_PROGRESS)
                .workflowNotes("x").build(),
            ImagingOrderStatusUpdateRequestDTO.builder().status(ImagingOrderStatus.IN_PROGRESS)
                .requiresAuthorization(false).build(),
            ImagingOrderStatusUpdateRequestDTO.builder().status(ImagingOrderStatus.IN_PROGRESS)
                .authorizationNumber("AUTH-1").build(),
        };
        for (ImagingOrderStatusUpdateRequestDTO request : changing) {
            assertThatThrownBy(() -> ImagingExamRecord.requireTechnicianStep(ImagingOrderStatus.ORDERED, request))
                .as(request.toString()).isInstanceOf(ClientSafeAccessDeniedException.class);
        }
    }

    @Test
    void whoeverStartsTheExamIsItsPerformer_andACompletionFillsAMissingOneOnly() {
        ImagingOrder order = new ImagingOrder();
        UUID starter = UUID.randomUUID();
        Staff staff = new Staff();
        staff.setId(UUID.randomUUID());
        LocalDateTime start = LocalDateTime.of(2026, 11, 20, 9, 0);

        ImagingExamRecord.stampPerformer(order, ImagingOrderStatus.ORDERED, ImagingOrderStatus.IN_PROGRESS, starter,
            "Awa Kone", staff, start);
        assertThat(order.getExamStartedAt()).isEqualTo(start);
        assertThat(order.getPerformedByUserId()).isEqualTo(starter);
        assertThat(order.getPerformedByStaffId()).isEqualTo(staff.getId());
        assertThat(order.getPerformedByName()).isEqualTo("Awa Kone");

        // Somebody else completes it: the performer stays who started it.
        ImagingExamRecord.stampPerformer(order, ImagingOrderStatus.IN_PROGRESS, ImagingOrderStatus.COMPLETED,
            UUID.randomUUID(), "Other", null,
            start.plusMinutes(20));
        assertThat(order.getPerformedByUserId()).isEqualTo(starter);
        assertThat(order.getExamStartedAt()).isEqualTo(start);

        // A study started before V346 has no performer: whoever completes it is recorded.
        ImagingOrder legacy = new ImagingOrder();
        UUID completer = UUID.randomUUID();
        ImagingExamRecord.stampPerformer(legacy, ImagingOrderStatus.IN_PROGRESS, ImagingOrderStatus.COMPLETED, completer,
            "Doc", null, start);
        assertThat(legacy.getPerformedByUserId()).isEqualTo(completer);
        assertThat(legacy.getPerformedByStaffId()).isNull();
        assertThat(legacy.getExamStartedAt()).isNull();

        // No session user, or a step that performs nothing: nothing is written.
        ImagingOrder untouched = new ImagingOrder();
        ImagingExamRecord.stampPerformer(untouched, ImagingOrderStatus.ORDERED, ImagingOrderStatus.IN_PROGRESS, null, null,
            null, start);
        ImagingExamRecord.stampPerformer(untouched, ImagingOrderStatus.ORDERED, ImagingOrderStatus.SCHEDULED, starter,
            "Awa", staff, start);
        assertThat(untouched.getPerformedByUserId()).isNull();
        assertThat(untouched.getExamStartedAt()).isNull();
    }

    @Test
    void checkInIsOpenWhileTheExamIsPlacedAndNotDone_theRecordFromItsStartUntilASignedReport() {
        for (ImagingOrderStatus status : ImagingOrderStatus.values()) {
            boolean checkIn = status == ImagingOrderStatus.ORDERED || status == ImagingOrderStatus.PENDING_AUTHORIZATION
                || status == ImagingOrderStatus.SCHEDULED || status == ImagingOrderStatus.IN_PROGRESS;
            boolean recordOpen = status == ImagingOrderStatus.IN_PROGRESS || status == ImagingOrderStatus.COMPLETED;
            if (checkIn) {
                assertThatCode(() -> ImagingExamRecord.requireCheckInOpen(status)).doesNotThrowAnyException();
            } else {
                assertThatThrownBy(() -> ImagingExamRecord.requireCheckInOpen(status))
                    .as("check-in at " + status).isInstanceOf(BusinessException.class);
            }
            if (recordOpen) {
                assertThatCode(() -> ImagingExamRecord.requireRecordOpen(status)).doesNotThrowAnyException();
            } else {
                assertThatThrownBy(() -> ImagingExamRecord.requireRecordOpen(status))
                    .as("record at " + status).isInstanceOf(BusinessException.class);
            }
        }
    }

    @Test
    void aSameStatusWriteIsNotAStart_itNeverReplacesThePerformerNorTheStartTime() {
        ImagingOrder order = new ImagingOrder();
        UUID starter = UUID.randomUUID();
        LocalDateTime start = LocalDateTime.of(2026, 11, 20, 9, 0);
        ImagingExamRecord.stampPerformer(order, ImagingOrderStatus.SCHEDULED, ImagingOrderStatus.IN_PROGRESS, starter,
            "Awa Kone", null, start);

        // Review of #1243: IN_PROGRESS to IN_PROGRESS (a schedule or a location recorded) is not performing.
        ImagingExamRecord.stampPerformer(order, ImagingOrderStatus.IN_PROGRESS, ImagingOrderStatus.IN_PROGRESS,
            UUID.randomUUID(), "Dr Autre", null, start.plusMinutes(10));

        assertThat(order.getPerformedByUserId()).isEqualTo(starter);
        assertThat(order.getPerformedByName()).isEqualTo("Awa Kone");
        assertThat(order.getExamStartedAt()).isEqualTo(start);
    }

    @Test
    void theChangeListNamesTheStructuredValuesBeforeAndAfter_andTheFreeTextsOnlyAsChanged() {
        ImagingOrder order = new ImagingOrder();
        order.setExamTechnicalNote("ancienne note");
        order.setExamContrastDetails("Iode 60 mL");
        order.setExamRadiationDoseMgy(new java.math.BigDecimal("0.100"));

        java.util.Map<String, String> changes = ImagingExamRecord.changes(order,
            com.example.hms.payload.dto.imaging.ImagingExamRecordRequestDTO.builder()
                .technicalNote("nouvelle note").contrastAdministered(true).contrastDetails("  ")
                .radiationDoseMgy(new java.math.BigDecimal("0.1000")).build());

        assertThat(changes)
            .containsEntry("technicalNote", "changed")
            .containsEntry("contrastAdministered", "null -> true")
            .containsEntry("contrastDetails", "cleared")
            // 0.100 and 0.1000 are the same dose.
            .doesNotContainKey("radiationDoseMgy");
        assertThat(changes.values()).noneMatch(value -> value.contains("note") || value.contains("Iode"));

        assertThat(ImagingExamRecord.changes(new ImagingOrder(),
            com.example.hms.payload.dto.imaging.ImagingExamRecordRequestDTO.builder().build())).isEmpty();
    }

    @Test
    void anExamRecordWithNothingInItIsRefusedOnTheNote() {
        try (jakarta.validation.ValidatorFactory factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            jakarta.validation.Validator validator = factory.getValidator();
            var empty = validator.validate(
                com.example.hms.payload.dto.imaging.ImagingExamRecordRequestDTO.builder().technicalNote("  ").build());
            assertThat(empty).hasSize(1);
            assertThat(empty.iterator().next().getPropertyPath()).hasToString("somethingRecorded");
            assertThat(empty.iterator().next().getMessageTemplate()).isEqualTo("{imaging.exam.record.empty}");

            // Any one of the four says something.
            assertThat(validator.validate(com.example.hms.payload.dto.imaging.ImagingExamRecordRequestDTO.builder()
                .technicalNote("RAS").build())).isEmpty();
            assertThat(validator.validate(com.example.hms.payload.dto.imaging.ImagingExamRecordRequestDTO.builder()
                .contrastAdministered(false).build())).isEmpty();
            assertThat(validator.validate(com.example.hms.payload.dto.imaging.ImagingExamRecordRequestDTO.builder()
                .contrastDetails("Iode").build())).isEmpty();
            assertThat(validator.validate(com.example.hms.payload.dto.imaging.ImagingExamRecordRequestDTO.builder()
                .radiationDoseMgy(java.math.BigDecimal.ONE).build())).isEmpty();
        }
    }
}
