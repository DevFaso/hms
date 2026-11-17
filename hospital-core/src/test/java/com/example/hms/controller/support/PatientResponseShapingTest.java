package com.example.hms.controller.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.example.hms.payload.dto.ApiResponseWrapper;
import com.example.hms.payload.dto.AppointmentResponseDTO;
import com.example.hms.testsupport.ControllerMappingRegistry;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.MethodParameter;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** QA D1429: why the desk overbooked a visit reaches neither a patient nor a proxy, and staff keep it. */
class PatientResponseShapingTest {

    private static final String REASON = "Urgence signalée par le médecin";

    private final PatientResponseShaping shaping = new PatientResponseShaping();

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private static void signIn(String... roles) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("someone", "n/a",
            java.util.Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList()));
    }

    private static AppointmentResponseDTO appointment() {
        AppointmentResponseDTO dto = new AppointmentResponseDTO();
        dto.setOverbookReason(REASON);
        dto.setReason("Consultation");
        return dto;
    }

    @SuppressWarnings("unchecked")
    private void write(Object body, String path) {
        shaping.beforeBodyWrite(body, mock(MethodParameter.class), MediaType.APPLICATION_JSON,
            (Class<? extends HttpMessageConverter<?>>) (Class<?>) HttpMessageConverter.class,
            new ServletServerHttpRequest(new MockHttpServletRequest("GET", path)), mock(ServerHttpResponse.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/me/patient/appointments", "/me/patient/appointments",
        "/api/me/patient/proxy-access/patient-1/appointments"})
    @DisplayName("the portal and proxy answers lose it, whoever asks: a staff member can be a proxy")
    void thePortalPathsLoseIt(String path) {
        signIn("ROLE_DOCTOR");
        AppointmentResponseDTO one = appointment();
        write(ApiResponseWrapper.success(List.of(one)), path);
        assertThat(one.getOverbookReason()).isNull();
        assertThat(one.getReason()).as("the visit's own reason stays").isEqualTo("Consultation");
    }

    @Test
    @DisplayName("a patient on the /appointments reads loses it, in a list, a page or alone")
    void aPatientOnTheStaffPathsLosesIt() {
        signIn("ROLE_PATIENT");
        AppointmentResponseDTO alone = appointment();
        AppointmentResponseDTO listed = appointment();
        AppointmentResponseDTO paged = appointment();
        write(alone, "/api/appointments/1");
        write(List.of(listed), "/api/appointments/patients/1");
        write(new PageImpl<>(List.of(paged)), "/api/appointments/search");
        assertThat(List.of(alone, listed, paged)).allMatch(dto -> dto.getOverbookReason() == null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_RECEPTIONIST", "ROLE_DOCTOR", "ROLE_HOSPITAL_ADMIN", "ROLE_SUPER_ADMIN"})
    @DisplayName("staff keep it on the staff paths, a patient record of their own or not")
    void staffKeepIt(String role) {
        signIn(role, "ROLE_PATIENT");
        AppointmentResponseDTO one = appointment();
        write(one, "/api/appointments/1");
        assertThat(one.getOverbookReason()).isEqualTo(REASON);
    }

    /** The shaping walks wrappers, lists and pages only: a DTO that embeds an appointment would go out whole. */
    @Test
    @DisplayName("no response type nests an appointment where the shaping does not walk")
    void noResponseNestsAnAppointment() {
        Set<String> holders = new TreeSet<>();
        Set<Class<?>> seen = new HashSet<>();
        ControllerMappingRegistry.mapping().getHandlerMethods().forEach((info, handler) ->
            walk(handler.getMethod().getGenericReturnType(), seen, holders));
        assertThat(holders).as("teach PatientResponseShaping#shape to walk into the holder").isEmpty();
    }

    private static void walk(Type type, Set<Class<?>> seen, Set<String> holders) {
        if (type instanceof WildcardType wildcard) {
            for (Type bound : wildcard.getUpperBounds()) {
                walk(bound, seen, holders);
            }
        } else if (type instanceof ParameterizedType parameterized) {
            walk(parameterized.getRawType(), seen, holders);
            for (Type argument : parameterized.getActualTypeArguments()) {
                walk(argument, seen, holders);
            }
        } else if (type instanceof Class<?> raw && raw.getName().startsWith("com.example.hms.") && !raw.isEnum()
                && raw != AppointmentResponseDTO.class && seen.add(raw)) {
            for (Field field : raw.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                if (field.getGenericType().getTypeName().contains(AppointmentResponseDTO.class.getName())) {
                    holders.add(raw.getSimpleName() + "#" + field.getName());
                }
                walk(field.getGenericType(), seen, holders);
            }
        }
    }
}
