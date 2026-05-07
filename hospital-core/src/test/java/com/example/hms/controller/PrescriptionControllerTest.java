package com.example.hms.controller;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class PrescriptionControllerTest {

    private static List<String> extractRolesFromMethod(String methodName, Class<?>... paramTypes) throws Exception {
        Method method = PrescriptionController.class.getDeclaredMethod(methodName, paramTypes);
        PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);
        assertThat(annotation).as("@PreAuthorize on %s", methodName).isNotNull();

        Pattern p = Pattern.compile("'(?:ROLE_)?(\\w+)'");
        Matcher m = p.matcher(annotation.value());
        List<String> roles = new ArrayList<>();
        while (m.find()) {
            roles.add(m.group(1));
        }
        return roles;
    }

    @Test
    void list_preAuthorize_includesSuperAdmin() throws Exception {
        List<String> roles = extractRolesFromMethod("list",
                UUID.class, UUID.class, UUID.class, Pageable.class, Locale.class);
        assertThat(roles).contains("SUPER_ADMIN");
    }

    @Test
    void list_preAuthorize_retainsTenantRoles() throws Exception {
        List<String> roles = extractRolesFromMethod("list",
                UUID.class, UUID.class, UUID.class, Pageable.class, Locale.class);
        assertThat(roles).contains("DOCTOR", "NURSE", "MIDWIFE", "PHARMACIST", "HOSPITAL_ADMIN");
    }
}
