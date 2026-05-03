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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantExportPackagerTest {

    @Mock private RegionPolicyService regionPolicyService;

    private TenantExportPackager packager;

    @BeforeEach
    void setUp() {
        packager = new TenantExportPackager(regionPolicyService);
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
}
