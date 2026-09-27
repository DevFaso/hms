package com.example.hms.integration;

import com.example.hms.BaseIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /auth/logout} through the REAL security chain.
 *
 * <p>It used to sit behind {@code .authenticated()} and CSRF, so the two ways a
 * patient app actually signs out never reached the controller: an idle client
 * whose access token had expired got a 401, and every native client (bearer +
 * JSON body, no XSRF header) got a 403 — so the refresh token it handed back
 * was never revoked. Revocation itself is covered in {@code AuthControllerTest};
 * this pins that the request gets there.
 */
@AutoConfigureMockMvc
class LogoutSecurityIT extends BaseIT {

    @Autowired private MockMvc mockMvc;

    @Test
    @DisplayName("a native client with no XSRF header and an expired bearer reaches logout (200)")
    void expiredBearerWithoutCsrfReachesLogout() throws Exception {
        mockMvc.perform(post("/auth/logout")
                        .header("Authorization", "Bearer not.a.valid.token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"not.a.valid.refresh\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a client with no bearer at all can still hand back its refresh token (200)")
    void noBearerReachesLogout() throws Exception {
        mockMvc.perform(post("/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"not.a.valid.refresh\"}"))
                .andExpect(status().isOk());
    }
}
