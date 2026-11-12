package com.example.hms.architecture;

import com.example.hms.payload.dto.portal.PreCheckInRequestDTO;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1590 (round 44): the pre-check-in form sent the patient's insurer, member
 * number and plan, the server answered 201, and nothing read them: the coverage
 * was discarded and the desk still asked for it. Every field the patient fills
 * on that form is read by the service that takes it. A new field on
 * {@link PreCheckInRequestDTO} fails here until the service reads it.
 */
class PreCheckInFieldsReadGuardTest {

    private static final Path SERVICE =
        Path.of("src/main/java/com/example/hms/service/impl/PatientPortalServiceImpl.java");

    @Test
    void everyPreCheckInFieldIsReadByTheService() throws IOException {
        String source = Files.readString(SERVICE, StandardCharsets.UTF_8);
        List<String> unread = new ArrayList<>();
        for (Field field : PreCheckInRequestDTO.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            String name = field.getName();
            String getter = "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1) + "()";
            if (!source.contains(getter)) {
                unread.add(name);
            }
        }
        assertThat(unread)
            .as("pre-check-in fields the patient fills and the server never reads (QA D1590)")
            .isEmpty();
    }
}
