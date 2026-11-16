package com.example.hms.architecture;

import com.example.hms.model.Patient;
import com.example.hms.payload.dto.PatientRequestDTO;
import com.example.hms.payload.dto.PatientProfileUpdateRequestDTO;
import com.example.hms.security.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class PatientNationalUniqueIdGuardTest {
    private static final Path MAIN = Path.of("src/main/java/com/example/hms");
    private static final Set<String> READERS = Set.of("model/Patient.java", "mapper/PatientMapper.java",
        "service/PatientNationalUniqueIdService.java", "service/empi/PatientNationalUniqueIdAliases.java",
        "service/impl/PatientPortalServiceImpl.java", "service/PatientServiceImpl.java",
        "controller/PatientNationalUniqueIdController.java");

    @Test
    void optionalEncryptedFieldHasANonUniqueKeyedLookupAndRegisteredMigration() throws Exception {
        for (Class<?> type : new Class<?>[] {Patient.class, PatientRequestDTO.class, PatientProfileUpdateRequestDTO.class,
                com.example.hms.controller.PatientNationalUniqueIdController.Lookup.class}) {
            var field = type.getDeclaredField("nationalUniqueId");
            assertThat(field.getAnnotations()).noneMatch(annotation ->
                Set.of("NotBlank", "NotNull", "NotEmpty").contains(annotation.annotationType().getSimpleName()));
        }
        var field = Patient.class.getDeclaredField("nationalUniqueId");
        assertThat(field.getAnnotation(Convert.class).converter()).isEqualTo(EncryptedStringConverter.class);
        assertThat(field.getAnnotation(Column.class).columnDefinition()).isEqualTo("TEXT");
        assertThat(Patient.class.getDeclaredField("nationalUniqueIdHash").getAnnotation(Column.class).unique()).isFalse();
        String codec = Files.readString(MAIN.resolve("security/PatientNationalUniqueId.java"));
        assertThat(codec).contains("HmacSHA256", "EncryptionKeyHolder.getKey()", "LOOKUP_DOMAIN")
            .doesNotContain("MessageDigest");
        Path migrations = Path.of("src/main/resources/db/migration");
        assertThat(Files.readString(migrations.resolve("changelog.xml"))).contains("V321__patient_national_unique_id.sql");
        assertThat(Files.readString(migrations.resolve("V321__patient_national_unique_id.sql")))
            .contains("national_unique_id TEXT", "CREATE INDEX IF NOT EXISTS idx_patient_national_unique_id_hash");
    }

    @Test
    void everyClearValueReaderIsAnIdentityBoundaryAndNeverALogOrExport() throws Exception {
        try (var files = Files.walk(MAIN)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = com.example.hms.testsupport.SourceScan.stripComments(Files.readString(file));
                String relative = MAIN.relativize(file).toString().replace('\\', '/');
                if (Pattern.compile("getNationalUniqueId(?:Hash|Masked)?\\s*\\(").matcher(source).find()) {
                    assertThat(READERS).as(relative).contains(relative);
                }
                assertThat(logsIdentity(source)).as(relative + " must not log an identifier or its property name").isFalse();
            }
        }
        assertThat(logsIdentity("log.info(\"nationalUniqueId={}\", value);")).isTrue();
        assertThat(logsIdentity("log.debug(\"000123456789\");")).isTrue();
        assertThat(logsIdentity("log.info(\"Patient updated: {}\", patient.getId());")).isFalse();
    }

    @Test
    void allPatientIdentityWritersCompleteRequiredAuditAndPrivateAliasSynchronization() throws Exception {
        String source = Files.readString(MAIN.resolve("service/PatientServiceImpl.java"));
        for (String method : Set.of("createPatient", "createPatientByStaff", "updatePatient", "patchPatient")) {
            String body = methodBody(source, "public PatientResponseDTO " + method + "(");
            assertThat(body).as(method).contains("prepareNationalUniqueId(", "completeNationalUniqueId(");
        }
        String service = Files.readString(MAIN.resolve("service/PatientNationalUniqueIdService.java"));
        assertThat(service).contains("aliases.synchronize(patient)", "audit.getObject().logRequiredEvent(",
            "patients.isFindableByPatientSearch(id)", "PatientNationalUniqueId.auditReason(reason)");
        String identityLoad = methodBody(service, "private Patient requirePatient(");
        assertThat(identityLoad).contains("roles.requireActiveHospitalId()", "patients.isFindableByPatientSearch(id)");
        assertThat(identityLoad.indexOf("patients.isFindableByPatientSearch(id)"))
            .isLessThan(identityLoad.indexOf("patients.findByIdUnscoped(id)"));
        String aliases = Files.readString(MAIN.resolve("service/empi/PatientNationalUniqueIdAliases.java"));
        assertThat(aliases).contains(".aliasValue(patient.getNationalUniqueIdHash())", ".nationalUniqueIdPatientId(patient.getId())");
    }

    @Test
    void publicEmpiAndExportsCannotRevealOrMatchPrivateAliases() throws Exception {
        String mapper = Files.readString(MAIN.resolve("mapper/EmpiMapper.java"));
        assertThat(mapper).contains(".filter(alias -> !alias.isNationalUniqueIdAlias())",
            "alias == null || alias.isNationalUniqueIdAlias()");
        assertThat(Files.readString(MAIN.resolve("repository/empi/EmpiIdentityAliasRepository.java")))
            .contains("a.nationalUniqueIdPatientId IS NULL");
        assertThat(Files.readString(MAIN.resolve("service/empi/EmpiServiceImpl.java")))
            .contains("!alias.isNationalUniqueIdAlias() && aliasId.equals(alias.getId())",
                "java.util.Objects.equals(existing.getNationalUniqueIdPatientId(), alias.getNationalUniqueIdPatientId())");
    }

    @Test
    void fullDisclosureAndDuplicateChecksKeepTheIdentityEditorGateAndRequiredAudit() throws Exception {
        Set<String> editors = Set.of("ROLE_RECEPTIONIST", "ROLE_NURSE", "ROLE_MIDWIFE", "ROLE_DOCTOR",
            "ROLE_HOSPITAL_ADMIN", "ROLE_SUPER_ADMIN");
        for (var method : com.example.hms.controller.PatientNationalUniqueIdController.class.getDeclaredMethods()) {
            if (!Set.of("check", "reveal").contains(method.getName())) continue;
            var authorization = method.getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class);
            assertThat(authorization).isNotNull();
            Set<String> actual = Pattern.compile("ROLE_[A-Z_]+").matcher(authorization.value()).results()
                .map(java.util.regex.MatchResult::group).collect(java.util.stream.Collectors.toSet());
            assertThat(actual).isEqualTo(editors);
        }
        String service = Files.readString(MAIN.resolve("service/PatientNationalUniqueIdService.java"));
        String reveal = methodBody(service, "public String reveal(");
        assertThat(reveal).contains("requireEditor()", "requirePatient(patientId)", "recordAudit(");
        assertThat(reveal.indexOf("recordAudit(")).isLessThan(reveal.indexOf("return PatientNationalUniqueId.formatted("));
        var audit = com.example.hms.service.AuditEventLogServiceImpl.class.getMethod("logRequiredEvent",
            com.example.hms.payload.dto.AuditEventRequestDTO.class);
        assertThat(audit.getAnnotation(org.springframework.transaction.annotation.Transactional.class).propagation())
            .isEqualTo(org.springframework.transaction.annotation.Propagation.MANDATORY);
    }

    private static boolean logsIdentity(String source) {
        return Pattern.compile("(?:log|logger)\\.(?:trace|debug|info|warn|error)\\s*\\([^;]*(?:nationalUniqueId|NationalUniqueId|000123456789)[^;]*;",
            Pattern.DOTALL).matcher(source).find();
    }

    @Test
    void everyPatientResponseProducerIsClassifiedAndBothIdentityMappersKeepSafetyStatus() throws Exception {
        var producers = new java.util.HashSet<String>();
        try (var files = Files.walk(MAIN)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (sourceAt(file).contains("PatientResponseDTO.builder(")) producers.add(MAIN.relativize(file).toString().replace('\\', '/'));
            }
        }
        assertThat(producers).containsExactlyInAnyOrder("mapper/PatientMapper.java",
            "service/PatientNationalUniqueIdService.java", "service/impl/NurseDashboardServiceImpl.java");
        // The nurse projection is deliberately a name-only selector, not a patient-status response.
        String names = methodBody(sourceAt(MAIN.resolve("service/impl/NurseDashboardServiceImpl.java")),
            "private static PatientResponseDTO nameOnly(");
        assertThat(names).contains("row.patientId()", "row.firstName()", "row.lastName()")
            .doesNotContain(".active(", ".deceased(", ".chartRestricted(");
        String mapper = sourceAt(MAIN.resolve("mapper/PatientMapper.java"));
        assertThat(methodBody(mapper, "public PatientResponseDTO toPatientDTO(Patient patient,\n"))
            .contains("withIdentityStatus(PatientResponseDTO.builder(), patient)");
        String flags = methodBody(mapper, "public static PatientResponseDTO.PatientResponseDTOBuilder withIdentityStatus(");
        assertThat(flags).contains(".active(patient.isActive())", ".deceased(patient.isDeceased())",
            ".deceasedAt(patient.getDeceasedAt())", "DeceasedPatientGuard.deathDisputedHere(patient)",
            "DeceasedPatientGuard.careBlocked(patient)", ".chartRestricted(patient.isChartRestricted())");
        String lookup = methodBody(sourceAt(MAIN.resolve("service/PatientNationalUniqueIdService.java")),
            "private static PatientResponseDTO identityMatch(");
        assertThat(lookup).contains("PatientMapper.withIdentityStatus(PatientResponseDTO.builder(), patient)")
            .doesNotContain("getMedicalHistorySummary(", "getCareTeamNotes(", "getAllergies(", "getChartRestrictionReason(");
    }

    private static String sourceAt(Path path) throws Exception {
        return com.example.hms.testsupport.SourceScan.stripComments(Files.readString(path)).replace("\r\n", "\n");
    }

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertThat(start).as(signature).isNotNegative();
        int open = source.indexOf('{', start), depth = 1, end = open + 1;
        while (depth != 0 && end < source.length()) {
            char c = source.charAt(end++);
            if (c == '{') depth++; else if (c == '}') depth--;
        }
        return source.substring(open, end);
    }
}
