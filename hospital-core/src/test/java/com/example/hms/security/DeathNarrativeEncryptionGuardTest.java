package com.example.hms.security;

import com.example.hms.model.DeathDispute;
import com.example.hms.model.DeathRecord;
import com.example.hms.model.labor.FetalDeathEvent;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pre-production blocker (2026-11-15): the free text of the death family is PHI and is encrypted at rest.
 *
 * <p>Every entity of the death family (any {@code @Entity} under {@code model/} whose class name says death,
 * mortality, stillbirth or autopsy) is found by scanning the source, so a new one is covered the day it lands. On
 * each, a {@code String} field named like free text (reason, notes, narrative, cause, comment, description,
 * summary) must carry {@code @Convert(converter = EncryptedStringConverter.class)} on a {@code TEXT} column: the
 * ciphertext of a value at the input limit is longer than the limit. ICD codes ({@code ...Code}) and staff names
 * ({@code ...Name}) are not narrative and are left out by name.
 */
@DisplayName("The death family's free text is encrypted at rest")
class DeathNarrativeEncryptionGuardTest {

    private static final Path MODEL = Path.of("src/main/java/com/example/hms/model");
    private static final Pattern FAMILY = Pattern.compile("(?i).*(death|mortal|stillbirth|autops).*");
    private static final Pattern NARRATIVE =
        Pattern.compile("(?i).*(reason|notes?|narrative|causes?|comment|description|summary|explanation)$");

    private SecretKey originalKey;

    @BeforeEach
    void installKey() throws Exception {
        originalKey = EncryptionKeyHolder.getKeyForTesting();
        KeyGenerator gen = KeyGenerator.getInstance("AES");
        gen.init(256);
        EncryptionKeyHolder.setKeyForTesting(gen.generateKey());
    }

    @AfterEach
    void restoreKey() {
        EncryptionKeyHolder.setKeyForTesting(originalKey);
    }

    @Test
    @DisplayName("every narrative String field of the family carries the converter on a TEXT column")
    void everyNarrativeIsEncrypted() throws Exception {
        List<String> missing = new ArrayList<>();
        for (Field field : narrativeFields()) {
            String where = field.getDeclaringClass().getSimpleName() + "." + field.getName();
            Convert convert = field.getAnnotation(Convert.class);
            if (convert == null || convert.converter() != EncryptedStringConverter.class) {
                missing.add(where + " has no @Convert(converter = EncryptedStringConverter.class)");
            }
            Column column = field.getAnnotation(Column.class);
            if (column == null || !"TEXT".equalsIgnoreCase(column.columnDefinition())) {
                missing.add(where + " is not a TEXT column (its ciphertext outgrows the input limit)");
            }
        }
        assertThat(missing).as("PHI narrative stored in the clear").isEmpty();
    }

    @Test
    @DisplayName("the scan finds the whole family, so the guard above is never vacuous")
    void theScanFindsTheFamily() throws Exception {
        TreeSet<String> names = new TreeSet<>();
        for (Field field : narrativeFields()) {
            names.add(field.getDeclaringClass().getSimpleName() + "." + field.getName());
        }
        assertThat(familyEntities()).contains(DeathDispute.class, DeathRecord.class, FetalDeathEvent.class);
        assertThat(names).contains(
            "DeathDispute.reason", "DeathDispute.resolutionReason",
            "DeathRecord.immediateCause", "DeathRecord.underlyingCause", "DeathRecord.contributingCauses",
            "DeathRecord.amendmentReason", "DeathRecord.notes", "DeathRecord.enteredInErrorReason",
            "FetalDeathEvent.creationReason", "FetalDeathEvent.supersedeReason");
    }

    @Test
    @DisplayName("each mapped converter round-trips a value at its input limit, and the ciphertext outgrows that limit")
    void eachMappingRoundTrips() throws Exception {
        for (Field field : narrativeFields()) {
            String where = field.getDeclaringClass().getSimpleName() + "." + field.getName();
            Convert convert = field.getAnnotation(Convert.class);
            assertThat(convert).as(where).isNotNull();
            Size size = field.getAnnotation(Size.class);
            assertThat(size).as("%s keeps its input limit (@Size)", where).isNotNull();
            @SuppressWarnings("unchecked")
            jakarta.persistence.AttributeConverter<String, String> converter =
                (jakarta.persistence.AttributeConverter<String, String>) convert.converter()
                    .getDeclaredConstructor().newInstance();

            String atLimit = "é".repeat(size.max());
            String stored = converter.convertToDatabaseColumn(atLimit);
            assertThat(stored).as(where).startsWith("gcm1:").doesNotContain("é");
            assertThat(stored.length()).as("%s: the old VARCHAR(%d) could not hold it", where, size.max())
                .isGreaterThan(size.max());
            assertThat(converter.convertToEntityAttribute(stored)).as(where).isEqualTo(atLimit);
            assertThat(converter.convertToEntityAttribute("Arrêt cardio-respiratoire"))
                .as("%s: a legacy plaintext row still reads", where).isEqualTo("Arrêt cardio-respiratoire");
        }
    }

    private static List<Field> narrativeFields() throws Exception {
        List<Field> fields = new ArrayList<>();
        for (Class<?> entity : familyEntities()) {
            for (Field field : entity.getDeclaredFields()) {
                String name = field.getName();
                if (field.getType() == String.class && NARRATIVE.matcher(name).matches()
                    && !name.endsWith("Code") && !name.endsWith("Name")) {
                    fields.add(field);
                }
            }
        }
        return fields;
    }

    private static List<Class<?>> familyEntities() throws IOException, ClassNotFoundException {
        List<Class<?>> entities = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MODEL)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                String simple = file.getFileName().toString().replace(".java", "");
                if (!FAMILY.matcher(simple).matches()) {
                    continue;
                }
                String fqcn = "com.example.hms.model." + MODEL.relativize(file).toString()
                    .replace(".java", "").replace('\\', '.').replace('/', '.');
                Class<?> type = Class.forName(fqcn);
                if (type.isAnnotationPresent(Entity.class)) {
                    entities.add(type);
                }
            }
        }
        assertThat(entities).as("no death-family entity found under %s", MODEL.toString().toLowerCase(Locale.ROOT))
            .isNotEmpty();
        return entities;
    }
}
