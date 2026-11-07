package com.example.hms.architecture;

import com.example.hms.model.postpartum.PostpartumObservation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CR-06 class: a rule can recommend treatment and ask for follow-up, but only a clinician records that
 * it happened. {@code hemorrhageProtocolActivated} holds what was submitted; the HEMORRHAGE alert says
 * that indicators are present and, with the flag, keeps the 15-minute checks running.
 */
@DisplayName("CR-06 class: an alert never records treatment as performed")
class AlertNeverRecordsTreatmentGuardTest {

    private static final Path MAIN = Path.of("src/main/java");
    private static final Path HMS = MAIN.resolve("com/example/hms");
    private static final String FLAG = "hemorrhageProtocolActivated";
    private static final String CAPITALISED_FLAG = "HemorrhageProtocolActivated";
    private static final Pattern FLAG_BUILD = Pattern.compile("\\." + FLAG + "\\(([^;\\n]*)\\)");

    private static final String TREATMENT_NAME =
        "\\w{0,80}(?:Initiated|Activated|Started|Given|Performed|Administered|Treated|Transfused)";

    /** A flag built or set to a constant true says something happened that nobody submitted. */
    private static final Pattern FORCED_TRUE = Pattern.compile(
        "\\.(?:set[A-Z]|[a-z])" + TREATMENT_NAME + "\\s*\\(\\s*(?:true|Boolean\\.TRUE)\\s*\\)");

    /** A flag set from anything but a submitted value: a computed indicator is the same defect as a constant. */
    private static final Pattern SET_FROM = Pattern.compile("\\.set[A-Z]" + TREATMENT_NAME + "\\s*\\(([^;\\n]*)\\)");

    /** Removed by CR-06 itself on fix/cr-newborn: delete these entries when that branch is merged. */
    private static final Set<String> FILED = Set.of(
        ".setThermoregulationSupportInitiated(true)",
        ".setRespiratorySupportInitiated(true)",
        ".setGlucoseProtocolInitiated(true)");

    private static final List<String> CLINICAL_CODE =
        List.of("service", "cdshooks", "hl7", "fhir", "mapper", "model", "controller");

    @Test
    @DisplayName("the flag has no setter: nothing can change it after the clinician's value is built in")
    void theFlagHasNoSetter() {
        assertThat(Arrays.stream(PostpartumObservation.class.getMethods()).map(Method::getName))
            .contains("is" + CAPITALISED_FLAG)
            .doesNotContain("set" + CAPITALISED_FLAG);
    }

    @Test
    @DisplayName("across hospital-core, the flag is built only from the same flag of the request")
    void theFlagIsBuiltOnlyFromTheRequest() throws IOException {
        List<String> offenders = new ArrayList<>();
        int writes = 0;
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher build = FLAG_BUILD.matcher(Files.readString(file));
                while (build.find()) {
                    String value = build.group(1);
                    boolean toResponse = file.endsWith("PostpartumObservationMapper.java")
                        && value.equals("observation.is" + CAPITALISED_FLAG + "()");
                    if (value.equals("Boolean.TRUE.equals(request.get" + CAPITALISED_FLAG + "())")) {
                        writes++;
                    } else if (!toResponse) {
                        offenders.add(MAIN.relativize(file) + ": " + build.group());
                    }
                }
            }
        }
        assertThat(offenders).isEmpty();
        assertThat(writes).as("the scan must still see the one write").isEqualTo(1);
    }

    @Test
    @DisplayName("the schedule reads the indicators and the attestation, on this row and on the plan")
    void theScheduleReadsBothMeanings() throws IOException {
        String service = Files.readString(HMS.resolve("service/impl/PostpartumCareServiceImpl.java"));
        assertThat(service).contains(
            "evaluation.hemorrhageDetected()",
            "observation.is" + CAPITALISED_FLAG + "()",
            "observationRepository.existsHaemorrhageOnPlan(");
        String repository = Files.readString(HMS.resolve("repository/PostpartumObservationRepository.java"));
        assertThat(repository).contains("o." + FLAG + " = true", "PostpartumAlertType.HEMORRHAGE");
    }

    @Test
    @DisplayName("across clinical code, no initiated, activated, started, given or performed flag is forced or computed")
    void noTreatmentFlagIsForcedToTrue() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (String area : CLINICAL_CODE) {
            try (Stream<Path> files = Files.walk(HMS.resolve(area))) {
                for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String source = Files.readString(file);
                    Matcher forced = FORCED_TRUE.matcher(source);
                    while (forced.find()) {
                        if (!FILED.contains(forced.group())) {
                            offenders.add(MAIN.relativize(file) + ": " + forced.group());
                        }
                    }
                    Matcher set = SET_FROM.matcher(source);
                    while (set.find()) {
                        boolean constant = FORCED_TRUE.matcher(set.group()).matches();
                        boolean clears = set.group(1).matches("\\s*(?:false|Boolean\\.FALSE|null)\\s*");
                        boolean submitted = set.group(1).contains("request.get") || set.group(1).contains("dto.get");
                        if (!constant && !clears && !submitted) {
                            offenders.add(MAIN.relativize(file) + ": " + set.group());
                        }
                    }
                }
            }
        }
        assertThat(offenders).isEmpty();
    }
}
