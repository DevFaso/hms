package com.example.hms.architecture;

import com.example.hms.controller.DicomProxyController;
import com.example.hms.controller.ImagingOrderController;
import com.example.hms.controller.ImagingReadingContextController;
import com.example.hms.controller.ImagingResultController;
import com.example.hms.controller.ImagingStudyImageController;
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
 * (SUPER_ADMIN, HOSPITAL_ADMIN) READ Imagerie — orders and released reports, scoped by the
 * services — and never act on it. ADMIN was the third until the platform admin rules of 2026-11-20
 * (rule 1b): it is the organisation's administrator and reads no patient or clinical data
 * (OrganisationAdminNoPatientDataGuardTest); every imaging write goes through a clinical role the caller
 * holds on their own ({@link ClinicalActor#holdsOwnRole}), since a super-admin's token carries
 * ROLE_DOCTOR and ROLE_NURSE by inheritance.
 *
 * <ul>
 *   <li>Every imaging read admits RADIOLOGIST and the two admin roles, and never ADMIN.</li>
 *   <li>Every imaging write is a {@code ClinicalActor.holdsOwnRole} gate naming no admin role.</li>
 *   <li>The worklist order reads share one role set, and every role the portal /imaging route lets
 *       in can load it.</li>
 * </ul>
 * The released-only rule for admin report reads is behavioural: ImagingReportServiceImplTest.
 */
class ImagingReadRoleGuardTest {

    private static final List<Class<?>> IMAGING_CONTROLLERS =
        List.of(ImagingOrderController.class, ImagingResultController.class, DicomProxyController.class,
            ImagingStudyImageController.class, ImagingReadingContextController.class);

    private static final Set<String> ADMIN_ROLES = Set.of("SUPER_ADMIN", "HOSPITAL_ADMIN");

    /** The read that belongs to ordering: gated like a write. */
    private static final String ORDERING_READ = "ImagingOrderController.getDuplicatePreview";

    /** Endpoints outside the matrix, each with its reason. A new entry needs one too. */
    private static final Map<String, String> OUTSIDE_THE_MATRIX = Map.of(
        "ImagingResultController.runCriticalEscalation",
        "an operator trigger of the scheduled escalation sweep, not a clinical act on a study",
        "DicomProxyController.listInstances",
        "image pixel data, not an order or a report: not part of the 2026-11-14 admin read scope",
        "DicomProxyController.viewer",
        "image pixel data (the viewer manifest): a clinical role of the caller's own, see the pixel test",
        "DicomProxyController.renderedFrame",
        "image pixel data (a rendered frame): a clinical role of the caller's own, see the pixel test",
        "DicomProxyController.viewerReady",
        "r64 D2467: whether the pixel viewer can be offered, gated exactly as the viewer it announces",
        "ImagingResultController.getReportsToSign",
        "r63 D2306: the reads waiting for the caller's own signature; only an author signs, so only a role that"
            + " authors reports (createReport's) has a list",
        "ImagingReadingContextController.getReadingContext",
        "r63: the patient's creatinine and maternal history beside the order, clinical data outside the"
            + " 2026-11-14 admin read scope (orders and released reports)");

    private static final Pattern QUOTED_ROLE = Pattern.compile("'(?:ROLE_)?([A-Z_]+)'");
    private static final String CLINICAL_GATE =
        "T(com.example.hms.security.ClinicalActor).holdsOwnRole(authentication,";
    private static final Path ROUTES = Path.of("../hospital-portal/src/app/app.routes.ts");

    @AfterEach
    void clear() {
        HospitalContextHolder.clear();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
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
                // Platform admin rules 2026-11-20, rule 1b: never the organisation's administrator.
                if (roles(controller, method).contains("ADMIN")) {
                    refused.add(name + " admits ADMIN, the organisation admin, who reads no clinical data");
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

    /**
     * The in-app viewer (V317): pixels are clinical content, so each viewer
     * endpoint is a ClinicalActor gate over the four clinical imaging roles and
     * names no admin role, which the super-admin's inherited DOCTOR would pass.
     */
    @Test
    void theViewerReadsPixelsThroughAClinicalRoleOfTheCallersOwn() {
        List<String> offenders = new ArrayList<>();
        int viewerEndpoints = 0;
        for (Method method : DicomProxyController.class.getDeclaredMethods()) {
            GetMapping get = method.getAnnotation(GetMapping.class);
            if (get == null || Arrays.stream(get.value()).noneMatch(path -> path.startsWith("/reports/"))) {
                continue;
            }
            viewerEndpoints++;
            String gate = gate(DicomProxyController.class, method);
            if (!gate.startsWith(CLINICAL_GATE)) {
                offenders.add(method.getName() + " is not a ClinicalActor.holdsOwnRole gate: " + gate);
            }
            Set<String> roles = roles(DicomProxyController.class, method);
            if (!roles.equals(Set.of("DOCTOR", "NURSE", "MIDWIFE", "RADIOLOGIST"))) {
                offenders.add(method.getName() + " admits " + roles);
            }
        }
        assertThat(viewerEndpoints).as("the viewer endpoints were found").isGreaterThanOrEqualTo(2);
        assertThat(offenders).as("an image-viewer endpoint an administrator alone can reach (user decision"
            + " 2026-11-14)" + SourceScan.MANDATE).isEmpty();
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

    // ── Policy rule 3 (2026-11-20, provisional): the imaging technician's matrix ──

    private static final String TECHNICIAN = "IMAGING_TECHNICIAN";

    /**
     * Every endpoint of the platform that admits the imaging technician. They acquire, document
     * and complete an exam and read the released reports of exams they performed (the service
     * narrows the reads). Nothing else: no order, no report write, no signature, no withdrawal
     * after signing, no chart.
     */
    private static final Set<String> TECHNICIAN_ENDPOINTS = Set.of(
        // The worklist of their hospital, with the indication and the safety flags on the order.
        "ImagingOrderController.getAllOrders",
        "ImagingOrderController.getOrdersByHospital",
        "ImagingOrderController.getOrder",
        // The exam: check-in, start / complete / discontinue, and its record.
        "ImagingOrderController.checkIn",
        "ImagingOrderController.updateStatus",
        "ImagingOrderController.recordExam",
        // Key images: attach, caption and (before the signature) remove; read the study's images.
        "ImagingStudyImageController.upload",
        "ImagingStudyImageController.updateCaption",
        "ImagingStudyImageController.delete",
        "ImagingStudyImageController.list",
        "ImagingStudyImageController.content",
        // What became of an image they attached: when, by whom and why it was withdrawn (never the bytes).
        "ImagingStudyImageController.withdrawn",
        // Released reports of exams they performed (ImagingReportServiceImpl).
        "ImagingResultController.getReport",
        "ImagingResultController.getLatestReportForOrder",
        "ImagingResultController.getAllReportsForOrder",
        // V356: the provisional rules of their hospital and their sign-off status (the technician's own rule
        // shows « provisoire » or « validée » from it), and the printed pack. Read-only, no patient.
        "HospitalClinicalRulesController.list",
        "HospitalClinicalRulesController.pack");

    /** What the policy forbids, by name, so a widened gate fails with the rule it broke. */
    private static final Set<String> NEVER_THE_TECHNICIAN = Set.of(
        "ImagingOrderController.createOrder", "ImagingOrderController.updateOrder",
        "ImagingOrderController.captureSignature", "ImagingOrderController.getDuplicatePreview",
        "ImagingOrderController.recordAuthorization", "ImagingOrderController.getOrdersByPatient",
        "ImagingResultController.createReport", "ImagingResultController.updateReport",
        "ImagingResultController.signReport", "ImagingResultController.updateReportStatus",
        "ImagingResultController.acknowledgeCriticalResult", "ImagingResultController.getReportsToSign",
        "ImagingResultController.getReportsByHospital", "ImagingStudyImageController.withdraw",
        "ImagingReadingContextController.getReadingContext");

    private static boolean isEndpoint(Method method) {
        return isWrite(method) || method.isAnnotationPresent(GetMapping.class);
    }

    /** Every {@code @RestController} of the platform, so the technician cannot appear on an endpoint unnoticed. */
    private static List<Class<?>> everyController() {
        org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider scanner =
            new org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new org.springframework.core.type.filter.AnnotationTypeFilter(
            org.springframework.web.bind.annotation.RestController.class));
        List<Class<?>> controllers = new ArrayList<>();
        scanner.findCandidateComponents("com.example.hms").forEach(candidate -> {
            try {
                controllers.add(Class.forName(candidate.getBeanClassName()));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        });
        return controllers;
    }

    @Test
    void theImagingTechnicianIsAdmittedToExactlyTheExamEndpoints() {
        Set<String> admitted = new TreeSet<>();
        List<Class<?>> controllers = everyController();
        assertThat(controllers).as("the controllers were found").hasSizeGreaterThan(100);
        for (Class<?> controller : controllers) {
            PreAuthorize classGate = controller.getAnnotation(PreAuthorize.class);
            for (Method method : controller.getDeclaredMethods()) {
                if (!isEndpoint(method)) {
                    continue;
                }
                PreAuthorize gate = method.getAnnotation(PreAuthorize.class);
                String expression = gate != null ? gate.value() : classGate != null ? classGate.value() : "";
                if (expression.contains(TECHNICIAN)) {
                    admitted.add(controller.getSimpleName() + "." + method.getName());
                }
            }
        }
        assertThat(admitted).as("the endpoints that admit the imaging technician (policy rule 3). Never an"
            + " order, a report write or signature, or a withdrawal after signing; a new one is added here with"
            + " its reason, a removed one breaks their work" + SourceScan.MANDATE)
            .doesNotContainAnyElementsOf(NEVER_THE_TECHNICIAN)
            .isEqualTo(new TreeSet<>(TECHNICIAN_ENDPOINTS));
    }

    @Test
    void theTechniciansWritesAreAClinicalRoleOfTheirOwn_andTheReportReadsStayOnTheStaffReaderSet() {
        for (Class<?> controller : IMAGING_CONTROLLERS) {
            for (Method method : controller.getDeclaredMethods()) {
                String name = controller.getSimpleName() + "." + method.getName();
                if (isWrite(method) && TECHNICIAN_ENDPOINTS.contains(name)) {
                    assertThat(gate(controller, method)).as(name + SourceScan.MANDATE).startsWith(CLINICAL_GATE);
                }
            }
        }
        // A caller who is technician and patient reads as staff, not as the patient of every study.
        Set<String> staffReportReaders = com.example.hms.service.PatientSubjectReaderRoles.IMAGING_REPORT_READS;
        assertThat(staffReportReaders).contains("ROLE_" + TECHNICIAN);
    }

    // ── QA D2915 (r66): a role counts at the hospital it is held at ──

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");

    /** The service behind each imaging controller's field, and where its source is. */
    private static final Map<String, String> IMAGING_SERVICES = Map.of(
        "imagingOrderService", "service/impl/ImagingOrderServiceImpl.java",
        "imagingReportService", "service/impl/ImagingReportServiceImpl.java",
        "imageService", "service/imaging/ImagingStudyImageService.java",
        "viewerService", "service/imaging/ImagingStudyViewerService.java",
        "readingContextService", "service/imaging/ImagingReadingContextService.java");

    /** Endpoints no clinical role opens, each with its reason. A new entry needs one too. */
    private static final Map<String, String> NO_CLINICAL_ROLE = Map.of(
        "ImagingResultController.runCriticalEscalation",
        "an operator trigger of the scheduled escalation sweep: SUPER_ADMIN or HOSPITAL_ADMIN, no clinical role");

    private static final String SCOPE = "ImagingRoleScope(roleValidator)";
    private static final Pattern SERVICE_CALL = Pattern.compile("\\b(\\w++)\\s*+\\.\\s*+(\\w++)\\s*+\\(");
    /** A call; one on the class itself has no dot before it ({@code self.}, its own proxy, is dropped first). */
    private static final Pattern LOCAL_CALL = Pattern.compile("(\\w++)\\s*+\\(");

    private static final Set<String> NOT_A_CALL = Set.of("if", "for", "while", "switch", "catch", "synchronized",
        "try", "return", "throw", "new", "else", "do", "super", "this", "assert", "yield");

    private static String source(String relative) throws IOException {
        return SourceScan.stripComments(Files.readString(MAIN.resolve(relative), StandardCharsets.UTF_8));
    }

    /** Whether {@code method} of this source builds the scope, itself or through a method of the same class. */
    private static boolean asksTheScope(String code, String method, Set<String> seen) {
        String body = seen.add(method) ? SourceScan.methodBody(code, method) : null;
        if (body == null) {
            return false;
        }
        if (body.contains(SCOPE)) {
            return true;
        }
        String calls = body.replace("self.", "");
        Matcher call = LOCAL_CALL.matcher(calls);
        while (call.find()) {
            boolean onAnotherObject = call.start() > 0 && calls.charAt(call.start() - 1) == '.';
            // A keyword is not a method of the class (methodBody would return some unrelated block).
            if (!onAnotherObject && !NOT_A_CALL.contains(call.group(1)) && asksTheScope(code, call.group(1), seen)) {
                return true;
            }
        }
        return false;
    }

    /**
     * QA D2915: a technician at hospital A who is a radiologist at hospital B picked the
     * radiologist role at login, acted at A, and scheduled orders, recorded pre-authorisation
     * numbers, wrote reports and read every report there. The endpoint gates read the token, which
     * names a role wherever it is held. So every imaging endpoint calls a service method that asks
     * {@code ImagingRoleScope} what the caller is AT the order's hospital (their assignments there).
     * Behaviour: ImagingRoleScopeTest and the services' D2915 tests.
     */
    @Test
    void everyImagingEndpointAsksWhatTheCallerIsAtTheHospital() throws IOException {
        List<String> tokenOnly = new ArrayList<>();
        int endpoints = 0;
        for (Class<?> controller : IMAGING_CONTROLLERS) {
            String controllerCode = source("controller/" + controller.getSimpleName() + ".java");
            for (Method method : controller.getDeclaredMethods()) {
                String name = controller.getSimpleName() + "." + method.getName();
                if (!isEndpoint(method) || NO_CLINICAL_ROLE.containsKey(name)) {
                    continue;
                }
                endpoints++;
                String body = SourceScan.methodBody(controllerCode, method.getName());
                assertThat(body).as(name + " was found in its source").isNotNull();
                boolean asked = false;
                Matcher call = SERVICE_CALL.matcher(body);
                while (call.find() && !asked) {
                    String service = IMAGING_SERVICES.get(call.group(1));
                    asked = service != null && asksTheScope(source(service), call.group(2), new java.util.HashSet<>());
                }
                if (!asked) {
                    tokenOnly.add(name);
                }
            }
        }
        assertThat(endpoints).as("the imaging endpoints were found").isGreaterThan(25);
        assertThat(tokenOnly).as("an imaging endpoint whose service never asks ImagingRoleScope what the caller is at"
            + " the order's hospital: the role its gate read from the token may be held at another hospital (QA"
            + " D2915)" + SourceScan.MANDATE).isEmpty();
    }

    /**
     * ... and no imaging service decides on a role the token names: not through the token-wide
     * {@code ClinicalActor}, not from the authorities, not by naming the technician role itself.
     *
     * <p>Review of #1251: nor does the scope, for the administrative roles either. A hospital admin
     * of B who is a receptionist at A, acting at A under the admin role, read A's worklist, orders,
     * released reports and images, because the scope took "administrator" from the token. A hospital
     * admin or an admin is one where their assignment says; the super-admin is the VERIFIED one (a
     * live SUPER_ADMIN assignment), never the token's authority.
     */
    @Test
    void noImagingRoleClinicalOrAdministrativeIsTakenFromTheToken() throws IOException {
        for (String service : IMAGING_SERVICES.values()) {
            assertThat(source(service)).as(service + " asks the hospital-scoped rule (QA D2915)" + SourceScan.MANDATE)
                .contains(SCOPE)
                .doesNotContain("ClinicalActor")
                .doesNotContain("getAuthorities(")
                .doesNotContain("hasAnyAuthority(")
                .doesNotContain("IMAGING_TECHNICIAN");
        }
        assertThat(source("security/ClinicalActor.java")).doesNotContain("IMAGING_TECHNICIAN");
        // The scope itself reads the clinical roles from the assignments, through RoleValidator.
        String scope = source("service/imaging/ImagingRoleScope.java");
        assertThat(scope).contains("roleValidator.interpretsImaging(", "roleValidator.readsImagingClinically(",
            "roleValidator.ordersImaging(", "roleValidator.hasRole(");
        assertThat(scope).as("the administrative roles are read where they are held (review of #1251)"
                + SourceScan.MANDATE)
            .contains("roleValidator.administersImagingReads(", "roleValidator.isSuperAdminActingAt(hospitalId)")
            .doesNotContain("isSuperAdminFromAuth")
            .doesNotContain("getAuthorities(")
            .doesNotContain("SecurityContextHolder")
            .doesNotContain("Authentication")
            .doesNotContain("ClinicalActor")
            .doesNotContain("hasAnyAuthority(")
            .doesNotContain("isHospitalAdminFromAuthGlobalOnly")
            .doesNotContain("isStaffOrAdminFromAuth");
        // ... and the two lookups it asks are assignments, not authorities.
        String validator = source("utility/RoleValidator.java");
        assertThat(SourceScan.methodBody(validator, "administersImagingReads"))
            .contains("hasAnyCode(userId, hospitalId,").contains("HOSPITAL_ADMIN_ROLE").doesNotContain("hasAuthority");
        assertThat(SourceScan.methodBody(validator, "isSuperAdminActingAt"))
            .as("the verified super-admin, where the one tenant resolver says they act")
            .contains("CallerIdentity.isSuperAdminFor(hospitalId)");
        assertThat(SourceScan.methodBody(source("security/CallerIdentity.java"), "isSuperAdminFor"))
            .contains("isGlobalSuperAdmin()").contains("ActingScopeResolver.superAdminPinnedHospitalIdOrNull()")
            .doesNotContain("getAuthorities");
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
