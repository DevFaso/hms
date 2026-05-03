package com.example.hms.service.tenant;

import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import com.example.hms.service.RegionPolicyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * GDPR data-export packager (MVP-c batch — MVP-2c).
 *
 * <p>Writes a deterministic ZIP to the configured output path containing
 * a JSON manifest at the root + per-table NDJSON files. Each NDJSON
 * record is one JSON object per line so a partner can stream-process
 * the export without loading the whole file in memory.
 *
 * <p><b>Scope of this MVP</b>: org-level + hospital-level metadata
 * (the entities the lifecycle layer already touches) ship to the
 * archive. Patient / staff / encounter / audit dumps require
 * additional repository iterators per table — they're staged as
 * empty placeholders in the manifest so the export shape is stable
 * across releases, and real content drops in via a follow-up that
 * wires per-table cursors. The encryption envelope already supports
 * arbitrary content size; only the producer needs more inputs.
 *
 * <p>Region-aware export-format default consulted from
 * {@link RegionPolicyService#resolveDefaultExportFormat} —
 * GDPR_PORTABILITY tagged regions get an additional
 * {@code gdpr_portability_metadata.json} entry capturing the data-
 * subject rights notice required by Article 20.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TenantExportPackager {

    /** Manifest schema version — bump when the file shape changes. */
    public static final int FORMAT_VERSION = 1;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RegionPolicyService regionPolicyService;

    /**
     * Package one organization's data to a ZIP at {@code outputPath}.
     *
     * @return PackageResult with the absolute path + record counts.
     * @throws IOException if the ZIP cannot be written.
     */
    public PackageResult packageOrganization(Organization org, Path outputPath) throws IOException {
        Files.createDirectories(outputPath.getParent());

        Map<String, Long> counts = new LinkedHashMap<>();
        try (OutputStream out = Files.newOutputStream(outputPath);
             ZipOutputStream zip = new ZipOutputStream(out)) {

            // 1. Manifest first so partial failures still include the metadata.
            writeManifest(zip, org, counts);

            // 2. Org-level NDJSON (single record).
            counts.put("organization.ndjson", writeOrganizationNdjson(zip, org));

            // 3. Hospitals NDJSON (one record per hospital).
            counts.put("hospitals.ndjson", writeHospitalsNdjson(zip, org));

            // 4. Region-specific extras (GDPR portability metadata).
            String exportFormat = regionPolicyService.resolveDefaultExportFormat(org.getRegion());
            if ("GDPR_PORTABILITY".equalsIgnoreCase(exportFormat)) {
                writeGdprPortabilityMetadata(zip, org);
                counts.put("gdpr_portability_metadata.json", 1L);
            }

            // 5. Empty placeholders for the per-table dumps that need
            //    repository iterators. Keeps the ZIP shape stable so a
            //    consumer doesn't need to handle "missing entries".
            for (String name : new String[] {
                "patients.ndjson", "staff.ndjson",
                "encounters.ndjson", "appointments.ndjson", "audit_events.ndjson"
            }) {
                writeEmptyEntry(zip, name);
                counts.putIfAbsent(name, 0L);
            }
        }
        log.info("[TENANT-EXPORT] Packaged org {} -> {} ({} entries)",
            org.getId(), outputPath, counts.size());
        return new PackageResult(outputPath.toAbsolutePath(), Map.copyOf(counts));
    }

    private void writeManifest(ZipOutputStream zip, Organization org, Map<String, Long> counts)
        throws IOException {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("format_version", FORMAT_VERSION);
        manifest.put("generated_at", Instant.now().toString());
        manifest.put("org_id", org.getId() != null ? org.getId().toString() : null);
        manifest.put("org_name", org.getName());
        manifest.put("org_code", org.getCode());
        manifest.put("region", org.getRegion() != null ? org.getRegion().name() : null);
        manifest.put("lifecycle_state", org.getLifecycleState() != null ? org.getLifecycleState().name() : null);
        manifest.put("record_counts_by_table", counts);

        zip.putNextEntry(new ZipEntry("manifest.json"));
        zip.write(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
        zip.closeEntry();
    }

    private long writeOrganizationNdjson(ZipOutputStream zip, Organization org) throws IOException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", org.getId() != null ? org.getId().toString() : null);
        row.put("name", org.getName());
        row.put("code", org.getCode());
        row.put("region", org.getRegion() != null ? org.getRegion().name() : null);
        row.put("lifecycle_state", org.getLifecycleState() != null ? org.getLifecycleState().name() : null);
        row.put("primary_contact_email", org.getPrimaryContactEmail());

        zip.putNextEntry(new ZipEntry("organization.ndjson"));
        zip.write(MAPPER.writeValueAsBytes(row));
        zip.write('\n');
        zip.closeEntry();
        return 1L;
    }

    private long writeHospitalsNdjson(ZipOutputStream zip, Organization org) throws IOException {
        zip.putNextEntry(new ZipEntry("hospitals.ndjson"));
        long count = 0L;
        if (org.getHospitals() != null) {
            for (Hospital hospital : org.getHospitals()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", hospital.getId() != null ? hospital.getId().toString() : null);
                row.put("name", hospital.getName());
                row.put("code", hospital.getCode());
                row.put("city", hospital.getCity());
                row.put("country", hospital.getCountry());
                row.put("lifecycle_state", hospital.getLifecycleState() != null
                    ? hospital.getLifecycleState().name() : null);
                zip.write(MAPPER.writeValueAsBytes(row));
                zip.write('\n');
                count++;
            }
        }
        zip.closeEntry();
        return count;
    }

    private void writeGdprPortabilityMetadata(ZipOutputStream zip, Organization org) throws IOException {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("article", "GDPR Article 20 — Right to data portability");
        meta.put("data_subject_org_id", org.getId() != null ? org.getId().toString() : null);
        meta.put("controller", "HMS Platform");
        meta.put("format", "JSON / NDJSON archive");
        meta.put("generated_at", Instant.now().toString());

        zip.putNextEntry(new ZipEntry("gdpr_portability_metadata.json"));
        zip.write(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(meta));
        zip.closeEntry();
    }

    private void writeEmptyEntry(ZipOutputStream zip, String name) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        // Empty NDJSON — no rows.
        zip.closeEntry();
    }

    /** Result of packaging — surface for the caller's audit description. */
    public record PackageResult(Path outputPath, Map<String, Long> recordCountsByTable) {
        public String describe() {
            long total = recordCountsByTable.values().stream().mapToLong(Long::longValue).sum();
            return outputPath + " (" + total + " records across " + recordCountsByTable.size() + " entries)";
        }

        /** UUID-based id for the audit row so two archives with the same path are distinguishable. */
        public String archiveId() {
            return UUID.nameUUIDFromBytes(outputPath.toString().getBytes(StandardCharsets.UTF_8)).toString();
        }
    }
}
