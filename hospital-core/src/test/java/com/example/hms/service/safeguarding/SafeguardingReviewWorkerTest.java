package com.example.hms.service.safeguarding;

import com.example.hms.enums.InBasketPriority;
import com.example.hms.enums.SafeguardingReviewStatus;
import com.example.hms.model.User;
import com.example.hms.model.safeguarding.SafeguardingReview;
import com.example.hms.payload.dto.clinical.CreateInBasketItemRequest;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.SafeguardingReviewRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.service.InBasketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("r53: the safeguarding review and its two URGENT tasks, at the admission's hospital only")
class SafeguardingReviewWorkerTest {

    private final SafeguardingReviewRepository reviews = mock(SafeguardingReviewRepository.class);
    private final UserRoleHospitalAssignmentRepository assignments = mock(UserRoleHospitalAssignmentRepository.class);
    private final PatientRepository patients = mock(PatientRepository.class);
    private final InBasketService inBasket = mock(InBasketService.class);
    private final SafeguardingReviewWorker worker = new SafeguardingReviewWorker(reviews, assignments, patients, inBasket);

    private final UUID hospitalId = UUID.randomUUID();
    private final UUID patientId = UUID.randomUUID();
    private final User doctor = user();
    private final User midwife = user();

    private static User user() {
        User user = new User();
        user.setId(UUID.randomUUID());
        return user;
    }

    private MinorLabourSafeguarding.LabourAdmission admission() {
        return new MinorLabourSafeguarding.LabourAdmission(hospitalId, patientId, SafeguardingReview.SOURCE_ADMISSION,
            UUID.randomUUID(), 10, 13, LocalDateTime.now(), midwife.getId());
    }

    @BeforeEach
    void setUp() {
        when(reviews.saveAndFlush(any())).thenAnswer(inv -> {
            SafeguardingReview review = inv.getArgument(0);
            review.setId(UUID.randomUUID());
            return review;
        });
        when(patients.findByIdUnscoped(patientId)).thenReturn(Optional.empty());
        when(assignments.findLiveUsersAtHospitalWithRoleCodes(hospitalId, SafeguardingReviewWorker.SAFEGUARDING_ROLE_CODES))
            .thenReturn(List.of(doctor, midwife));
        when(assignments.findLiveUsersAtHospitalWithRoleCodes(hospitalId, SafeguardingReviewWorker.SENIOR_REVIEW_ROLE_CODES))
            .thenReturn(List.of(doctor));
    }

    @Test
    void opensAnOpenReviewAndFilesTheUrgentTaskAndTheSeniorObstetricRequest() {
        UUID id = worker.open(admission());

        ArgumentCaptor<SafeguardingReview> saved = ArgumentCaptor.forClass(SafeguardingReview.class);
        verify(reviews).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(SafeguardingReviewStatus.OPEN);
        assertThat(saved.getValue().getPatientAgeYears()).isEqualTo(10);
        assertThat(saved.getValue().getAgeThresholdYears()).isEqualTo(13);
        assertThat(saved.getValue().getHospitalId()).isEqualTo(hospitalId);

        ArgumentCaptor<CreateInBasketItemRequest> items = ArgumentCaptor.forClass(CreateInBasketItemRequest.class);
        verify(inBasket, times(3)).createItemInNewTransaction(items.capture());
        assertThat(items.getAllValues()).allSatisfy(item -> {
            assertThat(item.getPriority()).isEqualTo(InBasketPriority.URGENT);
            assertThat(item.getHospitalId()).isEqualTo(hospitalId);
            assertThat(item.getReferenceId()).isEqualTo(id);
        });
        assertThat(items.getAllValues()).filteredOn(i -> "SAFEGUARDING_REVIEW".equals(i.getReferenceType()))
            .extracting(CreateInBasketItemRequest::getRecipientUserId)
            .containsExactlyInAnyOrder(doctor.getId(), midwife.getId());
        assertThat(items.getAllValues()).filteredOn(i -> "SENIOR_OBSTETRIC_REVIEW".equals(i.getReferenceType()))
            .extracting(CreateInBasketItemRequest::getRecipientUserId)
            .containsExactly(doctor.getId());
    }

    @Test
    void theAdmissionAndTheEpisodeOfOneStayOpenOneReview() {
        when(reviews.existsByPatientIdAndHospitalIdAndTriggerCodeAndStatus(patientId, hospitalId,
            SafeguardingReview.TRIGGER_MINOR_LABOUR, SafeguardingReviewStatus.OPEN)).thenReturn(true);
        assertThat(worker.open(admission())).isNull();
        verify(reviews, never()).saveAndFlush(any());
        verify(inBasket, never()).createItemInNewTransaction(any());
    }

    @Test
    void oneFailedTaskNeverStopsTheOthers() {
        doThrow(new IllegalStateException("x")).when(inBasket)
            .createItemInNewTransaction(argThat(i -> i != null && doctor.getId().equals(i.getRecipientUserId())));
        assertThat(worker.open(admission())).isNotNull();
        verify(inBasket, times(3)).createItemInNewTransaction(any());
    }

    @Test
    void onlyTheHospitalsCliniciansAreTaskedNeverAPatientARelativeOrTheDesk() {
        worker.open(admission());
        verify(assignments).findLiveUsersAtHospitalWithRoleCodes(eq(hospitalId),
            eq(SafeguardingReviewWorker.SAFEGUARDING_ROLE_CODES));
        assertThat(SafeguardingReviewWorker.SAFEGUARDING_ROLE_CODES)
            .containsExactlyInAnyOrder("ROLE_DOCTOR", "ROLE_SURGEON", "ROLE_MIDWIFE");
        assertThat(SafeguardingReviewWorker.SENIOR_REVIEW_ROLE_CODES)
            .containsExactlyInAnyOrder("ROLE_DOCTOR", "ROLE_SURGEON");
    }

    /** The wording states a review is required; it never states or implies that abuse is confirmed or suspected. */
    @Test
    void theWordingIsNeutralInEveryLanguage() throws IOException {
        for (String suffix : List.of("", "_en", "_fr", "_es")) {
            Properties bundle = new Properties();
            try (var reader = Files.newBufferedReader(
                    Path.of("src/main/resources/i18n/messages-maternity" + suffix + ".properties"), StandardCharsets.UTF_8)) {
                bundle.load(reader);
            }
            for (String key : List.of(SafeguardingReviewWorker.TASK_TITLE_KEY, SafeguardingReviewWorker.TASK_BODY_KEY,
                    SafeguardingReviewWorker.SENIOR_TITLE_KEY, SafeguardingReviewWorker.SENIOR_BODY_KEY)) {
                String text = bundle.getProperty(key);
                assertThat(text).as(suffix + " " + key).isNotBlank();
                String lower = text.toLowerCase(Locale.ROOT);
                for (String word : List.of("abus", "abuse", "maltrait", "violen", "agress", "assault", "viol ",
                        "confirm", "suspect", "soupçon", "sospech", "victim")) {
                    assertThat(lower).as(suffix + " " + key + " must stay neutral").doesNotContain(word);
                }
            }
        }
    }
}
