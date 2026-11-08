package com.example.hms.i18n;

import com.example.hms.enums.AllergySeverity;
import com.example.hms.enums.AppointmentStatus;
import com.example.hms.enums.BloodProductType;
import com.example.hms.enums.CareProgram;
import com.example.hms.enums.EncounterStatus;
import com.example.hms.enums.InteractionSeverity;
import com.example.hms.enums.InvoiceAdjustmentType;
import com.example.hms.enums.InvoiceStatus;
import com.example.hms.enums.IsolationPrecautionType;
import com.example.hms.enums.ItemCategory;
import com.example.hms.enums.LabTestDefinitionApprovalStatus;
import com.example.hms.enums.LaborAlertType;
import com.example.hms.enums.LiquorColour;
import com.example.hms.enums.MedicalCertificateType;
import com.example.hms.enums.NewbornAlertType;
import com.example.hms.enums.PaymentMethod;
import com.example.hms.enums.PharmacyClaimStatus;
import com.example.hms.enums.PostpartumAlertType;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.enums.RoiRequestStatus;
import com.example.hms.enums.RoutingDecisionStatus;
import com.example.hms.enums.SignatureType;
import com.example.hms.enums.StockTransactionType;
import com.example.hms.exception.HospitalScopeRefusedException;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.superadmin.SuperAdminPlatformRegistrySummaryDTO.AutomationStatus;
import com.example.hms.service.pharmacy.PrescriberPharmacyNotifier;
import com.example.hms.utility.NewsScoreCalculator;
import jakarta.persistence.Entity;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * QA D1536 (round 40): the inputs of every computed message key, so
 * {@link MessageKeyResolutionGuardTest} can resolve each key a computed site
 * can produce. The registry id is {@code <File>.java|<first key fragment>}, as
 * {@link MessageKeyScanner.Site#registryId()} gives it; every computed site
 * must have an entry, and every entry must still have a site.
 *
 * <p>How a domain is enumerated:
 * <ul>
 *   <li>{@link #enumValues}: every constant of the enum(s) the expression reads
 *       ({@code "cds.severity." + allergy.getSeverity().name()});</li>
 *   <li>{@link #values}: a fixed list, a static field of the class read by
 *       reflection, or the seeded role catalog;</li>
 *   <li>{@link #callers}: the literal (or same-file constant) arguments of
 *       every call of the method that builds the key; a call passing anything
 *       else fails, since its value cannot be enumerated;</li>
 *   <li>{@link #literalsIn}: every literal of a pattern in the source that
 *       produces the codes (alert codes such as {@code "labor-pph"});</li>
 *   <li>{@link #open}: an input no list can bound (a stored code, any DTO
 *       field, any enum), at a site whose lookup has a fallback by design;
 *       the reason is written beside it.</li>
 * </ul>
 */
final class ComputedKeys {

    interface Domain {
        Set<String> keys() throws IOException;
    }

    /** A site whose input no list can bound; the reason says why its lookup is safe. */
    record Open(String reason) implements Domain {
        @Override
        public Set<String> keys() {
            return Set.of();
        }
    }

    static final Map<String, Domain> REGISTRY = registry();

    private ComputedKeys() {
    }

    private static Map<String, Domain> registry() {
        Map<String, Domain> r = new LinkedHashMap<>();

        // ---- per enum value ----------------------------------------------------------------
        r.put("AppointmentStatus.java|appointment.statusLabel.", enumValues("appointment.statusLabel.", AppointmentStatus.class));
        r.put("EncounterStatus.java|encounter.statusLabel.", enumValues("encounter.statusLabel.", EncounterStatus.class));
        r.put("InvoiceStatus.java|pdf.invoice.statusValue.", enumValues("pdf.invoice.statusValue.", InvoiceStatus.class));
        r.put("PrescriptionStatus.java|cds.enum.", enumValues("cds.enum.", PrescriptionStatus.class));
        r.put("RoutingDecisionStatus.java|stock.routing.statusLabel.",
            enumValues("stock.routing.statusLabel.", RoutingDecisionStatus.class));
        r.put("CareGapTraceService.java|care.gap.program.", enumValues("care.gap.program.", CareProgram.class));
        // severityWord(InteractionSeverity | AllergySeverity); enumWord(PrescriptionStatus) and, as
        // severityWord's own fallback, the severities again.
        r.put("CdsCardText.java|cds.severity.",
            enumValues("cds.severity.", InteractionSeverity.class, AllergySeverity.class));
        r.put("CdsCardText.java|cds.enum.", enumValues("cds.enum.", PrescriptionStatus.class));
        r.put("CdsCheckServiceImpl.java|cds.severity.", enumValues("cds.severity.", InteractionSeverity.class));
        // PatientServiceImpl and PrescriptionServiceImpl: the allergy's severity, or "UNKNOWN" for none.
        r.put("PatientServiceImpl.java|cds.severity.",
            union(enumValues("cds.severity.", AllergySeverity.class), values("cds.severity.", List.of("UNKNOWN"))));
        r.put("PrescriptionServiceImpl.java|cds.severity.",
            union(enumValues("cds.severity.", AllergySeverity.class), values("cds.severity.", List.of("UNKNOWN"))));
        r.put("IsolationServiceImpl.java|isolation.type.", enumValues("isolation.type.", IsolationPrecautionType.class));
        r.put("LabTestDefinitionServiceImpl.java|lab.definition.status.",
            enumValues("lab.definition.status.", LabTestDefinitionApprovalStatus.class));
        r.put("LaborAlertArgs.java|labor.liquor.", enumValues("labor.liquor.", LiquorColour.class));
        r.put("LaborServiceImpl.java|labor.liquor.", enumValues("labor.liquor.", LiquorColour.class));
        r.put("LaborServiceImpl.java|labor.alert.type.", enumValues("labor.alert.type.", LaborAlertType.class));
        r.put("NewbornAssessmentServiceImpl.java|newborn.alert.type.", enumValues("newborn.alert.type.", NewbornAlertType.class));
        r.put("PostpartumCareServiceImpl.java|postpartum.alert.type.",
            enumValues("postpartum.alert.type.", PostpartumAlertType.class));
        // typePrefix(sourceType) + "type." + alertType: the three obstetric sources and their type enums.
        r.put("ObstetricAlertEscalationWorker.java|type.", union(
            enumValues("labor.alert.type.", LaborAlertType.class),
            enumValues("newborn.alert.type.", NewbornAlertType.class),
            enumValues("postpartum.alert.type.", PostpartumAlertType.class)));
        r.put("MedicalCertificateServiceImpl.java|medicalCertificate.title.",
            enumValues("medicalCertificate.title.", MedicalCertificateType.class));
        r.put("PdfInvoiceService.java|pdf.invoice.adjustment.",
            enumValues("pdf.invoice.adjustment.", InvoiceAdjustmentType.class));
        r.put("PdfInvoiceService.java|pdf.invoice.category.", enumValues("pdf.invoice.category.", ItemCategory.class));
        r.put("PdfInvoiceService.java|pdf.invoice.statusValue.", enumValues("pdf.invoice.statusValue.", InvoiceStatus.class));
        r.put("PdfInvoiceService.java|pdf.payment.method.", enumValues("pdf.payment.method.", PaymentMethod.class));
        r.put("PharmacyClaimServiceImpl.java|pharmacy.claim.status.",
            enumValues("pharmacy.claim.status.", PharmacyClaimStatus.class));
        r.put("ResultReviewServiceImpl.java|signature.type.", enumValues("signature.type.", SignatureType.class));
        r.put("RoiRequestService.java|roi.request.status.", enumValues("roi.request.status.", RoiRequestStatus.class));
        r.put("SuperAdminPlatformRegistryServiceImpl.java|platform.automation.status.",
            enumValues("platform.automation.status.", AutomationStatus.class));
        r.put("TransfusionChargeSource.java|transfusion.productType.",
            enumValues("transfusion.productType.", BloodProductType.class));
        r.put("TransfusionServiceImpl.java|transfusion.productType.",
            enumValues("transfusion.productType.", BloodProductType.class));
        r.put("ControlledDrugRegisterService.java|pharmacy.register.movement.",
            enumValues("pharmacy.register.movement.", StockTransactionType.class));
        r.put("NewsScoreProtocolRule.java|cds.bpa.news2.band.",
            enumValues("cds.bpa.news2.band.", NewsScoreCalculator.RiskBand.class));
        // The reason of a HospitalScopeRefusedException: an ActingScope.Reason, GLOBAL_VIEW or PATIENT_OWNED.
        r.put("ActingScopeResolver.java|hospitalScope.refused.", union(
            enumValues("hospitalScope.refused.", actingScopeReason()),
            values("hospitalScope.refused.",
                List.of(HospitalScopeRefusedException.GLOBAL_VIEW, HospitalScopeRefusedException.PATIENT_OWNED))));
        // write(prescriptionId, event) for the events the notifier forwards, and the two partner keys.
        r.put("PrescriberPharmacyNotificationWriter.java|prescription.pharmacy.event.", union(
            () -> prefixed("prescription.pharmacy.event.",
                PrescriberPharmacyNotifier.NOTIFIED_EVENTS.stream().map(Enum::name).toList(), ""),
            values("prescription.pharmacy.event.", List.of("PARTNER_TIMED_OUT", "PARTNER_DISPENSED_WITHDRAWN"))));

        // ---- per role ----------------------------------------------------------------------
        r.put("EmailServiceImpl.java|email.role.label.", () -> prefixed("email.role.label.", bareRoleCodes(), ""));
        r.put("UserRoleHospitalAssignmentServiceImpl.java|email.role.label.",
            () -> prefixed("email.role.label.", bareRoleCodes(), ""));
        r.put("UserRoleHospitalAssignmentServiceImpl.java|role.description.",
            () -> prefixed("role.description.", seededRoles().keySet(), ""));
        r.put("UserRoleHospitalAssignmentServiceImpl.java|onboarding.checklist.", ComputedKeys::checklistKeys);

        // ---- per field / per code: a fixed list or a static field ---------------------------
        r.put("RoutingReasonText.java|stock.routing.pharmacyType.",
            () -> prefixed("stock.routing.pharmacyType.", staticStrings(
                com.example.hms.mapper.pharmacy.RoutingReasonText.class, "PHARMACY_TYPES"), ""));
        r.put("ControlledDrugRegisterService.java|pharmacy.register.adjustment.",
            () -> prefixed("pharmacy.register.adjustment.", staticMapValues(
                com.example.hms.service.pharmacy.ControlledDrugRegisterService.class, "ADJUSTMENT_CODES"), ""));
        r.put("MedicationRoutes.java|medication.route.",
            () -> prefixed("medication.route.", staticMapValues(
                com.example.hms.service.emar.MedicationRoutes.class, "SYNONYMS"), ""));
        // recommendationKey(type): INITIAL_INTAKE, ULTRASOUND and LATE_PREGNANCY by name, every other type ROUTINE_CHECK.
        List<String> prenatal = List.of("INITIAL_INTAKE", "ULTRASOUND", "LATE_PREGNANCY", "ROUTINE_CHECK");
        r.put("PrenatalSchedulingServiceImpl.java|prenatal.schedule.recommendation.",
            values("prenatal.schedule.recommendation.", prenatal));
        r.put("PrenatalSchedulingServiceImpl.java|prenatal.schedule.notes.", values("prenatal.schedule.notes.", prenatal));
        // conflictFor(field, …): FIELD_USERNAME, FIELD_EMAIL and "phone".
        r.put("UserServiceImpl.java|users.identifier.deletedAccount.",
            callers("UserServiceImpl.java", "conflictFor", "users.identifier.deletedAccount.{0}"));
        r.put("UserServiceImpl.java|users.identifier.taken.",
            callers("UserServiceImpl.java", "conflictFor", "users.identifier.taken.{0}"));
        // Every entity with a UserRoleHospitalAssignment attribute (AssignmentReferences reads the metamodel).
        r.put("UserRoleHospitalAssignmentServiceImpl.java|users.assignment.reference.",
            () -> prefixed("users.assignment.reference.", entitiesReferencingAnAssignment(), ""));
        // The NEWS2 parameters NewsScoreCalculator reports missing.
        r.put("NewsScoreProtocolRule.java|cds.bpa.news2.param.",
            literalsIn("NewsScoreCalculator.java", "missing\\.add\\(\"(\\w+)\"\\)", "cds.bpa.news2.param.%s"));
        // The protocols with a rule in code; each has a name and a summary.
        r.put("BpaCardText.java|cds.bpa.", protocolKeys());
        r.put("PregnancyHypertensionProtocolRule.java|cds.bpa.", protocolKeys());

        // ---- the literal arguments of the callers -------------------------------------------
        r.put("HospitalLifecycleServiceImpl.java|hospital.lifecycle.action.", union(
            callers("HospitalLifecycleServiceImpl.java", "requireTransition", 2, a -> "hospital.lifecycle.action." + a.replace(' ', '-')),
            callers("HospitalLifecycleServiceImpl.java", "requireReason", 1, a -> "hospital.lifecycle.action." + a.replace(' ', '-'))));
        r.put("SecurityRuleGovernanceServiceImpl.java|security.template.",
            callers("SecurityRuleGovernanceServiceImpl.java", "templateText", "security.template.{0}.{1}"));
        r.put("SuperAdminPlatformRegistryServiceImpl.java|.title",
            callers("SuperAdminPlatformRegistryServiceImpl.java", "buildModule", "{0}.title"));
        r.put("SuperAdminPlatformRegistryServiceImpl.java|.description",
            callers("SuperAdminPlatformRegistryServiceImpl.java", "buildModule", "{0}.description"));
        // fieldLabel(field) is called from requireValue(value, field) and requireBoolean(value, field).
        r.put("SuperAdminLabOrderServiceImpl.java|field.label.", union(
            callers("SuperAdminLabOrderServiceImpl.java", "requireValue", "field.label.{1}"),
            callers("SuperAdminLabOrderServiceImpl.java", "requireBoolean", "field.label.{1}")));
        r.put("EnumQueryParam.java|field.label.", callersAnywhere("EnumQueryParam\\s*\\.\\s*refusal", 0, a -> "field.label." + a));
        // refusal(field, keyPrefix, reason): the guarded URL fields' prefixes and the reasons refusal names.
        r.put("OutboundUrlGuard.java|.", () -> {
            Set<String> prefixes = new TreeSet<>();
            for (String method : List.of("requirePublicHttps", "requirePublicHttpsShape")) {
                prefixes.addAll(callersAnywhere("OutboundUrlGuard\\s*\\.\\s*" + method, 2, a -> a).keys());
            }
            Set<String> reasons = callers("OutboundUrlGuard.java", "refusal", "{2}").keys();
            Set<String> keys = new TreeSet<>();
            for (String prefix : prefixes) {
                for (String reason : reasons) {
                    keys.add(prefix + "." + reason);
                }
            }
            return keys;
        });

        // ---- alert codes: the literals of the rules that raise them ------------------------
        r.put("LaborServiceImpl.java|labor.alert.msg.",
            literalsIn("LaborServiceImpl.java", "alertText\\(\\s*\"(labor-[a-z0-9-]+)\"", "labor.alert.msg.%s"));
        Domain newbornCodes = literalsIn(List.of("NewbornAlertRules.java", "NewbornAssessmentServiceImpl.java"),
            "\"(newborn-[a-z0-9-]+)\"", "%s");
        r.put("NewbornAssessmentServiceImpl.java|newborn.alert.msg.", mapped(newbornCodes, c -> "newborn.alert.msg." + c));
        r.put("NewbornAssessmentServiceImpl.java|newborn.alert.trigger.", mapped(newbornCodes, c -> "newborn.alert.trigger." + c));
        Domain postpartumCodes = literalsIn("PostpartumCareServiceImpl.java", "alertText\\(\\s*\"(postpartum-[a-z0-9-]+)\"", "%s");
        r.put("PostpartumCareServiceImpl.java|postpartum.alert.msg.", mapped(postpartumCodes, c -> "postpartum.alert.msg." + c));
        r.put("PostpartumCareServiceImpl.java|postpartum.alert.trigger.",
            mapped(postpartumCodes, c -> "postpartum.alert.trigger." + c));

        // ---- open: no list bounds the input, and the lookup has a fallback by design --------
        r.put("GlobalExceptionHandler.java|field.label.", new Open(
            "the leaf of any request property a validation names; the lookup has a null default and the "
                + "refusal then names the property itself"));
        r.put("GlobalExceptionHandler.java|field.label.param.", new Open(
            "any query parameter a request names; the lookup has a null default and falls back to field.label"));
        r.put("DrugInteractionText.java|ddi.seed.", new Open(
            "the two RxNorm codes of a stored interaction: only seeded pairs have text, resolveOrNull keeps the "
                + "stored sentence for every other pair"));
        r.put("PatientRecordPdfService.java|cds.severity.", new Open(
            "a code stored on the record; getOrCode prints the code made readable when the bundle has none"));
        r.put("PatientRecordPdfService.java|cds.enum.", new Open(
            "any stored code of the record PDF; getOrCode falls back to the readable code"));
        r.put("PatientRecordPdfService.java|pdf.record.enum.", new Open(
            "any stored code of the record PDF; getOrCode falls back to the cds.enum word"));
        r.put("TimelineStatus.java|timeline.status.", new Open(
            "any status enum of any timeline row; resolveOrNull falls back to pdf.record.enum and the readable name"));
        r.put("VisitTimelineRows.java|timeline.enum.", new Open(
            "any enum on a timeline row; resolveOrNull falls back to pdf.record.enum and the readable name"));
        r.put("VisitTimelineRows.java|pdf.record.enum.", new Open(
            "any enum or stored status on a timeline row; resolveOrNull falls back to the readable name"));
        r.put("VisitTimelineRows.java|timeline.immunization.source.", new Open(
            "a stored immunization source; resolveOrNull falls back to the stored text"));
        r.put("VisitTimelineRows.java|timeline.status.Immunization.", new Open(
            "a stored immunization status; resolveOrNull falls back to the stored text"));
        return r;
    }

    // ---- domain builders ------------------------------------------------------------------------

    @SafeVarargs
    private static Domain enumValues(String prefix, Class<? extends Enum<?>>... enums) {
        return () -> {
            Set<String> keys = new TreeSet<>();
            for (Class<? extends Enum<?>> e : enums) {
                for (Enum<?> constant : e.getEnumConstants()) {
                    keys.add(prefix + constant.name());
                }
            }
            return keys;
        };
    }

    private static Domain values(String prefix, Collection<String> values) {
        return () -> prefixed(prefix, values, "");
    }

    private static Domain union(Domain... domains) {
        return () -> {
            Set<String> keys = new TreeSet<>();
            for (Domain d : domains) {
                keys.addAll(d.keys());
            }
            return keys;
        };
    }

    private static Domain mapped(Domain domain, UnaryOperator<String> toKey) {
        return () -> {
            Set<String> keys = new TreeSet<>();
            for (String value : domain.keys()) {
                keys.add(toKey.apply(value));
            }
            return keys;
        };
    }

    private static Set<String> prefixed(String prefix, Collection<String> values, String suffix) {
        Set<String> keys = new TreeSet<>();
        for (String v : values) {
            keys.add(prefix + v + suffix);
        }
        return keys;
    }

    /**
     * The keys built from every call of {@code method} in {@code file}: {@code template}'s
     * {@code {i}} is the call's i-th argument. A call whose used arguments are not literals
     * (or same-file constants) cannot be enumerated, and fails.
     */
    private static Domain callers(String file, String method, String template) {
        return () -> {
            Set<String> keys = new TreeSet<>();
            List<List<String>> calls = MessageKeyScanner.literalCallArguments(source(file), method, null);
            if (calls.isEmpty()) {
                throw new IllegalStateException(file + " has no call of " + method + " any more");
            }
            Matcher placeholder = Pattern.compile("\\{(\\d+)}").matcher(template);
            List<Integer> used = new ArrayList<>();
            while (placeholder.find()) {
                used.add(Integer.parseInt(placeholder.group(1)));
            }
            for (List<String> args : calls) {
                String key = template;
                for (int i : used) {
                    String value = i < args.size() ? args.get(i) : null;
                    if (value == null) {
                        throw new IllegalStateException(file + ": a call of " + method + " passes argument " + i
                            + " that is not a literal or a constant, so the keys it builds cannot be enumerated: "
                            + "register its domain another way");
                    }
                    key = key.replace("{" + i + "}", value);
                }
                keys.add(key);
            }
            return keys;
        };
    }

    private static Domain callers(String file, String method, int index, UnaryOperator<String> toKey) {
        return () -> {
            Set<String> keys = new TreeSet<>();
            for (String key : callers(file, method, "{" + index + "}").keys()) {
                keys.add(toKey.apply(key));
            }
            return keys;
        };
    }

    /** Like {@link #callers}, for a static method called from any file ({@code qualifiedCall} is a regex). */
    private static Domain callersAnywhere(String qualifiedCall, int index, UnaryOperator<String> toKey) {
        return () -> {
            Set<String> keys = new TreeSet<>();
            Pattern call = Pattern.compile(qualifiedCall + "\\s*\\(");
            for (Map.Entry<String, String> file : sources().entrySet()) {
                String src = MessageKeyScanner.stripComments(file.getValue());
                Matcher m = call.matcher(src);
                while (m.find()) {
                    int close = MessageBundleParityTest.matchingParen(src, m.end());
                    List<String> args = MessageBundleParityTest.topLevelArguments(src.substring(m.end(), close));
                    String arg = index < args.size() ? args.get(index).trim() : "";
                    if (!(arg.startsWith("\"") && arg.endsWith("\""))) {
                        throw new IllegalStateException(file.getKey() + ": " + qualifiedCall + " is called with "
                            + arg + ", which is not a literal: register its domain another way");
                    }
                    keys.add(toKey.apply(arg.substring(1, arg.length() - 1)));
                }
            }
            if (keys.isEmpty()) {
                throw new IllegalStateException("no call of " + qualifiedCall + " any more");
            }
            return keys;
        };
    }

    private static Domain literalsIn(String file, String regex, String format) {
        return literalsIn(List.of(file), regex, format);
    }

    private static Domain literalsIn(List<String> files, String regex, String format) {
        return () -> {
            Set<String> keys = new TreeSet<>();
            Pattern p = Pattern.compile(regex);
            for (String file : files) {
                Matcher m = p.matcher(MessageKeyScanner.stripComments(source(file)));
                while (m.find()) {
                    keys.add(String.format(Locale.ROOT, format, m.group(1)));
                }
            }
            return keys;
        };
    }

    /** {@code cds.bpa.<code>.name} and {@code .summary} for every protocol rule's PROTOCOL_CODE. */
    private static Domain protocolKeys() {
        return () -> {
            Set<String> keys = new TreeSet<>();
            Pattern code = Pattern.compile("PROTOCOL_CODE\\s*=\\s*\"(\\w+)\"");
            for (Map.Entry<String, String> file : sources().entrySet()) {
                if (!file.getKey().endsWith("ProtocolRule.java")) {
                    continue;
                }
                Matcher m = code.matcher(file.getValue());
                while (m.find()) {
                    keys.add("cds.bpa." + m.group(1) + ".name");
                    keys.add("cds.bpa." + m.group(1) + ".summary");
                }
            }
            if (keys.isEmpty()) {
                throw new IllegalStateException("no BPA protocol rule found");
            }
            return keys;
        };
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Enum<?>> actingScopeReason() {
        try {
            return (Class<? extends Enum<?>>) Class.forName("com.example.hms.security.tenant.ActingScope$Reason");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, String> seededRoles() {
        return (Map<String, String>) ReflectionTestUtils.invokeMethod(
            com.example.hms.seed.RoleSeeder.class, "buildRoleCatalog");
    }

    private static List<String> bareRoleCodes() {
        return seededRoles().keySet().stream()
            .map(code -> code.startsWith("ROLE_") ? code.substring("ROLE_".length()) : code)
            .toList();
    }

    @SuppressWarnings("unchecked")
    private static Set<String> checklistKeys() {
        Map<String, List<String>> all = new LinkedHashMap<>((Map<String, List<String>>) ReflectionTestUtils.getField(
            com.example.hms.service.UserRoleHospitalAssignmentServiceImpl.class, "ROLE_PROFILE_CHECKLISTS"));
        all.put((String) ReflectionTestUtils.getField(
                com.example.hms.service.UserRoleHospitalAssignmentServiceImpl.class, "DEFAULT_CHECKLIST_KEY"),
            (List<String>) ReflectionTestUtils.getField(
                com.example.hms.service.UserRoleHospitalAssignmentServiceImpl.class, "DEFAULT_PROFILE_CHECKLIST"));
        Set<String> keys = new TreeSet<>();
        all.forEach((owner, lines) -> {
            for (int i = 1; i <= lines.size(); i++) {
                keys.add("onboarding.checklist." + owner + "." + i);
            }
        });
        return keys;
    }

    @SuppressWarnings("unchecked")
    private static Collection<String> staticStrings(Class<?> type, String field) {
        return (Collection<String>) ReflectionTestUtils.getField(type, field);
    }

    @SuppressWarnings("unchecked")
    private static Collection<String> staticMapValues(Class<?> type, String field) {
        return new LinkedHashSet<>(((Map<String, String>) ReflectionTestUtils.getField(type, field)).values());
    }

    /** The simple names of every entity with an attribute of type UserRoleHospitalAssignment. */
    private static Set<String> entitiesReferencingAnAssignment() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        Set<String> names = new TreeSet<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("com.example.hms")) {
            try {
                Class<?> type = Class.forName(candidate.getBeanClassName());
                if (type == UserRoleHospitalAssignment.class
                        || type == com.example.hms.model.AuditEventLog.class) {
                    continue;
                }
                for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                    if (Arrays.stream(c.getDeclaredFields()).map(Field::getType)
                            .anyMatch(t -> t == UserRoleHospitalAssignment.class)) {
                        names.add(type.getSimpleName());
                        break;
                    }
                }
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }
        return names;
    }

    // ---- sources ------------------------------------------------------------------------------

    private static Map<String, String> sources;

    private static synchronized Map<String, String> sources() throws IOException {
        if (sources == null) {
            Map<String, String> out = new LinkedHashMap<>();
            try (Stream<Path> files = Files.walk(MessageKeyScanner.MAIN_JAVA)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    out.putIfAbsent(file.getFileName().toString(), Files.readString(file, StandardCharsets.UTF_8));
                }
            }
            sources = out;
        }
        return sources;
    }

    private static String source(String file) throws IOException {
        String text = sources().get(file);
        if (text == null) {
            throw new IllegalStateException("no source file " + file);
        }
        return text;
    }
}
