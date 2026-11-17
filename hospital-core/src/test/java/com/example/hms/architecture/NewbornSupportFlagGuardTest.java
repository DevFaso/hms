package com.example.hms.architecture;

import com.example.hms.model.neonatal.NewbornAssessment;
import com.example.hms.service.maternity.NewbornAlertRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CR-06: a numeric alert recorded treatment as started, even when the clinician had submitted
 * false. Whatever a rule finds, it can recommend and request follow-up; the three initiated flags
 * hold only what the clinician submitted. Each way a rule output could reach a flag fails here.
 */
@DisplayName("CR-06: no rule output can record newborn support as started")
class NewbornSupportFlagGuardTest {

    private static final Path MAIN = Path.of("src/main/java");
    private static final List<String> FLAGS =
        List.of("respiratorySupportInitiated", "glucoseProtocolInitiated", "thermoregulationSupportInitiated");
    /** The one write: the builder, from the clinician's own value in the request. */
    private static final Pattern BUILD_CALL = Pattern.compile("\\.(" + String.join("|", FLAGS) + ")\\(([^;\\n]*)\\)");

    @Test
    @DisplayName("a finding carries what it recommends, and no field that could say support started")
    void aFindingHasNoFieldThatCouldRecordTreatment() {
        List<String> components = Arrays.stream(NewbornAlertRules.Finding.class.getRecordComponents())
            .map(RecordComponent::getName).toList();
        assertThat(components).containsExactly("type", "severity", "code", "followUp");
        assertThat(NewbornAlertRules.Finding.class.getRecordComponents())
            .noneMatch(c -> c.getType() == boolean.class || c.getType() == Boolean.class);
    }

    @Test
    @DisplayName("the flags have no setter: nothing can change them after the clinician's value is built in")
    void theFlagsHaveNoSetter() {
        assertThat(Arrays.stream(NewbornAssessment.class.getMethods()).map(java.lang.reflect.Method::getName))
            .doesNotContain("setRespiratorySupportInitiated", "setGlucoseProtocolInitiated",
                "setThermoregulationSupportInitiated");
    }

    @Test
    @DisplayName("across hospital-core, a flag is built only from the same flag of the request")
    void theFlagsAreBuiltOnlyFromTheRequest() throws IOException {
        List<String> offenders = new ArrayList<>();
        int writes = 0;
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                Matcher call = BUILD_CALL.matcher(source);
                while (call.find()) {
                    String flag = call.group(1);
                    String capitalised = Character.toUpperCase(flag.charAt(0)) + flag.substring(1);
                    boolean fromRequest = call.group(2).contains("request.get" + capitalised + "()");
                    boolean toResponse = file.endsWith("NewbornAssessmentMapper.java")
                        && call.group(2).equals("assessment.is" + capitalised + "()");
                    if (fromRequest) {
                        writes++;
                    } else if (!toResponse) {
                        offenders.add(MAIN.relativize(file) + ": " + call.group());
                    }
                }
            }
        }
        assertThat(offenders).isEmpty();
        assertThat(writes).as("the scan must still see the three writes").isEqualTo(FLAGS.size());
    }

    @Test
    @DisplayName("the rules' source never names a flag")
    void theRulesNeverNameAFlag() throws IOException {
        String rules = Files.readString(MAIN.resolve("com/example/hms/service/maternity/NewbornAlertRules.java"));
        assertThat(rules).doesNotContain("SupportInitiated", "ProtocolInitiated", "startsSupport");
    }
}
