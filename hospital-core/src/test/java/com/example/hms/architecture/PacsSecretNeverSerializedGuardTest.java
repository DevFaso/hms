package com.example.hms.architecture;

import com.example.hms.model.Hospital;
import com.example.hms.security.EncryptedStringConverter;
import com.example.hms.testsupport.SourceScan;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.Convert;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A stored credential is write-only (V317, the PACS password).
 *
 * <ul>
 *   <li>An entity password column encrypted by {@link EncryptedStringConverter}
 *       is {@code @JsonIgnore}d, so no accidental entity serialisation (an
 *       event, a debug endpoint) ever carries it.</li>
 *   <li>No imaging DTO carries a password the server writes back: a password
 *       component exists only on a request, marked {@code WRITE_ONLY}.</li>
 * </ul>
 */
class PacsSecretNeverSerializedGuardTest {

    private static final Path MODEL = Path.of("src/main/java/com/example/hms/model");
    private static final Path IMAGING_DTO = Path.of("src/main/java/com/example/hms/payload/dto/imaging");

    @Test
    void encryptedPasswordColumnsAreJsonIgnored() throws IOException, ClassNotFoundException {
        List<String> offenders = new ArrayList<>();
        int found = 0;
        for (Class<?> type : classesUnder(MODEL, "com.example.hms.model")) {
            for (Field field : type.getDeclaredFields()) {
                Convert convert = field.getAnnotation(Convert.class);
                boolean encrypted = convert != null && convert.converter() == EncryptedStringConverter.class;
                if (encrypted && field.getName().toLowerCase(Locale.ROOT).contains("password")) {
                    found++;
                    if (!field.isAnnotationPresent(JsonIgnore.class)) {
                        offenders.add(type.getSimpleName() + "." + field.getName());
                    }
                }
            }
        }
        assertThat(found).as("the PACS password column was found").isGreaterThanOrEqualTo(1);
        assertThat(offenders).as("an encrypted password column an entity serialisation would carry (V317)"
            + SourceScan.MANDATE).isEmpty();
        assertThat(Hospital.class.getDeclaredFields()).anySatisfy(f -> assertThat(f.getName()).isEqualTo("pacsAuthPassword"));
    }

    @Test
    void noImagingDtoReturnsAPassword() throws IOException, ClassNotFoundException {
        List<String> offenders = new ArrayList<>();
        for (Class<?> type : classesUnder(IMAGING_DTO, "com.example.hms.payload.dto.imaging")) {
            List<String> names = new ArrayList<>();
            if (type.isRecord()) {
                for (RecordComponent component : type.getRecordComponents()) {
                    if (holdsAPassword(component.getName())
                        && !writeOnly(component.getAccessor().getDeclaringClass(), component.getName())) {
                        names.add(component.getName());
                    }
                }
            } else {
                for (Field field : type.getDeclaredFields()) {
                    if (holdsAPassword(field.getName()) && !writeOnly(type, field.getName())) {
                        names.add(field.getName());
                    }
                }
            }
            names.forEach(name -> offenders.add(type.getSimpleName() + "." + name));
        }
        assertThat(offenders).as("an imaging DTO that would return a password (V317)" + SourceScan.MANDATE).isEmpty();
    }

    /** "authPassword", "password": the value itself, not a flag about it ("passwordSet", "clearPassword"). */
    private static boolean holdsAPassword(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith("password") && !name.startsWith("clear");
    }

    private static boolean writeOnly(Class<?> type, String name) {
        try {
            JsonProperty property = type.getDeclaredField(name).getAnnotation(JsonProperty.class);
            return property != null && property.access() == JsonProperty.Access.WRITE_ONLY
                && type.getSimpleName().endsWith("RequestDTO");
        } catch (NoSuchFieldException ex) {
            return false;
        }
    }

    private static List<Class<?>> classesUnder(Path root, String rootPackage) throws IOException, ClassNotFoundException {
        List<Class<?>> out = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
        for (Path file : files) {
            String relative = root.relativize(file).toString().replace('\\', '/').replace('/', '.');
            String name = rootPackage + "." + relative.substring(0, relative.length() - ".java".length());
            if (name.endsWith("package-info")) {
                continue;
            }
            out.add(Class.forName(name, false, PacsSecretNeverSerializedGuardTest.class.getClassLoader()));
        }
        return out;
    }
}
