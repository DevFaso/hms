package com.example.hms.validation;

import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1211 (round 23): "12ab", "xyz", "appeler le voisin" and "pas de tel"
 * were saved as a patient's phone, a registration contact's and a
 * guarantor's, because only the account's main phone was checked (D1170).
 *
 * <p>Every class of every request payload ({@code com.example.hms.payload},
 * nested classes included) is read by reflection: a {@code String} field whose
 * name carries "phone" must carry {@link PhoneNumber}, unless its class only
 * carries data OUT (a response, a summary, a search filter), which the class
 * name says or {@link #READ_ONLY} lists with the reason. A new write DTO with
 * an unchecked phone field fails here.
 */
class PhoneFieldValidationGuardTest {

    private static final Path PAYLOAD = Path.of("src/main/java/com/example/hms/payload");

    /** Classes that carry data out, by the naming convention of the payload package. */
    private static final Pattern READ_ONLY_NAME = Pattern.compile(
        ".*(Response|Summary|Minimal|Lookup|Filter|Criteria|Snapshot|Match|Candidate|Jwt|Option"
            + "|DirectoryEntry|WithStaff|WithDepartments|Hierarchy)(DTO|Dto)?$");

    /** Classes a name cannot place, each with why its phone field is not a write. */
    private static final Map<String, String> READ_ONLY = Map.of(
        "PatientRecordDTO", "the chart record the server returns",
        "PatientProfileDTO", "the portal profile the server returns (writes go through PatientProfileUpdateDTO)",
        "ProviderProfileDTO", "the provider profile the server returns (writes go through ProviderProfileUpdateDTO)",
        "PasswordResetRequestDTO", "a lookup key: an unknown value finds nobody and the answer must not differ"
            + " (account enumeration)",
        "ProgramEnrollmentResponseDTO", "a response");

    @Test
    void everyPhoneFieldARequestCanWriteIsValidated() throws IOException, ClassNotFoundException {
        Set<String> unchecked = new TreeSet<>();
        Set<String> staleAllowlist = new TreeSet<>(READ_ONLY.keySet());
        int checked = 0;
        for (Class<?> type : payloadClasses()) {
            for (Field field : type.getDeclaredFields()) {
                if (field.getType() != String.class
                        || !field.getName().toLowerCase(Locale.ROOT).contains("phone")) {
                    continue;
                }
                String simple = type.getSimpleName();
                if (READ_ONLY.containsKey(simple)) {
                    staleAllowlist.remove(simple);
                    continue;
                }
                if (READ_ONLY_NAME.matcher(simple).matches()) {
                    continue;
                }
                checked++;
                if (!field.isAnnotationPresent(PhoneNumber.class)) {
                    unchecked.add(type.getName() + "." + field.getName());
                }
            }
        }
        assertThat(checked).as("the scan reached the request payloads").isGreaterThan(20);
        assertThat(unchecked)
            .as("a phone field a request writes without @PhoneNumber (QA D1211): annotate it, or list its class"
                + " in READ_ONLY with the reason it is not a write%s", SourceScan.MANDATE)
            .isEmpty();
        assertThat(staleAllowlist).as("a READ_ONLY entry no longer has a phone field: delete it").isEmpty();
    }

    @Test
    void theRuleRefusesTheQaValuesAndKeepsRealNumbers() {
        for (String bad : List.of("12ab", "xyz", "appeler le voisin", "pas de tel", "voisin", "12")) {
            assertThat(PhoneNumberValidator.isPhoneNumber(bad)).as(bad).isFalse();
        }
        for (String good : List.of("70202020", "70 20 20 20", "+226 70 20 20 20", "0022670202020",
                "+225 07 07 07 07 07", "0707070707", "+33 6 12 34 56 78")) {
            assertThat(PhoneNumberValidator.isPhoneNumber(good)).as(good).isTrue();
        }
        assertThat(PhoneNumberValidator.isPhoneNumber(null)).isTrue();
        assertThat(PhoneNumberValidator.isPhoneNumber("  ")).isTrue();
    }

    private static List<Class<?>> payloadClasses() throws IOException, ClassNotFoundException {
        List<Class<?>> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(PAYLOAD)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String rel = Path.of("src/main/java").relativize(file).toString().replace('\\', '/');
                String fqcn = rel.substring(0, rel.length() - ".java".length()).replace('/', '.');
                Class<?> top = Class.forName(fqcn, false, PhoneFieldValidationGuardTest.class.getClassLoader());
                collect(top, out);
            }
        }
        return out;
    }

    private static void collect(Class<?> type, List<Class<?>> out) {
        out.add(type);
        for (Class<?> nested : type.getDeclaredClasses()) {
            collect(nested, out);
        }
    }
}
