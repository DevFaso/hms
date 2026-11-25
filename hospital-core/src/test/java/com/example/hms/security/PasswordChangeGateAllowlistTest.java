package com.example.hms.security;

import com.example.hms.testsupport.ControllerMappingRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every request {@link PasswordChangeGate} lets through is one the application really serves, read from Spring's
 * own mapping registry: a renamed endpoint (the change-password call above all) would otherwise leave an account on
 * a temporary password with no way to change it.
 */
class PasswordChangeGateAllowlistTest {

    @Test
    @DisplayName("every admitted request maps to a controller method")
    void everyAdmittedRequestMapsToAHandler() {
        Map<RequestMappingInfo, ?> handlers = ControllerMappingRegistry.mapping().getHandlerMethods();
        List<String> unmapped = new ArrayList<>();
        for (String admitted : PasswordChangeGate.admittedRequests()) {
            String[] parts = admitted.split(" ", 2);
            RequestMethod method = RequestMethod.valueOf(parts[0]);
            String path = parts[1];
            boolean mapped = handlers.keySet().stream().anyMatch(info ->
                info.getPatternValues().contains(path)
                    && (info.getMethodsCondition().getMethods().isEmpty()
                        || info.getMethodsCondition().getMethods().contains(method)));
            if (!mapped) {
                unmapped.add(admitted);
            }
        }
        assertThat(unmapped).isEmpty();
    }

    @Test
    @DisplayName("the account-setup calls themselves are admitted")
    void theWayOutIsAdmitted() {
        assertThat(PasswordChangeGate.admittedRequests()).contains(
            "POST /auth/me/change-password", "POST /auth/me/complete-setup", "GET /auth/session/bootstrap");
        assertThat(Set.copyOf(PasswordChangeGate.admittedRequests())).hasSizeGreaterThan(3);
    }
}
