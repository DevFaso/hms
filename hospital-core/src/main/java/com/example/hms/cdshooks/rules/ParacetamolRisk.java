package com.example.hms.cdshooks.rules;

import com.example.hms.enums.ProblemSeverity;
import com.example.hms.model.PatientProblem;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The groups with a lower paracetamol limit (EFFERALGANSTIX 1000 mg, RCP 4.2 and 4.3), read only
 * from an ICD-10 code on a problem row: the problem list's code, its one reliable vocabulary
 * ({@code ProblemLoincBindings}). Never from the free-text display, the ICD-11 list (no mapping
 * was supplied) or the social history ("drinks per week" is not a diagnosis of chronic alcoholism).
 *
 * <p>The code-to-group mapping is proposed for clinician sign-off:
 * <ul>
 *   <li>malnutrition: E40 to E46. ICD-10 does not say "chronic", so any coded malnutrition counts;</li>
 *   <li>dehydration: E86, without E86.1 (hypovolaemia, in the ICD-10-CM subdivisions);</li>
 *   <li>Gilbert syndrome: E80.4. Chronic alcoholism: F10.2, alcohol dependence;</li>
 *   <li>hepatocellular insufficiency: K72, K70.4, K71.1. Its degree is the row's severity:
 *       SEVERE and LIFE_THREATENING are severe, MILD and MODERATE are not, and no recorded
 *       degree takes the mild-to-moderate limit and says that severe would contraindicate.</li>
 * </ul>
 */
public enum ParacetamolRisk {
    /** Chronic malnutrition or dehydration on the problem list: 60 mg/kg/day or 3,000 mg/day. */
    MALNUTRITION_OR_DEHYDRATION,
    /** Mild to moderate hepatocellular insufficiency, Gilbert syndrome or chronic alcoholism: 2,000 mg/day. */
    TWO_GRAM_GROUP,
    /** Chronic alcoholism: at least 8 hours between doses. */
    CHRONIC_ALCOHOLISM,
    /** Severe hepatocellular insufficiency: contraindicated. */
    SEVERE_HEPATIC,
    /** Hepatocellular insufficiency with no degree recorded. */
    HEPATIC_DEGREE_UNRECORDED;

    private static final Set<String> MALNUTRITION = Set.of("E40", "E41", "E42", "E43", "E44", "E45", "E46");
    private static final String DEHYDRATION = "E86";
    private static final String HYPOVOLAEMIA = "E861";
    private static final String GILBERT = "E804";
    private static final String ALCOHOL_DEPENDENCE = "F102";
    private static final List<String> HEPATIC = List.of("K72", "K704", "K711");

    /** The groups these problem rows put the patient in. The caller passes active rows only. */
    static Set<ParacetamolRisk> of(Collection<PatientProblem> activeProblems) {
        Set<ParacetamolRisk> risks = EnumSet.noneOf(ParacetamolRisk.class);
        for (PatientProblem problem : activeProblems) {
            if (!isIcd11(problem.getIcdVersion())) {
                addFor(normalized(problem.getProblemCode()), problem.getSeverity(), risks);
                otherCodesOf(problem).forEach(code -> addFor(code, null, risks));
            }
        }
        return risks;
    }

    /** A row's severity is its own problem's: a secondary code's degree is not recorded. */
    private static Stream<String> otherCodesOf(PatientProblem problem) {
        List<String> others = problem.getDiagnosisCodes() == null ? List.of() : problem.getDiagnosisCodes();
        return others.stream().map(ParacetamolRisk::normalized);
    }

    private static String normalized(String code) {
        return code == null ? "" : code.trim().replace(".", "").toUpperCase(Locale.ROOT);
    }

    private static void addFor(String code, ProblemSeverity severity, Set<ParacetamolRisk> risks) {
        if (code.isEmpty()) {
            return;
        }
        boolean malnutrition = code.length() >= 3 && MALNUTRITION.contains(code.substring(0, 3));
        boolean dehydration = code.startsWith(DEHYDRATION) && !code.startsWith(HYPOVOLAEMIA);
        if (malnutrition || dehydration) {
            risks.add(MALNUTRITION_OR_DEHYDRATION);
        } else if (code.startsWith(GILBERT)) {
            risks.add(TWO_GRAM_GROUP);
        } else if (code.startsWith(ALCOHOL_DEPENDENCE)) {
            risks.add(TWO_GRAM_GROUP);
            risks.add(CHRONIC_ALCOHOLISM);
        } else if (HEPATIC.stream().anyMatch(code::startsWith)) {
            addHepatic(severity, risks);
        }
    }

    private static void addHepatic(ProblemSeverity severity, Set<ParacetamolRisk> risks) {
        if (severity == ProblemSeverity.SEVERE || severity == ProblemSeverity.LIFE_THREATENING) {
            risks.add(SEVERE_HEPATIC);
            return;
        }
        risks.add(TWO_GRAM_GROUP);
        if (severity != ProblemSeverity.MILD && severity != ProblemSeverity.MODERATE) {
            risks.add(HEPATIC_DEGREE_UNRECORDED);
        }
    }

    /** The version text the chart's diagnosis form validates against, read the same way. */
    private static boolean isIcd11(String icdVersion) {
        if (icdVersion == null) {
            return false;
        }
        String version = icdVersion.toUpperCase(Locale.ROOT);
        return version.contains("11") || version.contains("MMS");
    }
}
