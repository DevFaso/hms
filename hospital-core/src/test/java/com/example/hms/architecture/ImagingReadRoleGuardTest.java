package com.example.hms.architecture;

import com.example.hms.controller.DicomProxyController;
import com.example.hms.controller.ImagingOrderController;
import com.example.hms.controller.ImagingResultController;
import com.example.hms.security.ClinicalActor;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The imaging role matrix.
 *
 * <p>QA D1841: GET /imaging/orders refused RADIOLOGIST while every sibling read admitted him, so
 * the radiologist's dashboard and worklist answered 403. User decision 2026-11-14: the admin roles
 * (SUPER_ADMIN, HOSPITAL_ADMIN, ADMIN) READ Imagerie — orders and released reports, scoped by the
 * services — and never act on it; every imaging write goes through a clinical role the caller
 * holds on their own ({@link ClinicalActor#holdsOwnRole}), since a super-admin's token carries
 * ROLE_DOCTOR and ROLE_NURSE by inheritance.
 *
 * <ul>
 *   <li>Every imaging read admits RADIOLOGIST and the three admin roles.</li>
 *   <li>Every imaging write is a {@code ClinicalActor.holdsOwnRole} gate naming no admin role.</li>
 *   <li>The worklist order reads share one role set, and every role the portal /imaging route lets
 *       in can load it.</li>
 * </ul>
 * The released-only rule for admin report reads is behavioural: ImagingReportServiceImplTest.
 */
class ImagingReadRoleGuardTest {

    private static final List<Class<?>> IMAGING_CONTROLLERS =
        List.of(ImagingOrderController.class, ImagingResultController.class, DicomProxyController.class);

    private static final Set<String> ADMIN_ROLES = Set.of("SUPER_ADMIN", "HOSPITAL_ADMIN", "ADMIN");

    /** The read that belongs to ordering: gated like a write. */
    private static final String ORDERING_READ = "ImagingOrderController.getDuplicatePreview";

    /** Endpoints outside the matrix, each with its reason. A new entry needs one too. */
    private static final Map<String, String> OUTSIDE_THE_MATRIX = Map.of(
        "ImagingResultController.runCriticalEscalation",
        "an operator trigger of the scheduled escalation sweep, not a clinical act on a study",
        "DicomProxyController.listInstances",
        "image pixel data, not an order or a report: not part of the 2026-11-14 admin read scope");

    private static final Pattern QUOTED_ROLE = Pattern.compile("'(?:ROLE_)?([A-Z_]+)'");
    private static final String CLINICAL_GATE =
        "T(com.example.hms.security.ClinicalActor).holdsOwnRole(authentication,";
    private static final Path ROUTES = Path.of("../hospital-portal/src/app/app.routes.ts");

    @AfterEach
    void clear() {
        HospitalContextHolder.clear();
    }

    private static boolean isWrite(Method method) {
        return method.isAnnotationPresent(PostMapping.class) || method.isAnnotationPresent(PutMapping.class)
            || method.isAnnotationPresent(PatchMapping.class) || method.isAnnotationPresent(DeleteMapping.class);
    }

    @Test
    void everyImagingReadAdmitsTheRadiologistAndTheAdmins() {
        List<String> refused = new ArrayList<>();
        int reads = 0;
        for (Class<?> controller : IMAGING_CONTROLLERS) {
            for (Method method : controller.getDeclaredMethods()) {
                String name = controller.getSimpleName() + "." + method.getName();
                if (!method.isAnnotationPresent(GetMapping.class) || ORDERING_READ.equals(name)
                    || OUTSIDE_THE_MATRIX.containsKey(name)) {
                    continue;
                }
                reads++;
                Set<String> missing = new TreeSet<>(ADMIN_ROLES);
                missing.add("RADIOLOGIST");
                missing.removeAll(roles(controller, method));
                if (!missing.isEmpty()) {
                    refused.add(name + " refuses " + missing);
                }
            }
        }
        assertThat(reads).as("the imaging reads were found").isGreaterThan(5);
        assertThat(refused).as("an imaging read that refuses the radiologist or an admin role (QA D1841, user"
            + " decision 2026-11-14)" + SourceScan.MANDATE).isEmpty();
    }

    @Test
    void everyImagingWriteNeedsAClinicalRoleOfTheCallersOwn() {
        List<String> offenders = new ArrayList<>();
        int writes = 0;
        for (Class<?> controller : IMAGING_CONTROLLERS) {
            for (Method method : controller.getDeclaredMethods()) {
                String name = controller.getSimpleName() + "." + method.getName();
                if (!(isWrite(method) || ORDERING_READ.equals(name)) || OUTSIDE_THE_MATRIX.containsKey(name)) {
                    continue;
                }
                writes++;
                String gate = gate(controller, method);
                if (!gate.startsWith(CLINICAL_GATE)) {
                    offenders.add(name + " is not a ClinicalActor.holdsOwnRole gate: " + gate);
                }
                Set<String> roles = roles(controller, method);
                roles.retainAll(ADMIN_ROLES);
                if (!roles.isEmpty()) {
                    offenders.add(name + " names the admin role(s) " + roles);
                }
            }
        }
        assertThat(writes).as("the imaging writes were found").isGreaterThanOrEqualTo(10);
        assertThat(offenders).as("an imaging write an administrator can reach (user decision 2026-11-14)"
            + SourceScan.MANDATE).isEmpty();
    }

    @Test
    void theWorklistOrderReadsAdmitTheSameRoles() throws NoSuchMethodException {
        Set<String> all = roles(ImagingOrderController.class, method(ImagingOrderController.class, "getAllOrders"));
        Set<String> byHospital = roles(ImagingOrderController.class,
            method(ImagingOrderController.class, "getOrdersByHospital"));
        Set<String> one = roles(ImagingOrderController.class, method(ImagingOrderController.class, "getOrder"));
        assertThat(all).as("GET /imaging/orders and /orders/hospital/{id} (QA D1841)" + SourceScan.MANDATE)
            .isEqualTo(byHospital);
        assertThat(one).as("GET /imaging/orders and /orders/{id} (QA D1841)" + SourceScan.MANDATE).isEqualTo(all);
    }

    @Test
    void everyRoleThePortalImagingRouteAdmitsCanLoadIt() throws IOException, NoSuchMethodException {
        String routes = Files.readString(ROUTES, StandardCharsets.UTF_8);
        int path = routes.indexOf("path: 'imaging',");
        assertThat(path).as("the /imaging route").isNotNegative();
        int open = routes.indexOf("roles: [", path);
        String block = routes.substring(open, routes.indexOf(']', open));
        Set<String> shownTo = new TreeSet<>();
        Matcher matcher = QUOTED_ROLE.matcher(block);
        while (matcher.find()) {
            shownTo.add(matcher.group(1));
        }
        assertThat(shownTo).as("the /imaging route's roles were read").contains("RADIOLOGIST", "HOSPITAL_ADMIN");
        Set<String> admitted = roles(ImagingOrderController.class, method(ImagingOrderController.class, "getAllOrders"));
        assertThat(admitted).as("a role the /imaging route admits but its load-time read GET /imaging/orders"
            + " refuses (QA D1841)" + SourceScan.MANDATE).containsAll(shownTo);
    }

    // ── ClinicalActor: what "a clinical role of their own" means ──

    private static Authentication token(String... authorities) {
        return new UsernamePasswordAuthenticationToken("u", "n/a",
            Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList());
    }

    /** The super-admin as the token carries them: SUPER_ADMIN plus every inherited role. */
    private static final String[] SUPER_ADMIN = {"ROLE_SUPER_ADMIN", "ROLE_HOSPITAL_ADMIN", "ROLE_RECEPTIONIST",
        "ROLE_DOCTOR", "ROLE_NURSE", "ROLE_LAB_SCIENTIST", "ROLE_STAFF", "ROLE_PATIENT"};

    @Test
    void aSuperAdminActsOnImagingOnlyThroughAClinicalRoleTheyHold() {
        HospitalContextHolder.setContext(HospitalContext.builder().assignedRoles(Set.of("ROLE_SUPER_ADMIN")).build());
        assertThat(ClinicalActor.holdsOwnRole(token(SUPER_ADMIN), "DOCTOR", "NURSE", "MIDWIFE", "RADIOLOGIST"))
            .as("the inherited DOCTOR and NURSE are not theirs").isFalse();

        HospitalContextHolder.setContext(HospitalContext.builder()
            .assignedRoles(Set.of("ROLE_SUPER_ADMIN", "ROLE_DOCTOR")).build());
        assertThat(ClinicalActor.holdsOwnRole(token(SUPER_ADMIN), "DOCTOR")).isTrue();
        assertThat(ClinicalActor.holdsOwnRole(token(SUPER_ADMIN), "NURSE")).as("only the role they hold").isFalse();

        String[] superAdminRadiologist = Arrays.copyOf(SUPER_ADMIN, SUPER_ADMIN.length + 1);
        superAdminRadiologist[SUPER_ADMIN.length] = "ROLE_RADIOLOGIST";
        HospitalContextHolder.clear();
        assertThat(ClinicalActor.holdsOwnRole(token(superAdminRadiologist), "RADIOLOGIST", "DOCTOR"))
            .as("a role outside the inheritance list is always their own").isTrue();
    }

    @Test
    void theAdminRolesNeverActAndTheClinicalRolesDo() {
        assertThat(ClinicalActor.holdsOwnRole(token("ROLE_HOSPITAL_ADMIN"), "DOCTOR", "RADIOLOGIST")).isFalse();
        assertThat(ClinicalActor.holdsOwnRole(token("ROLE_ADMIN"), "DOCTOR", "NURSE", "MIDWIFE")).isFalse();
        assertThat(ClinicalActor.holdsOwnRole(token("ROLE_RADIOLOGIST"), "RADIOLOGIST", "DOCTOR")).isTrue();
        assertThat(ClinicalActor.holdsOwnRole(token("ROLE_HOSPITAL_ADMIN", "ROLE_DOCTOR"), "DOCTOR")).isTrue();
        assertThat(ClinicalActor.holdsOwnRole(token("ROLE_MIDWIFE"), "DOCTOR", "RADIOLOGIST")).isFalse();
        assertThat(ClinicalActor.holdsOwnRole(null, "DOCTOR")).isFalse();
    }

    private static Method method(Class<?> controller, String name) throws NoSuchMethodException {
        for (Method method : controller.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                return method;
            }
        }
        throw new NoSuchMethodException(controller.getSimpleName() + "." + name);
    }

    private static String gate(Class<?> controller, Method method) {
        PreAuthorize gate = method.getAnnotation(PreAuthorize.class);
        if (gate == null) {
            gate = controller.getAnnotation(PreAuthorize.class);
        }
        assertThat(gate).as(controller.getSimpleName() + "." + method.getName() + " has a gate").isNotNull();
        return gate.value().trim();
    }

    /** The roles the method's gate names, or the class gate when the method has none. */
    private static Set<String> roles(Class<?> controller, Method method) {
        Set<String> roles = new TreeSet<>();
        Matcher matcher = QUOTED_ROLE.matcher(gate(controller, method));
        while (matcher.find()) {
            roles.add(matcher.group(1));
        }
        return roles;
    }
}
