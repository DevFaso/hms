package com.example.hms.architecture;

import com.example.hms.service.clinicalrules.ClinicalRuleRegistry;
import com.example.hms.service.clinicalrules.ClinicalRuleSet;
import com.example.hms.service.clinicalrules.ClinicalRuleSetKey;
import com.example.hms.service.clinicalrules.ProvisionalRule;
import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Owner direction 2026-11-21 (V356): every rule that is « provisoire » in code is a rule set a hospital's approver
 * can sign off, and every rule set a hospital can sign off is enforced by code that says so.
 *
 * <p>The class this guards: a provisional rule nobody can sign (built, marked PROVISIONAL, absent from the
 * « Règles cliniques et validation » screen and the sign-off pack), and its mirror, a registry entry whose code
 * moved or was renamed so the sign-off no longer points at anything. Four checks:
 * <ul>
 *   <li>every {@link ClinicalRuleSetKey} has exactly one complete registry entry (a source, a version, at least one
 *       enforcing class) and its title, description and approver role in the EN, FR and ES bundles;</li>
 *   <li>registry → code: every class an entry names exists and carries {@link ProvisionalRule} with that key;</li>
 *   <li>code → registry: every class carrying {@link ProvisionalRule} is named by the entry of each of its keys;</li>
 *   <li>the existing markers: a file that says PROVISIONAL (the codebase's marker for an unapproved rule), or prints
 *       « provisoire », carries the annotation itself, or is listed below as a mention of the class that does
 *       (a DTO, an enum or a caller that only names the rule), or as no rule at all, with the reason.</li>
 * </ul>
 */
@DisplayName("Every provisional rule in code has a sign-off entry, and every entry has its code (V356)")
class ClinicalRuleRegistryGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");
    private static final Pattern ANNOTATION = Pattern.compile("@ProvisionalRule\\s*\\(([^)]*)\\)");
    private static final Pattern KEY = Pattern.compile("ClinicalRuleSetKey\\.(\\w+)");
    /**
     * The codebase's markers for a rule awaiting its approver, in comments and literals, whatever the case:
     * "PROVISIONAL (user decision …)", "(provisional)", "Provisional, pending clinical approval", « provisoire ».
     */
    private static final Pattern PROVISIONAL_MARKER = Pattern.compile("(?i)provisional|provisoire");

    /**
     * Files that mention a provisional rule enforced elsewhere (a DTO, an enum, a controller, a caller): the
     * file, and the class (named from com.example.hms) that enforces the rule and carries the annotation.
     */
    private static final Map<String, String> MENTIONS = Map.ofEntries(
        Map.entry("cdshooks/bpa/NewsScoreProtocolRule.java", "service.vitals.News2Applicability"),
        Map.entry("config/SecurityConstants.java", "service.imaging.ImagingRoleScope"),
        Map.entry("controller/AdmissionController.java", "service.impl.AdmissionServiceImpl"),
        Map.entry("controller/HospitalAdolescentConfidentialityAgeController.java", "service.recordaccess.ProxySensitivityPolicy"),
        Map.entry("controller/HospitalBillingPolicyController.java", "service.billing.ConsultationChargeSource"),
        Map.entry("controller/HospitalImagingReportingSettingsController.java", "service.imaging.ImagingReportingSettingsService"),
        Map.entry("controller/ImagingOrderController.java", "service.support.ImagingExamRecord"),
        Map.entry("controller/ImagingResultController.java", "service.imaging.ImagingRoleScope"),
        Map.entry("controller/ImagingStudyImageController.java", "service.imaging.ImagingRoleScope"),
        Map.entry("controller/LabTestDefinitionController.java", "service.recordaccess.RecommendedLabSensitivity"),
        Map.entry("controller/LaborController.java", "service.impl.LaborServiceImpl"),
        Map.entry("controller/MinorResultReleaseController.java", "service.recordaccess.MinorResultReleaseServiceImpl"),
        Map.entry("controller/MortalityController.java", "service.support.StillbirthClassification"),
        Map.entry("controller/NewbornAssessmentController.java", "service.maternity.NewbornAlertRules"),
        Map.entry("controller/RefillApprovalController.java", "service.impl.RefillApprovalServiceImpl"),
        Map.entry("controller/SafeguardingReviewController.java", "service.safeguarding.MinorLabourSafeguarding"),
        Map.entry("controller/UnsignedNoteController.java", "service.UnsignedNoteFollowUp"),
        Map.entry("controller/pharmacy/DispenseBillingDeferralController.java", "service.pharmacy.DispenseBillingDeferralService"),
        Map.entry("enums/ClinicalPriority.java", "utility.ObstetricEligibility"),
        Map.entry("enums/DispenseBillingDeferralKind.java", "service.pharmacy.DispenseBillingDeferralService"),
        Map.entry("enums/ImagingDuplicateOverrideReason.java", "service.imaging.ImagingDuplicateOverride"),
        Map.entry("enums/LaborStatus.java", "service.impl.LaborServiceImpl"),
        Map.entry("enums/MaternalConcern.java", "service.vitals.MaternalEarlyWarningRules"),
        Map.entry("enums/OrganDamageSymptom.java", "service.vitals.SevereHypertension"),
        Map.entry("enums/PerinatalDeathType.java", "service.support.DeathClassificationRules"),
        Map.entry("enums/PostpartumAlertType.java", "service.vitals.SevereHypertension"),
        Map.entry("enums/RefillDecisionBasis.java", "service.impl.RefillApprovalServiceImpl"),
        Map.entry("enums/SensitivityCategory.java", "service.recordaccess.ProxySensitivityPolicy"),
        Map.entry("mapper/CheckOutMapper.java", "service.UnsignedNoteFollowUp"),
        Map.entry("model/Encounter.java", "service.UnsignedNoteFollowUp"),
        Map.entry("model/Hospital.java", "service.recordaccess.ProxySensitivityPolicy"),
        Map.entry("model/HospitalBillingPolicy.java", "service.billing.ConsultationChargeSource"),
        Map.entry("model/ImagingOrder.java", "service.support.ImagingPreAuthorisationPolicy"),
        Map.entry("model/PatientVitalSign.java", "service.vitals.SevereHypertension"),
        Map.entry("model/Prescription.java", "cdshooks.rules.PediatricDoseRule"),
        Map.entry("model/ProxySensitiveRelease.java", "service.recordaccess.MinorResultReleaseServiceImpl"),
        Map.entry("model/encounter/EncounterNote.java", "service.UnsignedNoteFollowUp"),
        Map.entry("model/labor/DeliveryRecord.java", "service.impl.LaborServiceImpl"),
        Map.entry("model/labor/LaborEpisode.java", "service.impl.LaborServiceImpl"),
        Map.entry("model/medication/MedicationCatalogItem.java", "service.recordaccess.ProxySensitivityPolicy"),
        Map.entry("model/pharmacy/DispenseBillingDeferral.java", "service.pharmacy.DispenseBillingDeferralService"),
        Map.entry("model/safeguarding/SafeguardingReview.java", "service.safeguarding.MinorLabourSafeguarding"),
        Map.entry("payload/dto/LabSensitivityRecommendationDTO.java", "service.recordaccess.RecommendedLabSensitivity"),
        Map.entry("payload/dto/NewbornVitalAlertDTO.java", "service.maternity.NewbornAlertRules"),
        Map.entry("payload/dto/NurseVitalCaptureResponseDTO.java", "service.vitals.SevereHypertension"),
        Map.entry("payload/dto/PatientVitalSignRequestDTO.java", "service.vitals.SevereHypertension"),
        Map.entry("payload/dto/PatientVitalSignResponseDTO.java", "service.vitals.MaternalEarlyWarningRules"),
        Map.entry("payload/dto/PrescriptionRequestDTO.java", "service.impl.RefillApprovalServiceImpl"),
        Map.entry("payload/dto/SignPrescriptionRequestDTO.java", "cdshooks.rules.PediatricDoseRule"),
        Map.entry("payload/dto/TriageSubmissionRequestDTO.java", "service.vitals.SevereHypertension"),
        Map.entry("payload/dto/TriageSubmissionResponseDTO.java", "service.vitals.SevereHypertension"),
        Map.entry("payload/dto/billing/HospitalBillingPolicyDTO.java", "service.billing.ConsultationChargeSource"),
        Map.entry("payload/dto/billing/HospitalBillingPolicyUpdateDTO.java", "service.billing.ConsultationChargeSource"),
        Map.entry("payload/dto/chartreview/ChartReviewDTO.java", "service.support.ImagingPreAuthorisationPolicy"),
        Map.entry("payload/dto/clinical/AfterVisitSummaryDTO.java", "service.UnsignedNoteFollowUp"),
        Map.entry("payload/dto/clinical/UnsignedNoteOverdueDTO.java", "service.UnsignedNoteFollowUp"),
        Map.entry("payload/dto/clinical/labor/DeliveryRecordRequestDTO.java", "service.impl.LaborServiceImpl"),
        Map.entry("payload/dto/clinical/labor/DeliveryRecordResponseDTO.java", "service.maternity.StillbirthCertificateConflicts"),
        Map.entry("payload/dto/clinical/labor/LaborArrivalRequestDTO.java", "service.impl.LaborServiceImpl"),
        Map.entry("payload/dto/clinical/labor/LaborEpisodeResponseDTO.java", "service.impl.LaborServiceImpl"),
        Map.entry("payload/dto/clinical/labor/LaborTransferRequestDTO.java", "service.impl.LaborServiceImpl"),
        Map.entry("payload/dto/clinical/newborn/NewbornAssessmentRequestDTO.java", "service.maternity.NewbornAlertRules"),
        Map.entry("payload/dto/discharge/DischargeSummaryResponseDTO.java", "service.UnsignedNoteFollowUp"),
        Map.entry("payload/dto/imaging/ImagingExamRecordRequestDTO.java", "service.support.ImagingExamRecord"),
        Map.entry("payload/dto/imaging/ImagingOrderAuthorizationRequestDTO.java", "service.support.ImagingPreAuthorisationPolicy"),
        Map.entry("payload/dto/imaging/ImagingOrderRequestDTO.java", "service.imaging.ImagingDuplicateOverride"),
        Map.entry("payload/dto/imaging/ImagingOrderResponseDTO.java", "service.imaging.ImagingDuplicateOverride"),
        Map.entry("payload/dto/imaging/ImagingReportingSettingsDTO.java", "service.imaging.ImagingReportingSettingsService"),
        Map.entry("payload/dto/medication/MedicationCatalogItemRequestDTO.java", "service.recordaccess.ProxySensitivityPolicy"),
        Map.entry("payload/dto/mortality/FetalDeathEventDTO.java", "service.support.StillbirthClassification"),
        Map.entry("payload/dto/mortality/MortalityRegisterDTO.java", "service.support.StillbirthClassification"),
        Map.entry("payload/dto/nurse/NurseVitalCaptureRequestDTO.java", "service.vitals.SevereHypertension"),
        Map.entry("payload/dto/nurse/NurseVitalTaskResponseDTO.java", "service.vitals.MaternalEarlyWarningService"),
        Map.entry("payload/dto/recordaccess/AdolescentConfidentialityAgeDTO.java", "service.recordaccess.ProxySensitivityPolicy"),
        Map.entry("payload/dto/recordaccess/MinorReleaseOverviewDTO.java", "service.recordaccess.MinorResultReleaseServiceImpl"),
        Map.entry("payload/dto/recordaccess/UpdateAdolescentConfidentialityAgeRequestDTO.java", "service.recordaccess.ProxySensitivityPolicy"),
        Map.entry("payload/dto/safeguarding/SafeguardingReviewDTO.java", "service.safeguarding.MinorLabourSafeguarding"),
        Map.entry("repository/DeliveryRecordRepository.java", "service.vitals.SevereHypertension"),
        Map.entry("repository/NewbornAssessmentRepository.java", "service.maternity.StillbornNeonatalAlerts"),
        Map.entry("repository/PatientVitalSignRepository.java", "service.vitals.MaternalEarlyWarningService"),
        Map.entry("security/permission/PermissionCatalog.java", "service.imaging.ImagingRoleScope"),
        Map.entry("seed/RoleSeeder.java", "service.imaging.ImagingRoleScope"),
        Map.entry("service/BedAssignmentService.java", "service.maternity.MidwifeNewbornCare"),
        Map.entry("service/DashboardConfigService.java", "service.imaging.ImagingRoleScope"),
        Map.entry("service/DischargeSummaryServiceImpl.java", "service.UnsignedNoteFollowUp"),
        Map.entry("service/EncounterAutoCompletionService.java", "service.UnsignedNoteFollowUp"),
        Map.entry("service/EncounterServiceImpl.java", "service.UnsignedNoteFollowUp"),
        Map.entry("service/ImagingOrderService.java", "service.support.ImagingPreAuthorisationPolicy"),
        Map.entry("service/LabTestDefinitionService.java", "service.recordaccess.RecommendedLabSensitivity"),
        Map.entry("service/LabTestDefinitionServiceImpl.java", "service.recordaccess.RecommendedLabSensitivity"),
        Map.entry("service/LaborService.java", "service.impl.LaborServiceImpl"),
        Map.entry("service/PatientSubjectReaderRoles.java", "service.imaging.ImagingRoleScope"),
        Map.entry("service/ReceptionServiceImpl.java", "service.UnsignedNoteFollowUp"),
        Map.entry("service/RefillApprovalService.java", "service.impl.RefillApprovalServiceImpl"),
        Map.entry("service/billing/HospitalBillingPolicyService.java", "service.billing.ConsultationChargeSource"),
        Map.entry("service/imaging/ImagingPreAuthorisationFollowUp.java", "service.support.ImagingPreAuthorisationPolicy"),
        Map.entry("service/imaging/ImagingStudyImageService.java", "service.imaging.ImagingRoleScope"),
        Map.entry("service/impl/ImagingOrderServiceImpl.java", "service.support.ImagingPreAuthorisationPolicy"),
        Map.entry("service/impl/ImagingReportServiceImpl.java", "service.support.ImagingPreAuthorisationPolicy"),
        Map.entry("service/impl/MortalityServiceImpl.java", "service.UnsignedNoteFollowUp"),
        Map.entry("service/impl/NewbornAssessmentServiceImpl.java", "service.maternity.StillbornNeonatalAlerts"),
        Map.entry("service/impl/NurseTaskServiceImpl.java", "service.vitals.MaternalEarlyWarningService"),
        Map.entry("service/impl/PatientVitalSignServiceImpl.java", "service.vitals.MaternalEarlyWarningService"),
        Map.entry("service/impl/PostpartumCareServiceImpl.java", "service.vitals.SevereHypertension"),
        Map.entry("service/impl/TransferServiceImpl.java", "service.maternity.MidwifeNewbornCare"),
        Map.entry("service/integration/impl/MllpInboundAdtVisitProjectionServiceImpl.java", "service.safeguarding.MinorLabourSafeguarding"),
        Map.entry("service/maternity/FetalDeathEventService.java", "service.maternity.StillbirthCertificateConflicts"),
        Map.entry("service/maternity/ObstetricAlertCurrency.java", "service.maternity.StillbornNeonatalAlerts"),
        Map.entry("service/maternity/ObstetricAlertEscalationWorker.java", "service.maternity.MidwifeNewbornCare"),
        Map.entry("service/pharmacy/ClaimVouching.java", "service.pharmacy.DispenseBillingDeferralService"),
        Map.entry("service/pharmacy/DeferredFillBalance.java", "service.pharmacy.DispenseBillingDeferralService"),
        Map.entry("service/pharmacy/DispenseBillingDeferralFollowUp.java", "service.pharmacy.DispenseBillingDeferralService"),
        Map.entry("service/pharmacy/FillClaimCover.java", "service.pharmacy.DispenseBillingDeferralService"),
        Map.entry("service/recordaccess/AdolescentConfidentialityAgeService.java", "service.recordaccess.ProxySensitivityPolicy"),
        Map.entry("service/recordaccess/MinorResultReleaseService.java", "service.recordaccess.MinorResultReleaseServiceImpl"),
        Map.entry("service/recordaccess/ProxyRecordFilter.java", "service.recordaccess.ProxySensitivityPolicy"),
        Map.entry("service/registry/ProgramEnrollmentService.java", "utility.ObstetricEligibility"),
        Map.entry("service/safeguarding/SafeguardingReviewService.java", "service.safeguarding.MinorLabourSafeguarding"),
        Map.entry("service/safeguarding/SafeguardingReviewWorker.java", "service.safeguarding.MinorLabourSafeguarding"),
        Map.entry("service/scheduled/UnsignedNoteReminderScheduler.java", "service.UnsignedNoteFollowUp"),
        Map.entry("service/vitals/MaternalWardScope.java", "service.vitals.MaternalEarlyWarningService"),
        Map.entry("service/vitals/MaternityVitalCharting.java", "service.vitals.MaternalEarlyWarningRules"),
        Map.entry("service/vitals/VitalSignJudgement.java", "service.vitals.SevereHypertension"),
        Map.entry("utility/RoleValidator.java", "service.impl.RefillApprovalServiceImpl"));

    /** Files where the word is not a rule marker, with the reason. */
    private static final Map<String, String> NOT_A_RULE = Map.ofEntries(
        Map.entry("config/ImpersonationLimitsWebConfig.java", "the sign-off machinery (V356) speaks of provisional rules; it enforces none"),
        Map.entry("config/SecurityConfig.java", "the sign-off machinery (V356) speaks of provisional rules; it enforces none"),
        Map.entry("controller/HospitalClinicalRulesController.java", "the sign-off machinery (V356) speaks of provisional rules; it enforces none"),
        Map.entry("enums/AllergyVerificationStatus.java", "PROVISIONAL is a clinical verification status of an allergy, not a rule"),
        Map.entry("enums/AuditEventType.java", "audit event names; each event's rule is registered through the service that emits it"),
        Map.entry("enums/RuleSignoffStatus.java", "the sign-off machinery (V356) speaks of provisional rules; it enforces none"),
        Map.entry("model/HospitalRuleSignoff.java", "the sign-off machinery (V356) speaks of provisional rules; it enforces none"),
        Map.entry("payload/dto/HospitalOnboardingReadinessDTO.java", "the sign-off machinery (V356) speaks of provisional rules; it enforces none"),
        Map.entry("payload/dto/clinicalrules/ClinicalRuleStatusDTO.java", "the sign-off machinery (V356) speaks of provisional rules; it enforces none"),
        Map.entry("payload/dto/clinicalrules/ClinicalRulesOverviewDTO.java", "the sign-off machinery (V356) speaks of provisional rules; it enforces none"),
        Map.entry("payload/dto/clinicalrules/RuleSignoffRevokeRequestDTO.java", "the sign-off machinery (V356) speaks of provisional rules; it enforces none"),
        Map.entry("repository/HospitalRuleSignoffRepository.java", "the sign-off machinery (V356) speaks of provisional rules; it enforces none"),
        Map.entry("security/context/HospitalContextRequestOverrides.java", "a provisional (not yet settled) scope refusal, not a clinical rule"),
        Map.entry("security/context/ImpersonationLimits.java", "the sign-off machinery (V356) speaks of provisional rules; it enforces none"),
        Map.entry("security/tenant/ActingScopeResolver.java", "\"provisionally\": a scope set before it is checked, not a clinical rule"),
        Map.entry("service/clinicalrules/ClinicalRuleRegistry.java", "the sign-off machinery (V356) speaks of provisional rules; it enforces none"),
        Map.entry("service/clinicalrules/ClinicalRuleSet.java", "the sign-off machinery (V356) speaks of provisional rules; it enforces none"),
        Map.entry("service/clinicalrules/ClinicalRuleSetKey.java", "the sign-off machinery (V356) speaks of provisional rules; it enforces none"),
        Map.entry("service/clinicalrules/HospitalRuleSignoffService.java", "the sign-off machinery (V356) speaks of provisional rules; it enforces none"),
        Map.entry("service/clinicalrules/ProvisionalRule.java", "the sign-off machinery (V356) speaks of provisional rules; it enforces none"),
        Map.entry("service/clinicalrules/RuleSignoffPackPdfService.java", "the sign-off machinery (V356) speaks of provisional rules; it enforces none"),
        Map.entry("service/maternity/NewbornRegistrationService.java", "a newborn's provisional first name, not a rule"));

    @Test
    @DisplayName("every rule set key has exactly one complete registry entry")
    void everyKeyHasOneCompleteEntry() {
        List<ClinicalRuleSetKey> keys = ClinicalRuleRegistry.all().stream().map(ClinicalRuleSet::key).toList();
        assertThat(keys).doesNotHaveDuplicates();
        assertThat(EnumSet.copyOf(keys)).containsExactlyInAnyOrderElementsOf(EnumSet.allOf(ClinicalRuleSetKey.class));

        org.springframework.context.MessageSource bundles = com.example.hms.i18n.TestMessageSources.bundles();
        List<String> incomplete = new ArrayList<>();
        for (ClinicalRuleSet rule : ClinicalRuleRegistry.all()) {
            for (String key : List.of(rule.titleKey(), rule.descriptionKey(), rule.sourceKey(), rule.approverKey())) {
                Map<String, String> byLanguage = new TreeMap<>();
                for (String language : List.of("en", "fr", "es")) {
                    String text = bundles.getMessage(key, null, null, java.util.Locale.forLanguageTag(language));
                    if (blank(text)) {
                        incomplete.add(rule.key() + ": " + key + " has no " + language + " text");
                    }
                    byLanguage.put(language, text);
                }
                // A citation may read the same in every language (« Vousden et al. »); the texts may not.
                if (!key.equals(rule.sourceKey()) && byLanguage.get("fr") != null
                    && byLanguage.get("fr").equals(byLanguage.get("en"))) {
                    incomplete.add(rule.key() + ": " + key + " is the English text in French");
                }
            }
            if (blank(rule.version()) || rule.version().length() > 64) {
                incomplete.add(rule.key() + ": no version (or longer than the 64 characters V356 stores)");
            }
            if (rule.enforcedIn().isEmpty()) {
                incomplete.add(rule.key() + ": names no enforcing class");
            }
        }
        assertThat(incomplete).as("registry entries missing a field").isEmpty();
    }

    @Test
    @DisplayName("registry to code: every class an entry names carries @ProvisionalRule with that key")
    void everyNamedClassCarriesTheAnnotation() {
        List<String> problems = new ArrayList<>();
        for (ClinicalRuleSet rule : ClinicalRuleRegistry.all()) {
            for (String name : rule.enforcedIn()) {
                Set<ClinicalRuleSetKey> keys = annotatedKeys(name);
                if (keys == null) {
                    problems.add(rule.key() + " names " + name + ", which does not exist");
                } else if (!keys.contains(rule.key())) {
                    problems.add(rule.key() + " names " + name + ", which does not carry @ProvisionalRule("
                        + rule.key() + ")");
                }
            }
        }
        assertThat(problems).isEmpty();
    }

    @Test
    @DisplayName("code to registry: every class carrying @ProvisionalRule is named by each of its keys' entries")
    void everyAnnotatedClassIsInTheRegistry() throws IOException {
        Map<String, List<ClinicalRuleSetKey>> annotated = annotatedClassesInSource();
        assertThat(annotated).as("no @ProvisionalRule found: the scan is broken").isNotEmpty();
        List<String> problems = new ArrayList<>();
        annotated.forEach((name, keys) -> {
            for (ClinicalRuleSetKey key : keys) {
                ClinicalRuleSet rule = ClinicalRuleRegistry.find(key).orElseThrow();
                if (!rule.enforcedIn().contains(name)) {
                    problems.add(name + " carries @ProvisionalRule(" + key + ") but the registry entry does not name it");
                }
            }
        });
        assertThat(problems).isEmpty();
    }

    @Test
    @DisplayName("every file that says provisional (any case) or « provisoire » is a rule's code, or names the code that is")
    void everyProvisionalMarkerHasItsRuleSet() throws IOException {
        Set<String> annotatedFiles = annotatedFiles();
        List<String> unregistered = new ArrayList<>();
        Set<String> seenMentions = new java.util.TreeSet<>();
        for (Path file : javaFiles()) {
            String relative = MAIN.relativize(file).toString().replace('\\', '/');
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            boolean marked = PROVISIONAL_MARKER.matcher(raw).find();
            if (!marked || annotatedFiles.contains(relative) || NOT_A_RULE.containsKey(relative)) {
                continue;
            }
            String owner = MENTIONS.get(relative);
            if (owner == null) {
                unregistered.add(relative + ": says provisional (or « provisoire ») without @ProvisionalRule;"
                    + " annotate the rule's class and add it to ClinicalRuleRegistry, or list this file in MENTIONS");
                continue;
            }
            seenMentions.add(relative);
            Set<ClinicalRuleSetKey> ownerKeys = annotatedKeys(owner);
            if (ownerKeys == null || ownerKeys.isEmpty()) {
                unregistered.add(relative + ": names " + owner + " as its rule's code, which carries no @ProvisionalRule");
            }
        }
        assertThat(unregistered).isEmpty();
        assertThat(seenMentions).as("a MENTIONS entry whose file no longer says PROVISIONAL: remove it")
            .containsExactlyInAnyOrderElementsOf(MENTIONS.keySet());
        for (String file : NOT_A_RULE.keySet()) {
            assertThat(Files.exists(MAIN.resolve(file))).as("a NOT_A_RULE entry whose file is gone: " + file).isTrue();
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /** The keys of {@code name}'s annotation, an empty set when it has none, null when the class does not exist. */
    private static Set<ClinicalRuleSetKey> annotatedKeys(String name) {
        try {
            Class<?> type = Class.forName(ClinicalRuleSet.BASE_PACKAGE + name);
            ProvisionalRule annotation = type.getAnnotation(ProvisionalRule.class);
            return annotation == null ? Set.of() : Set.copyOf(Arrays.asList(annotation.value()));
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    /** Every main class whose source carries {@code @ProvisionalRule(...)}, by name from com.example.hms. */
    private static Map<String, List<ClinicalRuleSetKey>> annotatedClassesInSource() throws IOException {
        Map<String, List<ClinicalRuleSetKey>> annotated = new TreeMap<>();
        for (Path file : javaFiles()) {
            String code = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            Matcher annotation = ANNOTATION.matcher(code);
            if (!annotation.find()) {
                continue;
            }
            List<ClinicalRuleSetKey> keys = new ArrayList<>();
            Matcher key = KEY.matcher(annotation.group(1));
            while (key.find()) {
                keys.add(ClinicalRuleSetKey.valueOf(key.group(1)));
            }
            String relative = MAIN.relativize(file).toString().replace('\\', '/');
            annotated.put(relative.substring(0, relative.length() - ".java".length()).replace('/', '.'), keys);
        }
        return annotated;
    }

    private static Set<String> annotatedFiles() throws IOException {
        Set<String> files = new java.util.TreeSet<>();
        for (String name : annotatedClassesInSource().keySet()) {
            files.add(name.replace('.', '/') + ".java");
        }
        return files;
    }

    private static List<Path> javaFiles() throws IOException {
        try (Stream<Path> paths = Files.walk(MAIN)) {
            return paths.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        }
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }
}
