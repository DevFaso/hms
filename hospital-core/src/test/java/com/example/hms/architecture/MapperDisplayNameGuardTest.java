package com.example.hms.architecture;

import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1058 guard (NOT_FIXED in round 18): every option of the pharmacy
 * checkout picker read "Non renseigné". {@code DispenseResponseDTO} has a
 * {@code patientName} next to its {@code patientId}, but {@code DispenseMapper}
 * mapped only the id; one service method ({@code getDispense}) filled the name
 * afterwards, so the single read showed it and every list read (the picker)
 * did not. The class: a response mapper that sets {@code fooId} and leaves the
 * DTO's own {@code fooName} to some of its callers.
 *
 * <p>For every {@code …DTO.builder()} chain in {@code mapper/}, when the chain
 * sets {@code fooId(…)} and the DTO declares a {@code fooName} field of its
 * own (nested classes aside), the chain must set {@code fooName(…)} too.
 */
@DisplayName("A response mapper that names an id names its record too (QA D1058)")
class MapperDisplayNameGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");
    private static final Pattern BUILDER = Pattern.compile("\\b(\\w+DTO)\\.builder\\(\\)");
    private static final Pattern CALL = Pattern.compile("\\.(\\w+)\\(");
    /** A field declaration line: {@code private <type> <name> [= …];} (possessive: no backtracking). */
    private static final Pattern FIELD = Pattern.compile("^\\s*+private\\s++([\\w.<>, ?\\[\\]]++)(?:=[^;]*+)?+;");

    /** Known chains that leave a name unset, per {@code "file: DTO.field"}. Shrink only. */
    private static final Set<String> ALLOWED = Set.of();

    @Test
    @DisplayName("every mapper chain that sets fooId also sets the DTO's fooName")
    void mappersNameWhatTheyIdentify() throws IOException {
        Map<String, Set<String>> dtoFields = dtoFields();
        assertThat(dtoFields).as("the scan read the DTOs").containsKey("DispenseResponseDTO");
        Set<String> offenders = new TreeSet<>();
        try (Stream<Path> files = Files.walk(MAIN.resolve("mapper"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                offenders.addAll(offenders(file.getFileName().toString(), source, dtoFields));
            }
        }
        assertThat(offenders.stream().filter(o -> !ALLOWED.contains(o)).toList())
            .as("a mapper sets an id and leaves its name to the callers (QA D1058)" + SourceScan.MANDATE)
            .isEmpty();
        assertThat(ALLOWED.stream().filter(a -> !offenders.contains(a)).toList())
            .as("an allowed chain now names its record: delete its line").isEmpty();
    }

    @Test
    @DisplayName("the scan catches the D1058 shape")
    void catchesTheOldShape() {
        Map<String, Set<String>> fields = Map.of("DispenseResponseDTO", Set.of("patientId", "patientName", "id"));
        String before = "return DispenseResponseDTO.builder().id(e.getId()).patientId(p.getId()).build();";
        assertThat(offenders("DispenseMapper.java", before, fields))
            .containsExactly("DispenseMapper.java: DispenseResponseDTO.patientName");
        String after = before.replace(".patientId(p.getId())", ".patientId(p.getId()).patientName(p.getFullName())");
        assertThat(offenders("DispenseMapper.java", after, fields)).isEmpty();
    }

    static List<String> offenders(String file, String source, Map<String, Set<String>> dtoFields) {
        List<String> out = new ArrayList<>();
        Matcher builder = BUILDER.matcher(source);
        while (builder.find()) {
            Set<String> fields = dtoFields.get(builder.group(1));
            int end = source.indexOf(".build()", builder.end());
            if (fields == null || end < 0) {
                continue;
            }
            Set<String> called = new HashSet<>();
            Matcher call = CALL.matcher(source.substring(builder.end(), end));
            while (call.find()) {
                called.add(call.group(1));
            }
            for (String field : fields) {
                if (!field.endsWith("Name") || field.length() <= 4) {
                    continue;
                }
                String id = field.substring(0, field.length() - 4) + "Id";
                if (fields.contains(id) && called.contains(id) && !called.contains(field)) {
                    out.add(file + ": " + builder.group(1) + "." + field);
                }
            }
        }
        return out;
    }

    /** Each DTO's own fields (depth 1 of its top-level class; nested classes are skipped). */
    private static Map<String, Set<String>> dtoFields() throws IOException {
        Map<String, Set<String>> out = new HashMap<>();
        try (Stream<Path> files = Files.walk(MAIN.resolve("payload"))) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith("DTO.java")).toList()) {
                String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                Set<String> fields = new HashSet<>();
                int depth = 0;
                for (String line : source.split("\n")) {
                    if (depth == 1) {
                        Matcher m = FIELD.matcher(line);
                        if (m.find()) {
                            String declaration = m.group(1).strip();
                            int space = declaration.lastIndexOf(' ');
                            String name = declaration.substring(space + 1);
                            if (space > 0 && !name.isEmpty() && name.chars().allMatch(Character::isJavaIdentifierPart)) {
                                fields.add(name);
                            }
                        }
                    }
                    for (char c : line.toCharArray()) {
                        if (c == '{') {
                            depth++;
                        } else if (c == '}') {
                            depth--;
                        }
                    }
                }
                out.put(file.getFileName().toString().replace(".java", ""), fields);
            }
        }
        return out;
    }
}
