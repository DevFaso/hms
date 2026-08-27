package com.example.hms.service.tenant;

import com.example.hms.enums.OrganizationLifecycleState;
import com.example.hms.enums.OrganizationRegion;
import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import com.example.hms.service.RegionPolicyService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantExportPackagerTest {

    @Mock private RegionPolicyService regionPolicyService;
    @Mock private com.example.hms.repository.TenantExportRepository exportRepository;

    private TenantExportPackager packager;

    @BeforeEach
    void setUp() {
        packager = new TenantExportPackager(regionPolicyService, exportRepository);
    }

    private Organization sampleOrg() {
        Organization org = new Organization();
        org.setId(UUID.randomUUID());
        org.setName("Acme Health");
        org.setCode("ACME");
        org.setRegion(OrganizationRegion.BF);
        org.setLifecycleState(OrganizationLifecycleState.PENDING_PURGE);
        org.setPrimaryContactEmail("ops@acme.test");

        Hospital hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        hospital.setName("Acme General");
        hospital.setCode("ACME-GEN");
        hospital.setCity("Ouagadougou");
        hospital.setCountry("BF");
        Set<Hospital> hospitals = new LinkedHashSet<>();
        hospitals.add(hospital);
        org.setHospitals(new HashSet<>(hospitals));
        return org;
    }

    private Set<String> entries(Path zip) throws IOException {
        Set<String> names = new LinkedHashSet<>();
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            zf.stream().map(ZipEntry::getName).forEach(names::add);
        }
        return names;
    }

    @Test
    void packagesManifestPlusOrgAndHospitalNdjson(@TempDir Path tmp) throws IOException {
        Organization org = sampleOrg();
        Path out = tmp.resolve("acme.zip");

        TenantExportPackager.PackageResult result = packager.packageOrganization(org, out);

        assertThat(out).exists();
        Set<String> names = entries(out);
        assertThat(names).contains(
            "manifest.json",
            "organization.ndjson",
            "hospitals.ndjson",
            "patients.ndjson",
            "staff.ndjson",
            "encounters.ndjson",
            "appointments.ndjson",
            "audit_events.ndjson");
        assertThat(result.recordCountsByTable())
            .containsEntry("organization.ndjson", 1L)
            .containsEntry("hospitals.ndjson", 1L);
    }

    @Test
    void manifestCarriesFormatVersionAndOrgIdentity(@TempDir Path tmp) throws IOException {
        Organization org = sampleOrg();
        Path out = tmp.resolve("manifest.zip");
        packager.packageOrganization(org, out);

        try (ZipFile zf = new ZipFile(out.toFile())) {
            byte[] body = zf.getInputStream(zf.getEntry("manifest.json")).readAllBytes();
            JsonNode manifest = new ObjectMapper().readTree(body);
            assertThat(manifest.get("format_version").asInt())
                .isEqualTo(TenantExportPackager.FORMAT_VERSION);
            assertThat(manifest.get("org_id").asText()).isEqualTo(org.getId().toString());
            assertThat(manifest.get("org_code").asText()).isEqualTo("ACME");
            assertThat(manifest.get("region").asText()).isEqualTo("BF");
        }
    }

    @Test
    void gdprPortabilityRegionEmitsExtraMetadataEntry(@TempDir Path tmp) throws IOException {
        Organization org = sampleOrg();
        org.setRegion(OrganizationRegion.EU);
        when(regionPolicyService.resolveDefaultExportFormat(OrganizationRegion.EU))
            .thenReturn("GDPR_PORTABILITY");

        Path out = tmp.resolve("gdpr.zip");
        packager.packageOrganization(org, out);

        assertThat(entries(out)).contains("gdpr_portability_metadata.json");
    }

    @Test
    void emptyOrgWithNoHospitalsStillProducesValidArchive(@TempDir Path tmp) throws IOException {
        Organization org = sampleOrg();
        org.setHospitals(new HashSet<>());
        Path out = tmp.resolve("empty.zip");

        TenantExportPackager.PackageResult result = packager.packageOrganization(org, out);

        assertThat(Files.size(out)).isPositive();
        assertThat(result.recordCountsByTable().get("hospitals.ndjson")).isZero();
    }

    @Test
    void manifestRecordCountsByTableIsPopulatedInsideArchive(@TempDir Path tmp) throws IOException {
        // Copilot review fix #1 — manifest used to be written before the
        // counts were computed, so the persisted manifest's
        // record_counts_by_table was always empty. After the fix it
        // reflects what was actually written.
        Organization org = sampleOrg();
        Path out = tmp.resolve("counts.zip");

        packager.packageOrganization(org, out);

        try (ZipFile zf = new ZipFile(out.toFile())) {
            byte[] body = zf.getInputStream(zf.getEntry("manifest.json")).readAllBytes();
            JsonNode manifest = new ObjectMapper().readTree(body);
            JsonNode counts = manifest.get("record_counts_by_table");
            assertThat(counts.isObject()).isTrue();
            assertThat(counts.get("organization.ndjson").asLong()).isEqualTo(1L);
            assertThat(counts.get("hospitals.ndjson").asLong()).isEqualTo(1L);
            assertThat(counts.get("patients.ndjson").asLong()).isZero();
        }
    }

    @Test
    void hospitalsNdjsonIsSortedByCodeForReproducibleArchives(@TempDir Path tmp) throws IOException {
        // Copilot review fix #2 — Set iteration was non-deterministic.
        // After the fix, hospitals.ndjson is ordered by code regardless
        // of the input set ordering.
        Organization org = sampleOrg();
        // Three hospitals in deliberately reverse-alphabetical order.
        Hospital z = new Hospital(); z.setId(UUID.randomUUID()); z.setName("Zeta"); z.setCode("ZED");
        Hospital m = new Hospital(); m.setId(UUID.randomUUID()); m.setName("Mid"); m.setCode("MID");
        Hospital a = new Hospital(); a.setId(UUID.randomUUID()); a.setName("Alpha"); a.setCode("ALP");
        Set<Hospital> hospitals = new HashSet<>();
        hospitals.add(z);
        hospitals.add(m);
        hospitals.add(a);
        org.setHospitals(hospitals);
        Path out = tmp.resolve("ordered.zip");

        packager.packageOrganization(org, out);

        try (ZipFile zf = new ZipFile(out.toFile())) {
            String body = new String(
                zf.getInputStream(zf.getEntry("hospitals.ndjson")).readAllBytes());
            // Each line is one record; codes appear in order ALP, MID, ZED.
            int alpIdx = body.indexOf("\"ALP\"");
            int midIdx = body.indexOf("\"MID\"");
            int zedIdx = body.indexOf("\"ZED\"");
            assertThat(alpIdx).isPositive();
            assertThat(alpIdx).isLessThan(midIdx);
            assertThat(midIdx).isLessThan(zedIdx);
        }
    }

    // ── The archive must contain the tenant's data (not just its metadata) ──

    private static String entry(Path zip, String name) throws IOException {
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            return new String(zf.getInputStream(zf.getEntry(name)).readAllBytes());
        }
    }

    @Test
    void patientsAreActuallyExported(@TempDir Path tmp) throws IOException {
        // THE REGRESSION. patients / staff / encounters / appointments /
        // audit_events were written as permanently empty placeholders — "real
        // content drops in via a follow-up" that never came. TenantPurgeExecutor
        // packages, encrypts, then DELETES the tenant, so this archive was the
        // only surviving copy and it held none of the records, while stamping
        // itself "GDPR Article 20 — Right to data portability".
        Organization org = sampleOrg();
        UUID patientId = UUID.randomUUID();
        when(exportRepository.exportPatients(anyCollection(),
                any()))
            .thenReturn(java.util.List.<Object[]>of(new Object[] {
                patientId, "Awa", "Traore", java.time.LocalDate.of(1990, 1, 1),
                "F", "+22670000000", "awa@test.bf", "Ouagadougou", "BF"
            }))
            .thenReturn(java.util.List.<Object[]>of());

        Path out = tmp.resolve("with-data.zip");
        packager.packageOrganization(org, out);

        String body = entry(out, "patients.ndjson");
        assertThat(body).contains(patientId.toString()).contains("Traore");
    }

    @Test
    void manifestCountsMatchWhatWasWritten(@TempDir Path tmp) throws IOException {
        // The manifest said 0 for every table and was telling the truth about
        // an archive that should not have been empty. It has to keep telling
        // the truth now that the tables have rows.
        Organization org = sampleOrg();
        when(exportRepository.exportEncounters(anyCollection(),
                any()))
            .thenReturn(java.util.List.of(
                new Object[] {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    "OUTPATIENT", java.time.LocalDateTime.now(), "COMPLETED", "cough"},
                new Object[] {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    "OUTPATIENT", java.time.LocalDateTime.now(), "COMPLETED", "fever"}))
            .thenReturn(java.util.List.<Object[]>of());

        Path out = tmp.resolve("counts.zip");
        packager.packageOrganization(org, out);

        JsonNode manifest = new ObjectMapper().readTree(entry(out, "manifest.json"));
        assertThat(manifest.get("record_counts_by_table").get("encounters.ndjson").asLong())
            .isEqualTo(2L);
        assertThat(entry(out, "encounters.ndjson").lines().count()).isEqualTo(2L);
    }

    @Test
    void everyTableEntryIsPresentEvenWhenEmpty(@TempDir Path tmp) throws IOException {
        // A consumer must not have to handle "missing entries" — the original
        // reason placeholders existed. Keep that property now they carry data.
        Organization org = sampleOrg();
        Path out = tmp.resolve("shape.zip");
        packager.packageOrganization(org, out);

        try (ZipFile zf = new ZipFile(out.toFile())) {
            for (String name : new String[] {"patients.ndjson", "staff.ndjson",
                    "encounters.ndjson", "appointments.ndjson", "audit_events.ndjson"}) {
                assertThat(zf.getEntry(name)).as("%s must exist", name).isNotNull();
            }
        }
    }

    @Test
    void tenantScopeComesFromTheOrgsHospitalsNotTheSecurityContext(@TempDir Path tmp) throws IOException {
        // The export runs from a scheduled purge with no principal on the
        // thread, so it must never depend on tenant context to decide scope.
        // Every query is handed the hospital ids explicitly.
        Organization org = sampleOrg();
        UUID hospitalId = org.getHospitals().iterator().next().getId();
        Path out = tmp.resolve("scope.zip");

        packager.packageOrganization(org, out);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<UUID>> ids =
            ArgumentCaptor.forClass(java.util.Collection.class);
        verify(exportRepository, atLeastOnce())
            .exportPatients(ids.capture(), any());
        assertThat(ids.getValue()).containsExactly(hospitalId);
    }

    // ── Column alignment ─────────────────────────────────────────────────
    //
    // Each row mapper pairs a fixed tuple index with a column name, and the
    // order of that tuple is decided elsewhere — in the JPQL SELECT list on
    // TenantExportRepository. Nothing in the type system connects the two:
    // every projection is Object[], so reordering a SELECT still compiles,
    // still runs, and still produces a well-formed archive with the values
    // filed under the wrong names.
    //
    // That is worth pinning here rather than trusting to review, because of
    // where this output goes. TenantPurgeExecutor packages, encrypts, then
    // deletes the tenant. A mislabelled archive is not a bug someone notices
    // and fixes — it is the only surviving copy of the data, and by the time
    // anyone reads it there is nothing left to compare it against.
    //
    // Hence deliberately distinguishable values in adjacent columns below
    // (09:00 vs 09:30, first vs last name), so a shift by one fails loudly
    // instead of asserting a value against itself.

    private static JsonNode firstRow(Path zip, String name) throws IOException {
        String body = entry(zip, name);
        assertThat(body).as("%s should have at least one row", name).isNotBlank();
        return new ObjectMapper().readTree(body.lines().findFirst().orElseThrow());
    }

    @Test
    void staffRowsCarryEveryColumnUnderItsOwnName(@TempDir Path tmp) throws IOException {
        Organization org = sampleOrg();
        UUID staffId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        when(exportRepository.exportStaff(anyCollection(), any()))
            .thenReturn(java.util.List.<Object[]>of(new Object[] {
                staffId, "Fatou", "Diallo", "fatou@acme.test",
                "Registered Nurse", "Paediatrics", hospitalId
            }))
            .thenReturn(java.util.List.<Object[]>of());

        Path out = tmp.resolve("staff.zip");
        packager.packageOrganization(org, out);

        JsonNode row = firstRow(out, "staff.ndjson");
        assertThat(row.get("id").asText()).isEqualTo(staffId.toString());
        assertThat(row.get("first_name").asText()).isEqualTo("Fatou");
        assertThat(row.get("last_name").asText()).isEqualTo("Diallo");
        assertThat(row.get("email").asText()).isEqualTo("fatou@acme.test");
        assertThat(row.get("job_title").asText()).isEqualTo("Registered Nurse");
        assertThat(row.get("specialization").asText()).isEqualTo("Paediatrics");
        assertThat(row.get("hospital_id").asText()).isEqualTo(hospitalId.toString());
    }

    @Test
    void appointmentRowsKeepStartAndEndDistinct(@TempDir Path tmp) throws IOException {
        Organization org = sampleOrg();
        UUID apptId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        when(exportRepository.exportAppointments(anyCollection(), any()))
            .thenReturn(java.util.List.<Object[]>of(new Object[] {
                apptId, patientId, staffId, hospitalId,
                java.time.LocalDate.of(2026, 3, 4),
                java.time.LocalTime.of(9, 0), java.time.LocalTime.of(9, 30),
                "COMPLETED", "follow-up"
            }))
            .thenReturn(java.util.List.<Object[]>of());

        Path out = tmp.resolve("appointments.zip");
        packager.packageOrganization(org, out);

        JsonNode row = firstRow(out, "appointments.ndjson");
        assertThat(row.get("id").asText()).isEqualTo(apptId.toString());
        assertThat(row.get("patient_id").asText()).isEqualTo(patientId.toString());
        assertThat(row.get("staff_id").asText()).isEqualTo(staffId.toString());
        assertThat(row.get("hospital_id").asText()).isEqualTo(hospitalId.toString());
        assertThat(row.get("appointment_date").asText()).isEqualTo("2026-03-04");
        assertThat(row.get("start_time").asText()).isEqualTo("09:00");
        assertThat(row.get("end_time").asText()).isEqualTo("09:30");
        assertThat(row.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(row.get("reason").asText()).isEqualTo("follow-up");
    }

    @Test
    void auditEventRowsCarryThePatientKey(@TempDir Path tmp) throws IOException {
        // patient_id is the column V141 added. Without it the audit stream in
        // the archive can say an export happened but not whose record it
        // concerned — which is the one question a subject access request asks.
        Organization org = sampleOrg();
        UUID eventId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        when(exportRepository.exportAuditEvents(anyCollection(), any()))
            .thenReturn(java.util.List.<Object[]>of(new Object[] {
                eventId, "PATIENT_EXPORT",
                java.time.LocalDateTime.of(2026, 3, 4, 11, 15),
                "dr.kabore", "DOCTOR", "PATIENT", patientId.toString(),
                patientId, "SUCCESS"
            }))
            .thenReturn(java.util.List.<Object[]>of());

        Path out = tmp.resolve("audit.zip");
        packager.packageOrganization(org, out);

        JsonNode row = firstRow(out, "audit_events.ndjson");
        assertThat(row.get("id").asText()).isEqualTo(eventId.toString());
        assertThat(row.get("event_type").asText()).isEqualTo("PATIENT_EXPORT");
        assertThat(row.get("event_timestamp").asText()).isEqualTo("2026-03-04T11:15");
        assertThat(row.get("actor").asText()).isEqualTo("dr.kabore");
        assertThat(row.get("role").asText()).isEqualTo("DOCTOR");
        assertThat(row.get("entity_type").asText()).isEqualTo("PATIENT");
        assertThat(row.get("patient_id").asText()).isEqualTo(patientId.toString());
        assertThat(row.get("status").asText()).isEqualTo("SUCCESS");
    }

    @Test
    void absentValuesAreJsonNullNotTheWordNull(@TempDir Path tmp) throws IOException {
        // Most of these columns are genuinely nullable — a walk-in with no
        // recorded email, an appointment with no reason, an audit event with
        // no patient. They stringify through a null-safe helper, and the
        // failure mode if it stopped being null-safe is not a crash: it is the
        // four-character string "null" landing in the archive as though the
        // patient's city were spelled that way.
        Organization org = sampleOrg();
        UUID patientId = UUID.randomUUID();
        when(exportRepository.exportPatients(anyCollection(), any()))
            .thenReturn(java.util.List.<Object[]>of(new Object[] {
                patientId, "Awa", "Traore", null, null, null, null, null, null
            }))
            .thenReturn(java.util.List.<Object[]>of());

        Path out = tmp.resolve("nulls.zip");
        packager.packageOrganization(org, out);

        JsonNode row = firstRow(out, "patients.ndjson");
        assertThat(row.get("id").asText()).isEqualTo(patientId.toString());
        for (String column : new String[] {
                "date_of_birth", "gender", "phone_number", "email", "city", "country"}) {
            assertThat(row.get(column))
                .as("%s should be present and JSON null", column)
                .isNotNull();
            assertThat(row.get(column).isNull())
                .as("%s must be JSON null, not the string \"null\"", column)
                .isTrue();
        }
    }

    @Test
    void anOrgWhoseHospitalsAreNullIsScopedToNothingRatherThanEverything(@TempDir Path tmp)
            throws IOException {
        // hospitals is a lazy association; on a detached Organization it can
        // be null rather than empty. The scope of this export is derived from
        // it, so the null case must resolve to "no hospitals" — and must do so
        // before any query runs. Handing an empty collection to an IN clause
        // leaves the meaning of IN () to the provider, which is not a decision
        // to delegate when the answer decides whose records get archived.
        Organization org = sampleOrg();
        org.setHospitals(null);
        Path out = tmp.resolve("null-hospitals.zip");

        TenantExportPackager.PackageResult result = packager.packageOrganization(org, out);

        assertThat(out).exists();
        assertThat(entries(out)).contains("patients.ndjson", "staff.ndjson",
            "encounters.ndjson", "appointments.ndjson", "audit_events.ndjson");
        assertThat(result.recordCountsByTable())
            .containsEntry("patients.ndjson", 0L)
            .containsEntry("audit_events.ndjson", 0L);
        verifyNoInteractions(exportRepository);
    }
}
