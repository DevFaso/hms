package com.example.hms.architecture;

import com.example.hms.controller.EncounterController;
import com.example.hms.controller.ReceptionController;
import com.example.hms.controller.support.ReceptionistResponseShaping;
import com.example.hms.payload.dto.FlowBoardDTO;
import com.example.hms.payload.dto.ReceptionQueueItemDTO;
import com.example.hms.testsupport.ControllerMappingRegistry;
import com.example.hms.testsupport.RoleExpression;
import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.MediaType;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * QA r81 D4265: a front-desk view shows a visit's reason to a caller who does not read visits
 * (the hospital admin, billing, accounts) exactly as it shows it to the receptionist: what was
 * booked or typed at the desk, never the complaint a clinician recorded.
 *
 * <p>Three rules, over every handler of {@link ReceptionController}:
 * <ol>
 *   <li>{@link ReceptionistResponseShaping#VISIT_READERS} are the roles the visit list admits, and
 *       every other role a front-desk view admits is refused there;</li>
 *   <li>every role a front-desk view admits outside the visit readers receives the queue row and
 *       the flow board with the desk's reason, and a visit reader receives the visit's;</li>
 *   <li>a front-desk answer has no reason or complaint field that is neither shaped
 *       ({@link ReceptionistResponseShaping#DESK_VIEW_VISIT_REASON}) nor recorded in
 *       {@link #NOT_A_VISIT_REASON} with what it is.</li>
 * </ol>
 */
@DisplayName("r81 D4265: a front-desk view shows the desk's visit reason to every role that reads no visit")
class DeskViewVisitReasonGuardTest {

    private static final Pattern ROLE = Pattern.compile("ROLE_[A-Z_]+");
    private static final Pattern REASON_FIELD = Pattern.compile("(?i).*(reason|complaint).*");

    /** {@code Type#field} on a front-desk answer that reads like a visit reason and is not one. */
    private static final Map<String, String> NOT_A_VISIT_REASON = Map.of(
        "CheckInResponseDTO#chiefComplaint", "the answer to the caller's own check-in: what was just typed at the desk",
        "WaitlistEntryResponseDTO#reason", "the reason the desk typed when it put the patient on the waitlist",
        "WaitlistEntryResponseDTO#closeReason", "why the desk closed the waitlist entry");

    private final ReceptionistResponseShaping advice = new ReceptionistResponseShaping();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("the visit readers are the roles the visit list admits; no other front-desk role is one")
    void visitReadersAreTheRolesTheVisitListAdmits() {
        HandlerMethod visitList = handlers(EncounterController.class).stream()
            .filter(handler -> handler.getMethod().getName().equals("list")).findFirst().orElseThrow();
        for (String reader : ReceptionistResponseShaping.VISIT_READERS) {
            assertThat(RoleExpression.admits(visitList, reader)).as("%s reads the visit list", reader)
                .isEqualTo(RoleExpression.YES);
        }
        Set<String> others = frontDeskRolesOutsideTheVisitReaders();
        assertThat(others).contains("ROLE_HOSPITAL_ADMIN", "ROLE_RECEPTIONIST");
        for (String other : others) {
            assertThat(RoleExpression.admits(visitList, other)).as("%s does not read the visit list", other)
                .isEqualTo(RoleExpression.NO);
        }
    }

    @Test
    @DisplayName("every front-desk role that reads no visit receives the desk's reason, on the queue and the board")
    void everyRoleThatReadsNoVisitReceivesTheDesksReason() {
        for (String role : frontDeskRolesOutsideTheVisitReaders()) {
            signedInAs(role);
            assertThat(reasonsOf(write(List.of(row("Fièvre depuis trois jours", "Consultation"), row("Toux", null)))))
                .as("%s, the queue", role).containsExactly("Consultation", null);
            FlowBoardDTO board = FlowBoardDTO.builder()
                .arrived(List.of(row("Douleur thoracique", null)))
                .walkIn(List.of(row("Suivi de traitement", "Contrôle")))
                .build();
            write(board);
            assertThat(board.getArrived().get(0).getAppointmentReason()).as("%s, the board", role).isNull();
            assertThat(board.getWalkIn().get(0).getAppointmentReason()).as("%s, the board", role).isEqualTo("Contrôle");
        }
    }

    @Test
    @DisplayName("a visit reader receives the visit's own reason")
    void aVisitReaderReceivesTheVisitsReason() {
        for (String reader : ReceptionistResponseShaping.VISIT_READERS) {
            signedInAs(reader);
            assertThat(reasonsOf(write(List.of(row("Fièvre depuis trois jours", "Consultation")))))
                .as(reader).containsExactly("Fièvre depuis trois jours");
        }
    }

    @Test
    @DisplayName("no front-desk answer carries a reason or a complaint that is neither shaped nor recorded")
    void everyReasonOnAFrontDeskAnswerIsShapedOrRecorded() {
        Set<String> found = new TreeSet<>();
        Set<Class<?>> seen = new HashSet<>();
        for (HandlerMethod handler : handlers(ReceptionController.class)) {
            collect(ResolvableType.forMethodReturnType(handler.getMethod()), found, seen, 0);
        }
        Set<String> shaped = new TreeSet<>();
        ReceptionistResponseShaping.DESK_VIEW_VISIT_REASON
            .forEach((type, field) -> shaped.add(type.getSimpleName() + "#" + field));
        assertThat(found).as("the scan found the shaped visit reason").containsAll(shaped);
        found.removeAll(shaped);
        assertThat(found)
            .as("reason or complaint fields on a front-desk answer: shape each for the roles that read no visit"
                + " (ReceptionistResponseShaping.DESK_VIEW_VISIT_REASON) or record what it is in NOT_A_VISIT_REASON"
                + SourceScan.MANDATE)
            .containsExactlyInAnyOrderElementsOf(NOT_A_VISIT_REASON.keySet());
    }

    /* --------------------------------------------------------------------- */

    private static void signedInAs(String... roles) {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("u", "p", roles));
    }

    private Object write(Object body) {
        return advice.beforeBodyWrite(body, mock(MethodParameter.class), MediaType.APPLICATION_JSON,
            StringHttpMessageConverter.class,
            new ServletServerHttpRequest(new MockHttpServletRequest("GET", "/reception/queue")),
            new ServletServerHttpResponse(new MockHttpServletResponse()));
    }

    private static ReceptionQueueItemDTO row(String visitReason, String deskReason) {
        return ReceptionQueueItemDTO.builder().appointmentReason(visitReason).deskAppointmentReason(deskReason).build();
    }

    private static List<String> reasonsOf(Object rows) {
        List<String> reasons = new java.util.ArrayList<>();
        for (Object row : (List<?>) rows) {
            reasons.add(((ReceptionQueueItemDTO) row).getAppointmentReason());
        }
        return reasons;
    }

    private static List<HandlerMethod> handlers(Class<?> controller) {
        return ControllerMappingRegistry.mapping().getHandlerMethods().values().stream()
            .filter(handler -> handler.getBeanType() == controller).toList();
    }

    /** Every role a front-desk handler names in its access rule, less the visit readers. */
    private static Set<String> frontDeskRolesOutsideTheVisitReaders() {
        Set<String> roles = new TreeSet<>();
        for (HandlerMethod handler : handlers(ReceptionController.class)) {
            PreAuthorize rule = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), PreAuthorize.class);
            if (rule == null) {
                continue;
            }
            Matcher matcher = ROLE.matcher(rule.value());
            while (matcher.find()) {
                roles.add(matcher.group());
            }
        }
        roles.removeAll(ReceptionistResponseShaping.VISIT_READERS);
        return roles;
    }

    private static void collect(ResolvableType type, Set<String> found, Set<Class<?>> seen, int depth) {
        Class<?> raw = type.toClass();
        for (ResolvableType generic : type.getGenerics()) {
            collect(generic, found, seen, depth);
        }
        if (depth > 4 || !raw.getName().startsWith("com.example.hms.payload") || raw.isEnum() || !seen.add(raw)) {
            return;
        }
        for (Class<?> current = raw; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()
                    || field.isAnnotationPresent(com.fasterxml.jackson.annotation.JsonIgnore.class)) {
                    continue;
                }
                if (field.getType() == String.class && REASON_FIELD.matcher(field.getName()).matches()) {
                    found.add(raw.getSimpleName() + "#" + field.getName());
                }
                collect(ResolvableType.forField(field), found, seen, depth + 1);
            }
        }
    }
}
