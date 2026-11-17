package com.example.hms.controller.pharmacy;

import com.example.hms.BaseIT;
import com.example.hms.security.CustomUserDetails;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * QA D538, through the REAL filter chain: a role the checkout refuses gets
 * 403 at the edge, before the body is validated. {@code @PreAuthorize} runs
 * after argument resolution, so without the edge matcher a nurse posting an
 * invalid body saw the 400 field errors first. The pharmacist control shows
 * the same body does reach validation for an admitted role, so the nurse's
 * 403 comes from the edge and not from some other filter.
 */
@AutoConfigureMockMvc
@DisplayName("POST /pharmacy/payments edge authorization (D538)")
class PharmacyPaymentEdgeAuthorizationIT extends BaseIT {

    private static final String API = "/api";
    private static final String INVALID_BODY = "{}";

    @Autowired private MockMvc mockMvc;

    private static RequestPostProcessor signedInAs(String role) {
        CustomUserDetails details = new CustomUserDetails(UUID.randomUUID(), "edge-" + role.toLowerCase(), "n/a", true,
            List.of(new SimpleGrantedAuthority(role)));
        return authentication(new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
    }

    private MvcResult postInvalid(String role) throws Exception {
        return mockMvc.perform(post(API + "/pharmacy/payments").contextPath(API)
                .contentType(MediaType.APPLICATION_JSON).content(INVALID_BODY)
                .with(signedInAs(role)).with(csrf()))
            .andReturn();
    }

    @Test
    void nurse_isRefusedBeforeTheBodyIsValidated() throws Exception {
        MvcResult nurse = postInvalid("ROLE_NURSE");

        assertThat(nurse.getResponse().getStatus()).isEqualTo(403);
        assertThat(nurse.getResponse().getContentAsString())
            .doesNotContainIgnoringCase("fieldErrors")
            .doesNotContainIgnoringCase("validation");
    }

    @Test
    void pharmacist_reachesBodyValidation() throws Exception {
        assertThat(postInvalid("ROLE_PHARMACIST").getResponse().getStatus()).isEqualTo(400);
    }
}
