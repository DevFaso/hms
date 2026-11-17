package com.example.hms.architecture;

import com.example.hms.controller.ProgramEnrollmentController;
import com.example.hms.security.ReadsOnly;
import com.example.hms.security.RoleExpansion;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.testsupport.ControllerMappingRegistry;
import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.expression.EvaluationException;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D2120 (r59): a super-admin with no clinical role enrolled a patient in a disease programme
 * and recorded programme visits (201/200), with no clinician recorded. Standing user decision
 * (imaging access, 2026-11-14): a clinical act needs a clinical role the caller holds; the
 * super-admin reads clinical data and never records it.
 *
 * <p>The class is wider than the programme endpoints: {@link RoleExpansion#SUPER_ADMIN_INHERITS}
 * gives every super-admin ROLE_DOCTOR, ROLE_NURSE and ROLE_LAB_SCIENTIST on every request, so
 * {@code hasAnyAuthority('ROLE_DOCTOR')} admits a super-admin who never was one, and many clinical
 * writes named SUPER_ADMIN outright. This walks Spring's own mapping registry over every
 * controller and evaluates each write's {@code @PreAuthorize} (method, else class) twice:
 * <ul>
 *   <li>as a caller holding one clinical role on their own: if any is admitted, the write is
 *       clinical;</li>
 *   <li>as a super-admin pinned to a hospital with no clinical assignment there (the token as
 *       the platform issues it, with every inherited role).</li>
 * </ul>
 * A clinical write that admits the second caller fails here, unless it is in
 * {@link #NOT_A_CLINICAL_ACT} with the reason it is administrative. The fix is a
 * {@code T(com.example.hms.security.ClinicalActor).holdsOwnRole(authentication, ...)} gate
 * naming no SUPER_ADMIN; a hospital admin named in the list keeps their right.
 */
@DisplayName("D2120: no clinical write admits a super-admin who holds no clinical role")
class SuperAdminClinicalWriteGuardTest {

    private static final ExpressionParser PARSER = new SpelExpressionParser();
    private static final Set<RequestMethod> WRITES =
        Set.of(RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE);

    /** A caller holding one of these on their own and admitted to a write makes that write clinical. */
    private static final List<String> CLINICAL_ROLES = List.of("ROLE_DOCTOR", "ROLE_NURSE", "ROLE_MIDWIFE",
        "ROLE_SURGEON", "ROLE_RADIOLOGIST", "ROLE_ANESTHESIOLOGIST", "ROLE_PHYSIOTHERAPIST",
        "ROLE_LAB_SCIENTIST", "ROLE_LAB_TECHNICIAN", "ROLE_PHARMACIST");

    /** A has*Role / has*Authority call, for an expression only runtime can evaluate (a bean call). */
    private static final Pattern ROLE_CALL = Pattern.compile("has(?:Any)?(?:Role|Authority)\\(([^)]*)\\)");
    private static final Pattern QUOTED = Pattern.compile("'(?:ROLE_)?([A-Z_]+)'");

    /**
     * Writes a clinical role may make that are not a clinical act, and why. Key: a controller
     * (every write of it) or Controller#method. Each must still name a write the super-admin
     * reaches: the day it no longer does, delete the line.
     */
    private static final Map<String, String> NOT_A_CLINICAL_ACT = Map.ofEntries(
        Map.entry("AdmissionController#createOrderSet", "order-set catalogue configuration"),
        Map.entry("AdmissionController#deactivateOrderSet", "order-set catalogue configuration"),
        Map.entry("AdmissionController#assignBed", "bed logistics, not a clinical record"),
        Map.entry("AdmissionController#unassignBed", "bed logistics, not a clinical record"),
        Map.entry("AppointmentController", "scheduling (front desk), not a clinical record"),
        Map.entry("PrenatalSchedulingController", "scheduling reminders and reschedules"),
        Map.entry("PatientRecallController", "recall outreach scheduling"),
        Map.entry("StaffSchedulingController", "staff shifts and leave (HR)"),
        Map.entry("BreakGlassController", "access governance: declares or revokes emergency access, writes no record"),
        Map.entry("ChatController", "the caller's own messages"),
        Map.entry("NotificationController", "the caller's own notifications"),
        Map.entry("InBasketController", "the caller's own in-basket read and acknowledge marks"),
        Map.entry("MeController", "the caller's own alert acknowledgement"),
        Map.entry("NurseTaskController#markInboxRead", "the caller's own inbox read mark"),
        Map.entry("FileUploadController#uploadProfileImage", "the caller's own profile image"),
        Map.entry("FileUploadController#deleteProfileImage", "the caller's own profile image"),
        Map.entry("FileUploadController#uploadChatAttachment", "the caller's own chat attachment"),
        Map.entry("DrugInteractionAdminController", "drug-interaction catalogue configuration"),
        Map.entry("LabTestDefinitionController", "lab test catalogue configuration"),
        Map.entry("LabTestValidationStudyController", "lab method validation, no patient"),
        Map.entry("LabReflexRuleController", "lab reflex rule configuration"),
        Map.entry("LabQcEventController", "instrument quality control, no patient"),
        Map.entry("MedicationCatalogController", "medication catalogue configuration"),
        Map.entry("SmartPhraseController", "text template library"),
        Map.entry("PatientEducationController#createResource", "education content library"),
        Map.entry("PatientEducationController#updateResource", "education content library"),
        Map.entry("Hl7InboundController", "integration adapter intake"),
        Map.entry("InventoryController", "pharmacy stock"),
        Map.entry("StockTransactionController", "pharmacy stock"),
        Map.entry("TransfusionController#receiveUnit", "blood-bank stock receipt"),
        Map.entry("TransfusionController#discardUnit", "blood-bank stock disposal"),
        Map.entry("PharmacySaleController", "pharmacy till"),
        Map.entry("PharmacyPaymentController", "pharmacy payment"),
        Map.entry("PharmacyClaimController", "insurance claim"),
        Map.entry("EligibilityController", "insurance coverage and prior authorisation"),
        Map.entry("PatientController#createPatientByReceptionist", "patient registration (demographics)"),
        Map.entry("PatientController#updatePatient", "patient demographics"),
        Map.entry("PatientController#patchPatient", "patient demographics"),
        Map.entry("PatientHospitalRegistrationController", "patient registration"),
        Map.entry("PatientGuarantorController", "guarantor (billing) details"),
        Map.entry("PatientInsuranceController", "insurance details"),
        Map.entry("PatientPhotoController", "identification photo"),
        Map.entry("PhoneVerificationController", "contact verification"),
        Map.entry("PatientRoiController", "release of information (records office)"),
        Map.entry("RoiWorklistController", "release of information (records office)"),
        Map.entry("PatientPanelController", "care-team (panel) administration"),
        Map.entry("ReceptionController#updateEncounterStatus", "front-desk flow status"),
        Map.entry("ProviderAdminController", "staff administration"),
        Map.entry("UserController", "user administration"),
        Map.entry("SuperAdminImpersonationController", "ends the super-admin's own impersonation"));

    @AfterEach
    void clear() {
        HospitalContextHolder.clear();
    }

    @Test
    void noClinicalWriteAdmitsASuperAdminAlone() {
        Map<String, String> offenders = new TreeMap<>();
        Set<String> allowedSeen = new TreeSet<>();
        int clinical = 0;
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry
                : ControllerMappingRegistry.mapping().getHandlerMethods().entrySet()) {
            HandlerMethod handler = entry.getValue();
            Set<RequestMethod> verbs = entry.getKey().getMethodsCondition().getMethods();
            String expression = expression(handler);
            if (verbs.stream().noneMatch(WRITES::contains) || handler.getMethod().isAnnotationPresent(ReadsOnly.class)
                || expression == null) {
                continue;
            }
            String controller = handler.getBeanType().getSimpleName();
            String key = controller + "#" + handler.getMethod().getName();
            Boolean superAdmin = evaluate(expression, superAdminToken(), Set.of("ROLE_SUPER_ADMIN"));
            boolean isClinical = false;
            boolean undecidable = superAdmin == null;
            for (String role : CLINICAL_ROLES) {
                Boolean admitted = evaluate(expression, Set.of(role), Set.of(role));
                undecidable |= admitted == null;
                isClinical |= Boolean.TRUE.equals(admitted);
            }
            if (undecidable) {
                // A bean call: decide on the role calls it makes.
                isClinical = namesAny(expression, CLINICAL_ROLES);
                superAdmin = namesAny(expression, superAdminToken());
            }
            if (!isClinical) {
                continue;
            }
            clinical++;
            if (!Boolean.TRUE.equals(superAdmin)) {
                continue;
            }
            String allowedBy = NOT_A_CLINICAL_ACT.containsKey(key) ? key
                : NOT_A_CLINICAL_ACT.containsKey(controller) ? controller : null;
            if (allowedBy != null) {
                allowedSeen.add(allowedBy);
            } else {
                offenders.put(key, verbs + " " + entry.getKey().getPatternValues() + " :: " + expression);
            }
        }
        assertThat(clinical).as("the clinical writes were found").isGreaterThan(200);
        assertThat(offenders).as("a clinical write a super-admin with no clinical role can make (QA D2120, user"
            + " decision 2026-11-14: gate it with ClinicalActor.holdsOwnRole and name no SUPER_ADMIN)"
            + SourceScan.MANDATE).isEmpty();
        assertThat(allowedSeen).as("every NOT_A_CLINICAL_ACT line still names a write the super-admin reaches"
            + " (delete a stale line)").isEqualTo(new TreeSet<>(NOT_A_CLINICAL_ACT.keySet()));
    }

    /** The programme gates themselves (the D2120 instance): the clinician acts, the super-admin alone does not. */
    @Test
    void programmeWritesNeedAClinicalRoleTheCallerHolds() {
        int writes = 0;
        for (Method method : ProgramEnrollmentController.class.getDeclaredMethods()) {
            PreAuthorize own = method.getAnnotation(PreAuthorize.class);
            boolean write = AnnotatedElementUtils.hasAnnotation(method,
                org.springframework.web.bind.annotation.RequestMapping.class)
                && !AnnotatedElementUtils.hasAnnotation(method, org.springframework.web.bind.annotation.GetMapping.class);
            if (!write) {
                continue;
            }
            writes++;
            assertThat(own).as(method.getName() + " carries its own gate").isNotNull();
            String gate = own.value();
            assertThat(evaluate(gate, superAdminToken(), Set.of("ROLE_SUPER_ADMIN")))
                .as(method.getName() + ": the super-admin alone").isFalse();
            Set<String> superAdminDoctor = new LinkedHashSet<>(superAdminToken());
            assertThat(evaluate(gate, superAdminDoctor, Set.of("ROLE_SUPER_ADMIN", "ROLE_DOCTOR")))
                .as(method.getName() + ": a super-admin who is also a doctor at this hospital").isTrue();
            assertThat(evaluate(gate, Set.of("ROLE_NURSE"), Set.of("ROLE_NURSE")))
                .as(method.getName() + ": a nurse").isTrue();
            assertThat(evaluate(gate, Set.of("ROLE_HOSPITAL_ADMIN"), Set.of("ROLE_HOSPITAL_ADMIN")))
                .as(method.getName() + ": a hospital admin, as before").isFalse();
        }
        assertThat(writes).as("enrol, status, visit").isEqualTo(3);
    }

    /** The token a super-admin carries: SUPER_ADMIN plus every inherited role. */
    private static Set<String> superAdminToken() {
        Set<String> token = new LinkedHashSet<>(RoleExpansion.SUPER_ADMIN_INHERITS);
        token.add("ROLE_SUPER_ADMIN");
        return token;
    }

    private static String expression(HandlerMethod handler) {
        PreAuthorize annotation = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), PreAuthorize.class);
        if (annotation == null) {
            annotation = AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(), PreAuthorize.class);
        }
        return annotation == null ? null : annotation.value();
    }

    private static boolean namesAny(String expression, java.util.Collection<String> roles) {
        Matcher call = ROLE_CALL.matcher(expression);
        while (call.find()) {
            Matcher quoted = QUOTED.matcher(call.group(1));
            while (quoted.find()) {
                if (roles.contains("ROLE_" + quoted.group(1))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The expression for a caller with these authorities, whose live assignments at the pinned hospital are
     * {@code assigned}; null when it calls something only runtime can answer (a bean).
     */
    private static Boolean evaluate(String expression, Set<String> authorities, Set<String> assigned) {
        HospitalContextHolder.setContext(HospitalContext.builder().assignedRoles(assigned)
            .superAdmin(assigned.contains("ROLE_SUPER_ADMIN")).headerOverridden(true).build());
        try {
            return PARSER.parseExpression(expression)
                .getValue(new StandardEvaluationContext(new Root(authorities)), Boolean.class);
        } catch (EvaluationException | org.springframework.expression.ParseException e) {
            return null;
        } finally {
            HospitalContextHolder.clear();
        }
    }

    /** The role functions of Spring Security's expression root over a set of authorities. */
    public static final class Root {
        private final Set<String> authorities;

        Root(Set<String> authorities) {
            this.authorities = authorities;
        }

        public boolean hasAuthority(String wanted) {
            return authorities.contains(wanted);
        }

        public boolean hasAnyAuthority(String... wanted) {
            for (String one : wanted) {
                if (authorities.contains(one)) {
                    return true;
                }
            }
            return false;
        }

        public boolean hasRole(String wanted) {
            return authorities.contains(wanted.startsWith("ROLE_") ? wanted : "ROLE_" + wanted);
        }

        public boolean hasAnyRole(String... wanted) {
            for (String one : wanted) {
                if (hasRole(one)) {
                    return true;
                }
            }
            return false;
        }

        public Authentication getAuthentication() {
            return new UsernamePasswordAuthenticationToken("guard", "n/a",
                authorities.stream().map(SimpleGrantedAuthority::new).toList());
        }

        public boolean isAuthenticated() {
            return true;
        }

        public boolean permitAll() {
            return true;
        }

        public boolean denyAll() {
            return false;
        }
    }
}
