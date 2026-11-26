package com.example.hms.architecture;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;
import static org.assertj.core.api.Assertions.assertThat;

class PatientCollectionBloodGroupGuardTest {
    private static final Map<String, String> SINGLE_READS = Map.of(
        "mapper/PatientConsentMapper.java", "one consent response contains one patient",
        "service/PatientServiceImpl.java", "single chart helper; collection paths must use buildPatientDtos");

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
                String text = Files.readString(source);
                if (individualCall.matcher(text).find()) {
                    String relative = root.relativize(source).toString().replace('\\', '/');
                    assertThat(SINGLE_READS).as("new individual mapper caller %s: use the collection API", relative)
                        .containsKey(relative);
                }
                assertThat(text).as("per-row chart mapping in %s", source)
                    .doesNotContain(".map(patient -> buildPatientDto(", ".map(p -> buildPatientDto(",
                        ".map(this::enrichRegistration)");
            }
        }
    }
}
