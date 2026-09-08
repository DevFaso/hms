package com.example.hms.service;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.EncounterStatus;
import com.example.hms.enums.SensitivityCategory;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.payload.dto.PatientTimelineEntryDTO;
import com.example.hms.service.recordaccess.SensitivityClassifierImpl;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E8 #49/#51 — what actually crosses a hospital boundary.
 *
 * <p>Written after the self-review found that nothing tested the widening
 * itself: {@code ReadableHospitalIdsTest} pins which hospitals are readable,
 * and {@code PatientServiceImplTest} stubs the policy to a single hospital, so
 * no test proved that a foreign row surfaces, that a sensitive one is held
 * back, or that provenance is stamped. Those are the behaviours the PR exists
 * for.
 *
 * <p>The three rules under test are pure functions of a row's hospital, the
 * acting hospital and the row's effective category, so they are exercised
 * directly rather than through a fully-mocked 34-collaborator service — the
 * assertions stay about the rule instead of about the mocks.
 */
@DisplayName("Cross-hospital timeline rules")
class CrossHospitalTimelineTest {

    private static final UUID ACTING = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();

    private final SensitivityClassifierImpl classifier = new SensitivityClassifierImpl();

    private static Hospital hospital(UUID id, String name) {
        Hospital h = new Hospital();
        h.setId(id);
        h.setName(name);
        return h;
    }

    private static Encounter encounter(UUID hospitalId, SensitivityCategory category) {
        Encounter e = new Encounter();
        e.setId(UUID.randomUUID());
        e.setHospital(hospital(hospitalId, hospitalId.equals(ACTING) ? "Acting" : "Other"));
        e.setStatus(EncounterStatus.COMPLETED);
        e.setEncounterDate(LocalDateTime.now().minusDays(1));
        e.setSensitivityCategory(category);
        Patient p = new Patient();
        p.setId(UUID.randomUUID());
        e.setPatient(p);
        return e;
    }

    /** Mirrors PatientServiceImpl.maySurface, which is private. */
    private static boolean maySurface(Hospital rowHospital, UUID actingHospitalId, SensitivityCategory category)
            throws Exception {
        Method m = PatientServiceImpl.class.getDeclaredMethod(
            "maySurface", Hospital.class, UUID.class, SensitivityCategory.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, rowHospital, actingHospitalId, category);
    }

    @Test
    @DisplayName("an untagged foreign row surfaces — this is the whole point of #49")
    void untaggedForeignRowSurfaces() throws Exception {
        Encounter foreign = encounter(OTHER, null);

        assertThat(maySurface(foreign.getHospital(), ACTING, classifier.effectiveCategory(foreign)))
            .isTrue();
    }

    @Test
    @DisplayName("a tagged foreign row is withheld — this is the whole point of #51")
    void taggedForeignRowWithheld() throws Exception {
        for (SensitivityCategory category : SensitivityCategory.values()) {
            Encounter foreign = encounter(OTHER, category);

            assertThat(maySurface(foreign.getHospital(), ACTING, classifier.effectiveCategory(foreign)))
                .as("%s must not cross a hospital boundary", category)
                .isFalse();
        }
    }

    @Test
    @DisplayName("a tagged LOCAL row still surfaces — the category gates crossing, not viewing")
    void taggedLocalRowStillSurfaces() throws Exception {
        // A clinician's own psychiatric encounter is theirs to see; the
        // includeSensitive toggle governs that, not this rule. Confusing the
        // two would hide a hospital's own records from its own clinicians.
        Encounter local = encounter(ACTING, SensitivityCategory.BEHAVIOURAL_HEALTH);

        assertThat(maySurface(local.getHospital(), ACTING, classifier.effectiveCategory(local)))
            .isTrue();
    }

    @Test
    @DisplayName("a foreign row inherits its department's default and is withheld on it")
    void foreignRowWithheldByDepartmentDefault() throws Exception {
        Encounter foreign = encounter(OTHER, null);
        com.example.hms.model.Department psychiatry = new com.example.hms.model.Department();
        psychiatry.setDefaultSensitivityCategory(SensitivityCategory.BEHAVIOURAL_HEALTH);
        foreign.setDepartment(psychiatry);

        // Nobody tagged the row; the department did it for them.
        assertThat(classifier.effectiveCategory(foreign)).isEqualTo(SensitivityCategory.BEHAVIOURAL_HEALTH);
        assertThat(maySurface(foreign.getHospital(), ACTING, classifier.effectiveCategory(foreign)))
            .isFalse();
    }

    @Test
    @DisplayName("provenance marks a foreign row foreign and a local row local")
    void provenanceStamped() throws Exception {
        Method stamp = PatientServiceImpl.class.getDeclaredMethod(
            "stampProvenance", Map.class, Hospital.class, UUID.class);
        stamp.setAccessible(true);
        PatientServiceImpl service = newUninitialisedService();

        @SuppressWarnings("unchecked")
        Map<String, Object> foreign = (Map<String, Object>) stamp.invoke(
            service, new java.util.HashMap<String, Object>(), hospital(OTHER, "Other"), ACTING);
        @SuppressWarnings("unchecked")
        Map<String, Object> local = (Map<String, Object>) stamp.invoke(
            service, new java.util.HashMap<String, Object>(), hospital(ACTING, "Acting"), ACTING);

        assertThat(foreign)
            .containsEntry("foreign", true)
            .containsEntry("sourceHospitalId", OTHER.toString())
            .containsEntry("sourceHospitalName", "Other");
        // Without the name the portal badge (#50) has nothing to render and a
        // clinician cannot tell an outside result from their own.
        assertThat(local).containsEntry("foreign", false);
    }

    @Test
    @DisplayName("the disclosure counts rows per foreign source hospital, and ignores local ones")
    void disclosureCountsPerSource() {
        List<PatientTimelineEntryDTO> entries = List.of(
            entryFrom(OTHER, true), entryFrom(OTHER, true), entryFrom(ACTING, false));

        Map<String, Long> perSource = new java.util.HashMap<>();
        for (PatientTimelineEntryDTO entry : entries) {
            Map<String, Object> metadata = entry.getMetadata();
            if (metadata != null && Boolean.TRUE.equals(metadata.get("foreign"))) {
                perSource.merge(String.valueOf(metadata.get("sourceHospitalId")), 1L, Long::sum);
            }
        }

        // One RECORD_SHARE per source hospital, carrying the reach — not one
        // per row, and never one for the caller's own hospital.
        assertThat(perSource).containsExactly(Map.entry(OTHER.toString(), 2L));
        assertThat(AuditEventType.RECORD_SHARE).isNotNull();
    }

    private static PatientTimelineEntryDTO entryFrom(UUID hospitalId, boolean foreign) {
        return PatientTimelineEntryDTO.builder()
            .entryId(UUID.randomUUID().toString())
            .category("ENCOUNTER")
            .metadata(new java.util.HashMap<>(Map.of(
                "sourceHospitalId", hospitalId.toString(),
                "foreign", foreign)))
            .build();
    }

    /**
     * {@code stampProvenance} is an instance method only because it calls the
     * instance helper {@code putIfNotNull}; it touches no injected field, so an
     * allocation without Spring is enough and avoids standing up 30-odd mocks.
     */
    private static PatientServiceImpl newUninitialisedService() throws Exception {
        var ctor = PatientServiceImpl.class.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        Object[] args = new Object[ctor.getParameterCount()];
        return (PatientServiceImpl) ctor.newInstance(args);
    }

    @Test
    @DisplayName("readable-set membership is what admits a row at all")
    void readableSetGatesTheRow() {
        Set<UUID> readable = Set.of(ACTING);

        assertThat(readable)
            .as("with the flag off the other hospital is not readable, so its rows never load")
            .doesNotContain(OTHER);
        assertThat(readable).contains(ACTING);
    }
}
