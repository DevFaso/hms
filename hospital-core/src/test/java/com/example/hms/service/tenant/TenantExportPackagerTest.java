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
}
