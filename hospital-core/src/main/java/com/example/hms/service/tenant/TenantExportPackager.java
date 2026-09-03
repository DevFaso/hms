package com.example.hms.service.tenant;

import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import com.example.hms.service.RegionPolicyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
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

    private static final String FIELD_LIFECYCLE_STATE = "lifecycle_state";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RegionPolicyService regionPolicyService;
    private final com.example.hms.repository.TenantExportRepository exportRepository;

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

            // 1. Org-level NDJSON (single record).
            counts.put("organization.ndjson", writeOrganizationNdjson(zip, org));

            // 2. Hospitals NDJSON (one record per hospital).
            counts.put("hospitals.ndjson", writeHospitalsNdjson(zip, org));

            // 3. Region-specific extras (GDPR portability metadata).
            String exportFormat = regionPolicyService.resolveDefaultExportFormat(org.getRegion());
            if ("GDPR_PORTABILITY".equalsIgnoreCase(exportFormat)) {
                writeGdprPortabilityMetadata(zip, org);
                counts.put("gdpr_portability_metadata.json", 1L);
            }

            // 4. The tenant's actual records.
            //
            // These were empty placeholders — "staged ... real content drops
            // in via a follow-up that wires per-table cursors". The follow-up
            // never came, so a tenant archive contained the organisation row
            // and its hospitals and NOTHING ELSE, while still stamping itself
            // "GDPR Article 20 — Right to data portability". TenantPurgeExecutor
            // packages, encrypts, then DELETES the tenant: the archive was the
            // only surviving copy and it held none of the data.
            List<UUID> hospitalIds = hospitalIdsOf(org);
            if (hospitalIds.isEmpty()) {
                // No hospitals means no rows to scope to. Still write the
                // entries so the archive shape stays stable for consumers.
                for (String name : TABLE_ENTRIES) {
                    writeEmptyEntry(zip, name);
                    counts.putIfAbsent(name, 0L);
                }
            } else {
                counts.put("patients.ndjson", writeTable(zip, "patients.ndjson", hospitalIds,
                    exportRepository::exportPatients, TenantExportPackager::patientRow));
                counts.put("staff.ndjson", writeTable(zip, "staff.ndjson", hospitalIds,
                    exportRepository::exportStaff, TenantExportPackager::staffRow));
                counts.put("encounters.ndjson", writeTable(zip, "encounters.ndjson", hospitalIds,
                    exportRepository::exportEncounters, TenantExportPackager::encounterRow));
                counts.put("appointments.ndjson", writeTable(zip, "appointments.ndjson", hospitalIds,
                    exportRepository::exportAppointments, TenantExportPackager::appointmentRow));
                counts.put("audit_events.ndjson", writeTable(zip, "audit_events.ndjson", hospitalIds,
                    exportRepository::exportAuditEvents, TenantExportPackager::auditEventRow));
            }

            // 5. Manifest LAST so record_counts_by_table reflects what's
            //    actually in the archive (Copilot review fix — earlier
            //    write-first approach left this field empty in every
            //    archive, defeating the manifest's purpose).
            writeManifest(zip, org, counts);
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
        manifest.put(FIELD_LIFECYCLE_STATE, org.getLifecycleState() != null ? org.getLifecycleState().name() : null);
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
        row.put(FIELD_LIFECYCLE_STATE, org.getLifecycleState() != null ? org.getLifecycleState().name() : null);
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
        // Copilot review fix — Organization.hospitals is a Set with no
        // defined iteration order (HashSet under Hibernate), so iterating
        // it directly made hospitals.ndjson row order vary across runs.
        // Sort by code (stable, business-meaningful) so the archive is
        // byte-reproducible and downstream hashing / signing holds.
        List<Hospital> ordered = new ArrayList<>();
        if (org.getHospitals() != null) {
            ordered.addAll(org.getHospitals());
            ordered.sort(Comparator.comparing(
                Hospital::getCode,
                Comparator.nullsLast(Comparator.naturalOrder())));
        }
        for (Hospital hospital : ordered) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", hospital.getId() != null ? hospital.getId().toString() : null);
            row.put("name", hospital.getName());
            row.put("code", hospital.getCode());
            row.put("city", hospital.getCity());
            row.put("country", hospital.getCountry());
            row.put(FIELD_LIFECYCLE_STATE, hospital.getLifecycleState() != null
                ? hospital.getLifecycleState().name() : null);
            zip.write(MAPPER.writeValueAsBytes(row));
            zip.write('\n');
            count++;
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

    /** The per-table NDJSON entries, in the order they are written. */
    private static final String[] TABLE_ENTRIES = {
        "patients.ndjson", "staff.ndjson",
        "encounters.ndjson", "appointments.ndjson", "audit_events.ndjson"
    };

    /**
     * Rows per query. The archive is written to a stream, so nothing
     * accumulates in memory across pages — but a whole tenant's audit history
     * in one query would, which is the point of paging at all.
     */
    private static final int EXPORT_PAGE_SIZE = 500;

    /** Fetches one page of a table's rows. */
    @FunctionalInterface
    private interface PageFetcher {
        List<Object[]> fetch(Collection<UUID> hospitalIds, Pageable pageable);
    }

    /** Turns one projection tuple into the NDJSON object for its table. */
    @FunctionalInterface
    private interface RowMapper {
        Map<String, Object> map(Object[] tuple);
    }

    private List<UUID> hospitalIdsOf(Organization org) {
        if (org.getHospitals() == null) {
            return List.of();
        }
        return org.getHospitals().stream()
            .map(Hospital::getId)
            .filter(java.util.Objects::nonNull)
            .sorted()
            .toList();
    }

    /**
     * Stream one table into the archive, a page at a time.
     *
     * <p>Rows go straight to the ZIP stream as they arrive; only one page is
     * ever held. Paging stops on a short page, so a table that grows between
     * pages cannot loop — the queries order by id, so the boundary is stable.
     */
    private long writeTable(ZipOutputStream zip, String entryName, List<UUID> hospitalIds,
                            PageFetcher fetcher, RowMapper mapper) throws IOException {
        zip.putNextEntry(new ZipEntry(entryName));
        long count = 0L;
        int page = 0;
        List<Object[]> batch;
        do {
            batch = fetcher.fetch(hospitalIds, PageRequest.of(page, EXPORT_PAGE_SIZE));
            for (Object[] tuple : batch) {
                zip.write(MAPPER.writeValueAsBytes(mapper.map(tuple)));
                zip.write('\n');
                count++;
            }
            page++;
        } while (batch.size() == EXPORT_PAGE_SIZE);
        zip.closeEntry();
        return count;
    }

    /** Null-safe stringify — UUIDs, enums and temporals all render as text. */
    private static String text(Object value) {
        return value != null ? value.toString() : null;
    }

    private static Map<String, Object> patientRow(Object[] t) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", text(t[0]));
        row.put("first_name", t[1]);
        row.put("last_name", t[2]);
        row.put("date_of_birth", text(t[3]));
        row.put("gender", t[4]);
        row.put("phone_number", t[5]);
        row.put("email", t[6]);
        row.put("city", t[7]);
        row.put("country", t[8]);
        return row;
    }

    private static Map<String, Object> staffRow(Object[] t) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", text(t[0]));
        row.put("first_name", t[1]);
        row.put("last_name", t[2]);
        row.put("email", t[3]);
        row.put("job_title", text(t[4]));
        row.put("specialization", t[5]);
        row.put("hospital_id", text(t[6]));
        return row;
    }

    private static Map<String, Object> encounterRow(Object[] t) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", text(t[0]));
        row.put("patient_id", text(t[1]));
        row.put("hospital_id", text(t[2]));
        row.put("encounter_type", text(t[3]));
        row.put("encounter_date", text(t[4]));
        row.put("status", text(t[5]));
        row.put("chief_complaint", t[6]);
        return row;
    }

    private static Map<String, Object> appointmentRow(Object[] t) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", text(t[0]));
        row.put("patient_id", text(t[1]));
        row.put("staff_id", text(t[2]));
        row.put("hospital_id", text(t[3]));
        row.put("appointment_date", text(t[4]));
        row.put("start_time", text(t[5]));
        row.put("end_time", text(t[6]));
        row.put("status", text(t[7]));
        row.put("reason", t[8]);
        return row;
    }

    private static Map<String, Object> auditEventRow(Object[] t) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", text(t[0]));
        row.put("event_type", text(t[1]));
        row.put("event_timestamp", text(t[2]));
        row.put("actor", t[3]);
        row.put("role", t[4]);
        row.put("entity_type", t[5]);
        row.put("resource_id", t[6]);
        row.put("patient_id", text(t[7]));
        row.put("status", text(t[8]));
        return row;
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
