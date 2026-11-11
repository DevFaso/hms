package com.example.hms.service.allergy;

import com.example.hms.cdshooks.CdsCriticalBlockException;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.enums.AllergyReviewDecision;
import com.example.hms.enums.AllergySeverity;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientAllergy;
import com.example.hms.model.Prescription;
import com.example.hms.model.User;
import com.example.hms.payload.dto.nurse.MarVerificationResponseDTO;
import com.example.hms.payload.dto.nurse.NurseMedicationTaskResponseDTO;
import com.example.hms.payload.dto.pharmacy.WorkQueuePrescriptionDTO;
import com.example.hms.payload.dto.prescription.AllergyReviewCarrier;
import com.example.hms.payload.dto.prescription.AllergyReviewStateDTO;
import com.example.hms.payload.dto.prescription.AllergyReviewStateDTO.State;
import com.example.hms.repository.PatientAllergyRepository;
import com.example.hms.utility.MessageUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * QA D1584 / D1585: every gate response that shows the allergy card says where the conflict stands,
 * read on the very token the gate checks: awaiting review, the prescriber's CONTINUE (who, when, and
 * the plan for a clinical reader at the order's own hospital), or STOP. The card never says "a
 * prescriber must review" once the gate lets the dose through.
 */
class AllergyReviewStateReadingTest {

    private static final String PLAN = "Monitor 30 min after each dose, adrenaline at the bedside";
    private static final String RATIONALE = "Reaction was a rash; graded challenge tolerated";

    private final UUID hospitalId = UUID.randomUUID();
    private Prescription rx;
    private CurrentAllergyRecheck check;

    /** The three gate responses that carry the cards. */
    private static final Map<String, Supplier<AllergyReviewCarrier>> RESPONSES = Map.of(
        "eMAR / nurse-station MAR row", NurseMedicationTaskResponseDTO::new,
        "bedside verify", MarVerificationResponseDTO::new,
        "pharmacy work queue, verify, dispense and hand-over panels", WorkQueuePrescriptionDTO::new);

    @BeforeEach
    void setUp() {
        MessageUtil.setMessageSource(com.example.hms.i18n.TestMessageSources.bundles());
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        rx = new Prescription();
        rx.setId(UUID.randomUUID());
        rx.setPatient(patient);
        rx.setHospital(hospital);
        rx.setMedicationName("Amoxicillin");
        PatientAllergy allergy = new PatientAllergy();
        allergy.setId(UUID.randomUUID());
        allergy.setActive(true);
        allergy.setAllergenDisplay("Penicillin");
        allergy.setSeverity(AllergySeverity.SEVERE);
        PatientAllergyRepository repository = mock(PatientAllergyRepository.class);
        when(repository.findByPatient_Id(patient.getId())).thenReturn(List.of(allergy));
        check = new CurrentAllergyRecheck(repository, null, null);
        signIn("ROLE_NURSE");
    }

    @AfterEach
    void tearDown() {
        MessageUtil.setMessageSource(null);
        SecurityContextHolder.clearContext();
    }

    @Test
    void unreviewedTheCardAndEveryResponseSayItAwaitsTheReview() {
        for (var response : RESPONSES.entrySet()) {
            AllergyReviewCarrier dto = response.getValue().get();
            check.readFor(rx, "Amoxicillin", CurrentAllergyRecheck.mayReadReviewPlan(rx, hospitalId)).applyTo(dto);
            AllergyReviewStateDTO state = stateOf(dto);
            assertThat(state.getState()).as(response.getKey()).isEqualTo(State.AWAITING_REVIEW);
            assertThat(state.getPlan()).isNull();
            assertThat(state.getReviewedByName()).isNull();
            assertThat(cardsOf(dto).getFirst().summary()).as(response.getKey()).contains("must review");
        }
    }

    @Test
    void continuedTheCardNamesTheReviewerAndEveryResponseCarriesThePlan() {
        review(AllergyReviewDecision.CONTINUE);
        // The gate passes: the screen must say so too.
        assertThat(check.requirePrescriberReview(CurrentAllergyRecheck.Gate.MAR_DOSE, rx, "Amoxicillin")).isNotNull();
        for (var response : RESPONSES.entrySet()) {
            AllergyReviewCarrier dto = response.getValue().get();
            check.readFor(rx, "Amoxicillin", CurrentAllergyRecheck.mayReadReviewPlan(rx, hospitalId)).applyTo(dto);
            AllergyReviewStateDTO state = stateOf(dto);
            assertThat(state.getState()).as(response.getKey()).isEqualTo(State.CONTINUE);
            assertThat(state.getReviewedByName()).isEqualTo("Aminata Ouedraogo");
            assertThat(state.getReviewedAt()).isEqualTo(LocalDateTime.of(2026, 11, 10, 9, 30));
            assertThat(state.getPlan()).as(response.getKey()).isEqualTo(PLAN);
            assertThat(state.getRationale()).isEqualTo(RATIONALE);
            CdsCard card = cardsOf(dto).getFirst();
            assertThat(card.summary()).as(response.getKey())
                .contains("Reviewed by Dr Aminata Ouedraogo").contains("continue").doesNotContain("must review");
            // The narrative never travels in a card's text: a card reaches readers the plan must not.
            assertThat(card.summary() + card.detail()).doesNotContain(PLAN).doesNotContain(RATIONALE);
            assertThat(card.exceptionKey()).startsWith(CurrentAllergyRecheck.KEY_PREFIX);
        }
    }

    @Test
    void stoppedTheCardAndTheHoldSayContactThePrescriber() {
        review(AllergyReviewDecision.STOP);
        for (var response : RESPONSES.entrySet()) {
            AllergyReviewCarrier dto = response.getValue().get();
            check.readFor(rx, "Amoxicillin", true).applyTo(dto);
            AllergyReviewStateDTO state = stateOf(dto);
            assertThat(state.getState()).as(response.getKey()).isEqualTo(State.STOP);
            assertThat(state.getPlan()).isNull();
            assertThat(cardsOf(dto).getFirst().summary()).as(response.getKey())
                .contains("Prescriber decided to stop").contains("contact the prescriber").doesNotContain("must review");
        }
        // QA D1585: the hold names the STOP, so the dialog is not titled "awaiting review".
        assertThatThrownBy(() -> check.requirePrescriberReview(CurrentAllergyRecheck.Gate.DISPENSE, rx, "Amoxicillin"))
            .isInstanceOfSatisfying(CdsCriticalBlockException.class, e -> {
                assertThat(e.getReviewState()).isEqualTo("STOP");
                assertThat(e.getCards().getFirst().summary()).contains("Prescriber decided to stop");
            });
    }

    @Test
    void anUnreviewedHoldSaysItAwaitsTheReviewOnTheWire() {
        CdsCriticalBlockException hold = org.junit.jupiter.api.Assertions.assertThrows(CdsCriticalBlockException.class,
            () -> check.requirePrescriberReview(CurrentAllergyRecheck.Gate.MAR_DOSE, rx, "Amoxicillin"));
        assertThat(hold.getReviewState()).isEqualTo("AWAITING_REVIEW");
        var response = new com.example.hms.exception.GlobalExceptionHandler().handleCdsCriticalBlock(hold,
            new org.springframework.web.context.request.ServletWebRequest(
                new org.springframework.mock.web.MockHttpServletRequest()));
        assertThat(response.getBody()).isInstanceOfSatisfying(Map.class,
            body -> assertThat(body.get("reviewState")).isEqualTo("AWAITING_REVIEW"));
    }

    @Test
    void aReviewOfAnEarlierConflictReadsAsAwaitingAsTheGateHoldsIt() {
        review(AllergyReviewDecision.CONTINUE);
        rx.setDosage("1000");
        assertThatThrownBy(() -> check.requirePrescriberReview(CurrentAllergyRecheck.Gate.MAR_DOSE, rx, "Amoxicillin"))
            .isInstanceOf(CdsCriticalBlockException.class);
        NurseMedicationTaskResponseDTO dto = new NurseMedicationTaskResponseDTO();
        check.readFor(rx, "Amoxicillin", true).applyTo(dto);
        assertThat(dto.getAllergyReview().getState()).isEqualTo(State.AWAITING_REVIEW);
        assertThat(dto.getAllergyReview().getPlan()).isNull();
    }

    @Test
    void thePlanReachesOnlyAClinicalReaderAtTheOrdersOwnHospital() {
        review(AllergyReviewDecision.CONTINUE);
        // Another hospital (a shared patient's order read elsewhere): never its dose.
        assertThat(CurrentAllergyRecheck.mayReadReviewPlan(rx, UUID.randomUUID())).isFalse();
        // A hospital administrator or a super-admin reads the queue or the MAR, not the narrative.
        for (String role : List.of("ROLE_HOSPITAL_ADMIN", "ROLE_SUPER_ADMIN", "ROLE_RECEPTIONIST")) {
            signIn(role);
            assertThat(CurrentAllergyRecheck.mayReadReviewPlan(rx, hospitalId)).as(role).isFalse();
            WorkQueuePrescriptionDTO dto = new WorkQueuePrescriptionDTO();
            check.readFor(rx, "Amoxicillin", CurrentAllergyRecheck.mayReadReviewPlan(rx, hospitalId)).applyTo(dto);
            assertThat(dto.getAllergyReview().getState()).isEqualTo(State.CONTINUE);
            assertThat(dto.getAllergyReview().getPlan()).isNull();
            assertThat(dto.getAllergyReview().getRationale()).isNull();
        }
        for (String role : List.of("ROLE_NURSE", "ROLE_MIDWIFE", "ROLE_DOCTOR", "ROLE_PHARMACIST", "ROLE_PHARMACY_VERIFIER")) {
            signIn(role);
            assertThat(CurrentAllergyRecheck.mayReadReviewPlan(rx, hospitalId)).as(role).isTrue();
        }
        SecurityContextHolder.clearContext();
        assertThat(CurrentAllergyRecheck.mayReadReviewPlan(rx, hospitalId)).isFalse();
    }

    @Test
    void noAllergyLeavesTheResponseEmptyWithNoState() {
        PatientAllergyRepository none = mock(PatientAllergyRepository.class);
        when(none.findByPatient_Id(rx.getPatient().getId())).thenReturn(List.of());
        MarVerificationResponseDTO dto = new MarVerificationResponseDTO();
        new CurrentAllergyRecheck(none, null, null).readFor(rx, "Amoxicillin", true).applyTo(dto);
        assertThat(dto.getCdsAdvisories()).isEmpty();
        assertThat(dto.getAllergyReview()).isNull();
        WorkQueuePrescriptionDTO row = new WorkQueuePrescriptionDTO();
        new CurrentAllergyRecheck(none, null, null).readFor(rx, "Amoxicillin", true).applyTo(row);
        assertThat(row.getAllergyAlerts()).isNull();
    }

    private void review(AllergyReviewDecision decision) {
        User reviewer = new User();
        reviewer.setId(UUID.randomUUID());
        reviewer.setFirstName("Aminata");
        reviewer.setLastName("Ouedraogo");
        rx.setAllergyReviewDecision(decision);
        rx.setAllergyReviewToken(check.currentConflict(rx).orElseThrow().reviewToken());
        rx.setAllergyReviewPlan(decision == AllergyReviewDecision.CONTINUE ? PLAN : null);
        rx.setAllergyReviewRationale(RATIONALE);
        rx.setAllergyReviewedBy(reviewer);
        rx.setAllergyReviewedAt(LocalDateTime.of(2026, 11, 10, 9, 30));
    }

    private static void signIn(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            "user", "n/a", List.of(new SimpleGrantedAuthority(role))));
    }

    private static AllergyReviewStateDTO stateOf(AllergyReviewCarrier dto) {
        return switch (dto) {
            case NurseMedicationTaskResponseDTO t -> t.getAllergyReview();
            case MarVerificationResponseDTO v -> v.getAllergyReview();
            case WorkQueuePrescriptionDTO w -> w.getAllergyReview();
            default -> throw new IllegalArgumentException(dto.getClass().getName());
        };
    }

    private static List<CdsCard> cardsOf(AllergyReviewCarrier dto) {
        return switch (dto) {
            case NurseMedicationTaskResponseDTO t -> t.getCdsAdvisories();
            case MarVerificationResponseDTO v -> v.getCdsAdvisories();
            case WorkQueuePrescriptionDTO w -> w.getAllergyAlerts();
            default -> throw new IllegalArgumentException(dto.getClass().getName());
        };
    }
}
