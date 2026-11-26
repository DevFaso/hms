package com.example.hms.architecture;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;
import static org.assertj.core.api.Assertions.assertThat;

class PatientCollectionBloodGroupGuardTest {
    // Only these exact single-record methods are exempt; collection bodies in either file are always scanned.
    private static final Map<String, String> SINGLE_METHODS = Map.of(
        "mapper/PatientConsentMapper.java", "public PatientConsentResponseDTO toDto(PatientConsent consent)",
        "service/PatientServiceImpl.java", "private PatientResponseDTO buildPatientDto(Patient patient, UUID hospitalId)");

    @Test
    void mapperCollectionBoundaryUsesTheBatchLookup() throws Exception {
        String mapper = Files.readString(Path.of("src/main/java/com/example/hms/mapper/PatientMapper.java"));
        String collection = mapper.substring(mapper.indexOf("public List<PatientResponseDTO> toPatientDTOs("),
            mapper.indexOf("/** Status flags"));
        assertThat(collection).contains("bloodGroupStatus.ofAll(", "batchGroup(patient, groups)")
            .doesNotContain("bloodGroupStatus.of(");
    }

    @Test
    void newPatientMapperCallersMustChooseTheBatchOrDocumentASingleRecord() throws Exception {
        Path root = Path.of("src/main/java/com/example/hms");
        Pattern individualCall = Pattern.compile("patientMapper\\s*(?:\\.\\s*toPatientDTO\\s*\\(|::toPatientDTO\\b)");
        try (var sources = Files.walk(root)) {
            for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = com.example.hms.testsupport.SourceScan.stripComments(Files.readString(source));
                String relative = root.relativize(source).toString().replace('\\', '/');
                String singleMethod = SINGLE_METHODS.get(relative);
                if (singleMethod != null) text = withoutMethod(text, singleMethod);
                assertThat(individualCall.matcher(text).find())
                    .as("new individual mapper caller %s: use the collection API", relative).isFalse();
                assertThat(text).as("per-row chart mapping in %s", source)
                    .doesNotContain(".map(patient -> buildPatientDto(", ".map(p -> buildPatientDto(",
                        ".map(this::enrichRegistration)");
            }
        }
    }

    @Test
    void consentCollectionsResolveScopeBeforePagingAndNeverReuseTheOriginsClinicalScope() throws Exception {
        String service = Files.readString(Path.of("src/main/java/com/example/hms/service/PatientConsentServiceImpl.java"));
        assertThat(service).contains("roleValidator.requireActiveHospitalId()", "findVisibleInHospital(scope, null, pageable)",
            "findVisibleInHospital(scope, patientId, pageable)", "scope != null ? scope : consent.getFromHospital().getId()");
        String repository = Files.readString(Path.of("src/main/java/com/example/hms/repository/PatientConsentRepository.java"));
        assertThat(repository).contains("c.fromHospital.id = :hospitalId OR c.toHospital.id = :hospitalId",
            ":patientId IS NULL OR c.patient.id = :patientId");
    }

    /** Exempt the one declared helper, never the service's collection bodies. */
    private static String withoutSingleChartHelper(String source) {
        return withoutMethod(source, SINGLE_METHODS.get("service/PatientServiceImpl.java"));
    }

    private static String withoutMethod(String source, String signature) {
        int method = source.indexOf(signature);
        assertThat(method).as("the single-record exemption names one real method").isGreaterThanOrEqualTo(0);
        int open = source.indexOf('{', method);
        int close = com.example.hms.testsupport.SourceScan.closingBrace(source, open);
        return source.substring(0, method) + source.substring(close + 1);
    }

    @Test
    void directPerRowMappingInPatientServiceIsNeverExempt() throws Exception {
        String service = Files.readString(Path.of("src/main/java/com/example/hms/service/PatientServiceImpl.java"));
        String mutation = "void collection(List<Patient> patients, UUID hospitalId) { patients.forEach(p -> patientMapper.toPatientDTO(p, hospitalId)); }";
        int end = service.lastIndexOf('}');
        String mutated = service.substring(0, end) + mutation + service.substring(end);
        assertThat(withoutSingleChartHelper(mutated)).contains(mutation);
        assertThat(Pattern.compile("patientMapper\\s*\\.\\s*toPatientDTO\\s*\\(")
            .matcher(withoutSingleChartHelper(mutated)).find()).isTrue();
    }
}
