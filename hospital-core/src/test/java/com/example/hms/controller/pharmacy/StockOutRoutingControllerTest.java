package com.example.hms.controller.pharmacy;

import com.example.hms.payload.dto.pharmacy.PartnerNoShowRequestDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G3 round 4 — the in-house exit from a partner acceptance is an explicit
 * pharmacist act, so its gate is pinned: the annotation admits the two
 * pharmacist roles and nothing else, and no SecurityConfig matcher shadows
 * it (the chain is first-match-wins and terminal, so a matcher added later
 * without these roles would 403 the pharmacist before the annotation ran).
 */
class StockOutRoutingControllerTest {

    private static final Path SECURITY_CONFIG =
        Paths.get("src/main/java/com/example/hms/config/SecurityConfig.java");

    private static List<String> rolesOf(String methodName, Class<?>... paramTypes) throws Exception {
        Method method = StockOutRoutingController.class.getDeclaredMethod(methodName, paramTypes);
        PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);
        assertThat(annotation).as("@PreAuthorize on %s", methodName).isNotNull();

        Matcher m = Pattern.compile("'(?:ROLE_)?(\\w+)'").matcher(annotation.value());
        List<String> roles = new ArrayList<>();
        while (m.find()) {
            roles.add(m.group(1));
        }
        return roles;
    }

    @Test
    @DisplayName("partner-no-show is pharmacist-only")
    void partnerNoShowIsPharmacistOnly() throws Exception {
        assertThat(rolesOf("partnerNoShow", UUID.class, PartnerNoShowRequestDTO.class))
            .containsExactlyInAnyOrder("PHARMACIST", "PHARMACY_VERIFIER");
    }

    @Test
    @DisplayName("no SecurityConfig matcher covers /pharmacy/routing, so the annotations are the gate")
    void securityConfigDeclaresNoPharmacyRoutingMatcher() throws IOException {
        String source = Files.readString(SECURITY_CONFIG, StandardCharsets.UTF_8);
        assertThat(source)
            .as("a /pharmacy/routing matcher must admit every role the controller annotations admit")
            .doesNotContain("\"/pharmacy/routing");
    }
}
