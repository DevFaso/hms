package com.example.hms.integration;

import com.example.hms.BaseIT;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class RoleControllerIntegrationTest extends BaseIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @WithMockUser(roles = "SUPER_ADMIN")
    void putRolePersistsDescription() throws Exception {
        String uniqueSuffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        String roleCode = "ROLE_HTTP_" + uniqueSuffix;
        String roleName = "HTTP_TEST_" + uniqueSuffix;

        Map<String, Object> createPayload = Map.of(
            "name", roleName,
            "code", roleCode,
            "description", "Initial description"
        );

        MvcResult createResult = mockMvc.perform(post("/roles")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(createPayload)))
            .andExpect(status().isCreated())
            .andReturn();

        Map<?, ?> created = objectMapper.readValue(createResult.getResponse().getContentAsByteArray(), Map.class);
        UUID id = UUID.fromString(created.get("id").toString());

        Map<String, Object> updatePayload = Map.of(
            "name", roleName,
            "code", roleCode,
            "description", "Updated via HTTP"
        );

        mockMvc.perform(put("/roles/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(updatePayload)))
            .andExpect(status().isOk());

        MvcResult getResult = mockMvc.perform(get("/roles/" + id))
            .andExpect(status().isOk())
            .andReturn();

        Map<String, Object> reloaded = objectMapper.readValue(
            getResult.getResponse().getContentAsByteArray(),
            new TypeReference<>() {}
        );
        assertThat(reloaded).containsEntry("description", "Updated via HTTP");
    }
}
