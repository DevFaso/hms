package com.example.hms.security;

import com.example.hms.BaseIT;
import com.example.hms.service.SuperAdminPlatformRegistryService;
import com.example.hms.service.platform.PlatformRegistryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The platform registry's role gates through the REAL filter chain and
 * method security — the {@code addFilters = false} controller slices cannot
 * see a dropped or widened {@code @PreAuthorize}.
 *
 * <p>Every registry write, the cross-hospital link list and the whole
 * {@code /super-admin/platform/**} surface are SUPER_ADMIN only. Hospital
 * links used to admit HOSPITAL_ADMIN with no check that the hospital was
 * theirs.
 */
@AutoConfigureMockMvc
class PlatformRegistrySecurityIT extends BaseIT {

    private static final String API = "/api";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PlatformRegistryService platformRegistryService;

    @MockitoBean
    private SuperAdminPlatformRegistryService superAdminPlatformRegistryService;

    private final UUID orgId = UUID.randomUUID();
    private final UUID serviceId = UUID.randomUUID();
    private final UUID hospitalId = UUID.randomUUID();
    private final UUID departmentId = UUID.randomUUID();

    private static RequestPostProcessor signedInAs(String... roles) {
        CustomUserDetails details = new CustomUserDetails(UUID.randomUUID(), "user-" + roles[0].toLowerCase(), "n/a", true,
            java.util.Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList());
        return authentication(new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
    }

    /** Every endpoint a HOSPITAL_ADMIN must be refused. */
    private List<MockHttpServletRequestBuilder> superAdminOnly() {
        String body = "{\"serviceType\":\"EHR\"}";
        return List.of(
            post(API + "/platform/organizations/{o}/services", orgId).contentType(MediaType.APPLICATION_JSON).content(body),
            put(API + "/platform/organizations/{o}/services/{s}", orgId, serviceId).contentType(MediaType.APPLICATION_JSON).content("{}"),
            get(API + "/platform/organizations/{o}/services/{s}/hospital-links", orgId, serviceId),
            post(API + "/platform/hospitals/{h}/services/{s}", hospitalId, serviceId).contentType(MediaType.APPLICATION_JSON).content("{}"),
            put(API + "/platform/hospitals/{h}/services/{s}", hospitalId, serviceId).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"),
            delete(API + "/platform/hospitals/{h}/services/{s}", hospitalId, serviceId),
            post(API + "/platform/departments/{d}/services/{s}", departmentId, serviceId).contentType(MediaType.APPLICATION_JSON).content("{}"),
            delete(API + "/platform/departments/{d}/services/{s}", departmentId, serviceId),
            get(API + "/super-admin/platform/registry/summary"),
            get(API + "/super-admin/platform/registry/snapshot"),
            get(API + "/super-admin/platform/release-windows"),
            post(API + "/super-admin/platform/release-windows").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"w\",\"environment\":\"staging\",\"startsAt\":\"2099-01-01T10:00:00\",\"endsAt\":\"2099-01-01T12:00:00\"}"));
    }

    @Test
    @DisplayName("a HOSPITAL_ADMIN is refused every registry write and the whole super-admin platform surface")
    void hospitalAdminIsRefused() throws Exception {
        for (MockHttpServletRequestBuilder request : superAdminOnly()) {
            mockMvc.perform(request.contextPath(API).with(signedInAs("ROLE_HOSPITAL_ADMIN")).with(csrf()))
                .andExpect(status().isForbidden());
        }
        verifyNoInteractions(platformRegistryService, superAdminPlatformRegistryService);
    }

    @Test
    @DisplayName("a SUPER_ADMIN passes the same gates")
    void superAdminIsAdmitted() throws Exception {
        for (MockHttpServletRequestBuilder request : superAdminOnly()) {
            mockMvc.perform(request.contextPath(API).with(signedInAs("ROLE_SUPER_ADMIN")).with(csrf()))
                .andExpect(result -> {
                    int code = result.getResponse().getStatus();
                    if (code == 401 || code == 403) {
                        throw new AssertionError("SUPER_ADMIN refused with " + code + " on "
                            + result.getRequest().getMethod() + " " + result.getRequest().getRequestURI());
                    }
                });
        }
    }

    @Test
    @DisplayName("the reads a HOSPITAL_ADMIN keeps are still open to them")
    void hospitalAdminKeepsTheirReads() throws Exception {
        mockMvc.perform(get(API + "/platform/hospitals/{h}/services", hospitalId).contextPath(API)
                .with(signedInAs("ROLE_HOSPITAL_ADMIN")))
            .andExpect(status().isOk());
        mockMvc.perform(get(API + "/platform/catalog").contextPath(API)
                .with(signedInAs("ROLE_HOSPITAL_ADMIN")))
            .andExpect(status().isOk());
    }
}
