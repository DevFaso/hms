package com.example.hms.controller;

import com.example.hms.security.JwtTokenProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = JwksController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "com\\.example\\.hms\\.security\\..*"))
@AutoConfigureMockMvc(addFilters = false)
class JwksControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @Test
    @DisplayName("JWKS returns empty keys array when using HMAC")
    void jwks_hmac_returnsEmptyKeys() throws Exception {
        when(jwtTokenProvider.isAsymmetric()).thenReturn(false);

        mockMvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys", empty()));
    }

    @Test
    @DisplayName("JWKS returns current RSA key when asymmetric")
    void jwks_rsa_returnsCurrentKey() throws Exception {
        RSAPublicKey pubKey = generateRsaPublicKey();
        when(jwtTokenProvider.isAsymmetric()).thenReturn(true);
        when(jwtTokenProvider.getRsaPublicKey()).thenReturn(pubKey);

        mockMvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys", hasSize(1)))
                .andExpect(jsonPath("$.keys[0].kty", is("RSA")))
                .andExpect(jsonPath("$.keys[0].alg", is("RS256")))
                .andExpect(jsonPath("$.keys[0].kid", is("current")))
                .andExpect(jsonPath("$.keys[0].n").isString())
                .andExpect(jsonPath("$.keys[0].e").isString());
    }

    @Test
    @DisplayName("JWKS returns both current and previous keys during rotation")
    void jwks_rotation_returnsBothKeys() throws Exception {
        RSAPublicKey currentKey = generateRsaPublicKey();
        RSAPublicKey previousKey = generateRsaPublicKey();
        when(jwtTokenProvider.isAsymmetric()).thenReturn(true);
        when(jwtTokenProvider.getRsaPublicKey()).thenReturn(currentKey);
        when(jwtTokenProvider.getPreviousRsaPublicKey()).thenReturn(previousKey);

        mockMvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys", hasSize(2)))
                .andExpect(jsonPath("$.keys[0].kid", is("current")))
                .andExpect(jsonPath("$.keys[1].kid", is("previous")));
    }

    private RSAPublicKey generateRsaPublicKey() throws Exception {
        var gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        return (RSAPublicKey) gen.generateKeyPair().getPublic();
    }
}
