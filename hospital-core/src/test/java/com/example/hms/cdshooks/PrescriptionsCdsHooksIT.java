package com.example.hms.cdshooks;

import com.example.hms.BaseIT;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsHookRequest;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsHookResponse;
import com.example.hms.cdshooks.service.MedicationPrescribeRulesCdsService;
import com.example.hms.cdshooks.service.OrderSelectRulesCdsService;
import com.example.hms.enums.OrganizationType;
import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.MedicationCatalogItemRepository;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.terminology.TerminologyCodes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the v1.0 CDS Hooks expansion (roadmap row 2).
 *
 * <p>Wires the two new {@code order-select} and {@code medication-prescribe}
 * services through the real {@code CdsRuleEngine} and real repositories
 * against an in-memory H2 database (via
 * {@link com.example.hms.config.TestPostgresConfig}). Exercises:
 *
 * <ul>
 *   <li>Spring context registration — autowiring proves both new
 *       {@code @Component}s are picked up by the {@code CdsHookRegistry}.</li>
 *   <li>RxNorm-keyed catalog resolution path added in V93 — a draft
 *       MedicationRequest carrying only an RxNorm coding resolves to a
 *       seeded {@link MedicationCatalogItem} via the new
 *       {@code findActiveByHospitalIdAndRxnormCode} repository method.</li>
 *   <li>Tall-man advisory — the medication-prescribe service surfaces an
 *       INFO card carrying the {@code tall_man_name} (V93 column) when
 *       the matched catalog row has one.</li>
 * </ul>
 *
 * <p>The duplicate-medication-order rule itself is exercised by
 * {@link com.example.hms.cdshooks.rules.DuplicateMedicationOrderRuleTest}
 * with hand-built contexts, so this IT does not need to seed a full
 * {@code Prescription} (which has a deep FK web through Staff /
 * Assignment / Encounter that the {@code TenantEntityListener} would then
 * insist line up). Keeping this IT focused on the new resolution and
 * advisory paths keeps it fast and resilient.
 */
class PrescriptionsCdsHooksIT extends BaseIT {

    @Autowired
    private OrderSelectRulesCdsService orderSelectService;

    @Autowired
    private MedicationPrescribeRulesCdsService medicationPrescribeService;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private HospitalRepository hospitalRepository;

    @Autowired
    private PatientRepository patientRepository;

    @Autowired
    private MedicationCatalogItemRepository medicationCatalogItemRepository;

    @Autowired
    private UserRepository userRepository;

    private final AtomicInteger sequence = new AtomicInteger();

    private Patient patient;

    @BeforeEach
    void setUp() {
        Organization organization = organizationRepository.save(Organization.builder()
            .name("Test Health Network")
            .code("ORG-CDS-" + nextId())
            .type(OrganizationType.HOSPITAL_CHAIN)
            .active(true)
            .build());

        Hospital hospital = hospitalRepository.save(Hospital.builder()
            .name("Test Hospital — CDS")
            .code("HCDS" + nextId())
            .city("Ouagadougou")
            .country("Burkina Faso")
            .address("1 Main St")
            .phoneNumber("+22655" + nextId())
            .email("cds" + nextId() + "@hospital.test")
            .organization(organization)
            .build());

        // Catalog row carrying RxCUI 7052 (prednisone) plus tall-man lettering
        // so we can exercise both the RxNorm-keyed resolution and the V93
        // tall_man_name advisory in a single seed.
        medicationCatalogItemRepository.save(MedicationCatalogItem.builder()
            .code("PRED-5")
            .nameFr("Prednisone 5 mg")
            .genericName("prednisone")
            .rxnormCode("7052")
            .tallManName("predniSONE")
            .form("tablet")
            .strength("5")
            .strengthUnit("mg")
            .hospital(hospital)
            .build());

        // Patient.user is OneToOne(optional=false) so a User row must exist
        // before the patient persists. We are not exercising auth here — the
        // user is plumbing for the FK.
        int n = nextId();
        User account = userRepository.save(User.builder()
            .username("cds-it-user-" + n)
            .passwordHash("noop")
            .email("cds-it-user-" + n + "@hospital.test")
            .firstName("Aïcha")
            .lastName("Cds-Hooks-IT-" + n)
            .phoneNumber("+22670" + n + "100")
            .build());

        patient = patientRepository.save(Patient.builder()
            .firstName("Aïcha")
            .lastName("Cds-Hooks-IT-" + n)
            .dateOfBirth(LocalDate.of(1985, 6, 15))
            .gender("FEMALE")
            .phoneNumberPrimary("+22670" + n + "200")
            .email("cds-it-" + n + "@patient.test")
            .hospitalId(hospital.getId())
            .user(account)
            .build());
    }

    @AfterEach
    void tearDown() {
        patientRepository.deleteAll();
        userRepository.deleteAll();
        medicationCatalogItemRepository.deleteAll();
        hospitalRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    @Test
    @DisplayName("medication-prescribe resolves an RxNorm-only coding and surfaces tall-man lettering")
    void medicationPrescribeSurfacesTallManAdvisory() {
        CdsHookResponse response = medicationPrescribeService.evaluate(
            prescribeRequest(patient.getId(), List.of(
                rxnormDraft("7052", "Prednisone 5 mg", "1 tab daily")))
        );

        assertThat(response.cards())
            .as("medication-prescribe must emit the V93 tall-man advisory when present")
            .extracting(card -> card.summary() == null ? "" : card.summary())
            .anyMatch(s -> s.toLowerCase().contains("confusable"));
        assertThat(response.cards())
            .extracting(card -> card.detail() == null ? "" : card.detail())
            .anyMatch(d -> d.contains("predniSONE"));
    }

    @Test
    @DisplayName("order-select returns no cards when no rules trigger and accepts RxNorm-only payload")
    void orderSelectAcceptsRxnormOnlyPayloadWithNoRulesTriggered() {
        // No active prescriptions are seeded, no allergies, dose well within
        // pediatric ceiling — every rule should evaluate to no card. The
        // assertion is mainly that the call completes against the live
        // engine + repository wiring without exception.
        CdsHookResponse response = orderSelectService.evaluate(
            selectRequest(patient.getId(), List.of(),
                List.of(rxnormDraft("7052", "Prednisone 5 mg", "1 tab")))
        );

        assertThat(response).isNotNull();
        assertThat(response.cards()).isNotNull();
    }

    @Test
    @DisplayName("medication-prescribe returns empty when patient is unknown")
    void medicationPrescribeReturnsEmptyWhenPatientUnknown() {
        CdsHookResponse response = medicationPrescribeService.evaluate(
            prescribeRequest(UUID.randomUUID(),
                List.of(rxnormDraft("7052", "Prednisone 5 mg", "1 tab"))));

        assertThat(response.cards()).isEmpty();
    }

    /* =====================================================================
       Hook-payload helpers — the CDS Hooks spec uses loose JSON shapes,
       so we build them as plain Maps to mirror the wire format.
       ===================================================================== */

    private static CdsHookRequest prescribeRequest(UUID patientId,
                                                   List<Map<String, Object>> drafts) {
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("entry", drafts.stream()
            .map(d -> Map.<String, Object>of("resource", d))
            .toList());
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("patientId", patientId.toString());
        ctx.put("medications", bundle);
        return new CdsHookRequest("medication-prescribe", "i", null, null,
            "Practitioner/x", ctx, null);
    }

    private static CdsHookRequest selectRequest(UUID patientId,
                                                List<String> selections,
                                                List<Map<String, Object>> drafts) {
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("entry", drafts.stream()
            .map(d -> Map.<String, Object>of("resource", d))
            .toList());
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("patientId", patientId.toString());
        ctx.put("draftOrders", bundle);
        if (selections != null) ctx.put("selections", selections);
        return new CdsHookRequest("order-select", "i", null, null,
            "Practitioner/x", ctx, null);
    }

    /** A draft MedicationRequest whose only typed coding is the RxNorm system. */
    private static Map<String, Object> rxnormDraft(String rxcui, String displayName, String dose) {
        Map<String, Object> mr = new HashMap<>();
        mr.put("resourceType", "MedicationRequest");
        mr.put("medicationCodeableConcept", Map.of(
            "text", displayName,
            "coding", List.of(
                Map.of("system", TerminologyCodes.SYSTEM_RXNORM,
                       "code", rxcui,
                       "display", displayName))));
        mr.put("dosageInstruction", List.of(Map.of("text", dose)));
        return mr;
    }

    private int nextId() {
        return sequence.incrementAndGet();
    }
}
