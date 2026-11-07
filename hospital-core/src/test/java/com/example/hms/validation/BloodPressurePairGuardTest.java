package com.example.hms.validation;

import com.example.hms.payload.dto.PatientVitalSignRequestDTO;
import com.example.hms.payload.dto.TriageSubmissionRequestDTO;
import com.example.hms.payload.dto.VitalSignLimits;
import com.example.hms.payload.dto.clinical.labor.PartographEntryRequestDTO;
import com.example.hms.payload.dto.clinical.newborn.NewbornAssessmentRequestDTO;
import com.example.hms.payload.dto.clinical.postpartum.PostpartumObservationRequestDTO;
import com.example.hms.payload.dto.highrisk.HighRiskBloodPressureLogRequestDTO;
import com.example.hms.payload.dto.nurse.NurseVitalCaptureRequestDTO;
import com.example.hms.payload.dto.portal.HomeVitalReadingDTO;
import com.example.hms.testsupport.SourceScan;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.RegexPatternTypeFilter;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1424: the vitals API refused 70/120 and the partograph, post-partum and
 * newborn forms saved it; the partograph then raised "severe hypertension". Every
 * request that takes a systolic and a diastolic goes through the one rule
 * ({@link VitalSignLimits#bloodPressureOrdered}) and bounds both.
 */
@DisplayName("D1424: every request with a blood-pressure pair applies the one rule")
class BloodPressurePairGuardTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    /** Payloads that only carry a reading out to a reader, and the Lombok builders of the rest. */
    private static final Pattern READ_ONLY = Pattern.compile(".*(?:Response(?:DTO)?(?:[$].*)?|Builder)");

    private static final int EXPECTED_AT_LEAST = 8;

    private static Field fieldNamed(Class<?> type, String part) {
        for (Field f : type.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(f.getModifiers()) && f.getName().toLowerCase(Locale.ROOT).contains(part)
                && (f.getType() == Integer.class || f.getType() == int.class)) {
                return f;
            }
        }
        return null;
    }

    @Test
    @DisplayName("a request with both pressures bounds them and refuses a reversed pair")
    void everyPairIsChecked() throws ReflectiveOperationException {
        ClassPathScanningCandidateComponentProvider scan = new ClassPathScanningCandidateComponentProvider(false);
        scan.addIncludeFilter(new RegexPatternTypeFilter(Pattern.compile(".*")));
        List<String> problems = new ArrayList<>();
        int pairs = 0;
        for (BeanDefinition candidate : scan.findCandidateComponents("com.example.hms.payload")) {
            String name = candidate.getBeanClassName();
            if (READ_ONLY.matcher(name).matches()) {
                continue;
            }
            Class<?> type = Class.forName(name);
            Field systolic = fieldNamed(type, "systolic");
            Field diastolic = fieldNamed(type, "diastolic");
            if (systolic == null || diastolic == null) {
                continue;
            }
            pairs++;
            problems.addAll(problemsOf(type, systolic, diastolic));
        }
        assertThat(problems).as(SourceScan.MANDATE).isEmpty();
        assertThat(pairs).as("the scan found the requests with a pair").isGreaterThanOrEqualTo(EXPECTED_AT_LEAST);
    }

    private static List<String> problemsOf(Class<?> type, Field systolic, Field diastolic) {
        List<String> problems = new ArrayList<>();
        String name = type.getSimpleName();
        try {
            Method rule = type.getMethod("isBloodPressureOrdered");
            if (!rule.isAnnotationPresent(AssertTrue.class) || !rule.isAnnotationPresent(ReportedOn.class)) {
                problems.add(name + ": the pair rule needs @AssertTrue and @ReportedOn");
            }
        } catch (NoSuchMethodException e) {
            problems.add(name + ": no isBloodPressureOrdered() (VitalSignLimits.bloodPressureOrdered)");
        }
        for (Field f : List.of(systolic, diastolic)) {
            boolean isSystolic = f == systolic;
            Min min = f.getAnnotation(Min.class);
            Max max = f.getAnnotation(Max.class);
            if (type == NewbornAssessmentRequestDTO.class) {
                // Refuses only what cannot exist; a high value saves, flagged (NewbornAlertRules).
                if (f.getAnnotation(Min.class) == null || f.getAnnotation(Min.class).value() != 0) {
                    problems.add(name + "." + f.getName() + ": a negative pressure is refused");
                }
                continue;
            }
            int floor = isSystolic ? VitalSignLimits.SYSTOLIC_MIN : VitalSignLimits.DIASTOLIC_MIN;
            int ceiling = isSystolic ? VitalSignLimits.SYSTOLIC_MAX : VitalSignLimits.DIASTOLIC_MAX;
            if (min == null || max == null || min.value() < floor || max.value() > ceiling) {
                problems.add(name + "." + f.getName() + ": bounded within VitalSignLimits (" + floor + "-" + ceiling + ")");
            }
        }
        return problems;
    }

    @Test
    @DisplayName("70/120 is refused on the diastolic everywhere, 120/70 and a pressure alone pass")
    void theRuleBehavesOnEveryForm() {
        assertThat(rejected(PartographEntryRequestDTO.builder().systolicBpMmHg(70).diastolicBpMmHg(120).build()))
            .contains("bloodPressureOrdered");
        assertThat(rejected(NewbornAssessmentRequestDTO.builder().systolicBpMmHg(40).diastolicBpMmHg(90).build()))
            .contains("bloodPressureOrdered");
        PostpartumObservationRequestDTO postpartum = new PostpartumObservationRequestDTO();
        postpartum.setSystolicBpMmHg(70);
        postpartum.setDiastolicBpMmHg(120);
        assertThat(rejected(postpartum)).contains("bloodPressureOrdered");
        HighRiskBloodPressureLogRequestDTO log = new HighRiskBloodPressureLogRequestDTO();
        log.setReadingDate(java.time.LocalDate.now(java.time.ZoneOffset.UTC));
        log.setSystolic(70);
        log.setDiastolic(120);
        assertThat(rejected(log)).contains("bloodPressureOrdered");

        assertThat(rejected(PartographEntryRequestDTO.builder().systolicBpMmHg(120).diastolicBpMmHg(70).build())).isEmpty();
        assertThat(rejected(PartographEntryRequestDTO.builder().systolicBpMmHg(170).build())).isEmpty();
        assertThat(rejected(NewbornAssessmentRequestDTO.builder().diastolicBpMmHg(40).build())).isEmpty();
    }

    @ParameterizedTest(name = "{0}={1}")
    @CsvSource({
        "heartRateBpm,-1", "respirationsPerMin,-1", "oxygenSaturationPercent,150", "oxygenSaturationPercent,101",
        "oxygenSaturationPercent,-1", "glucoseMgDl,-5", "systolicBpMmHg,-1", "diastolicBpMmHg,-1"
    })
    @DisplayName("the newborn form refuses only what cannot exist by definition")
    void theNewbornFormBoundsItsVitals(String field, int value) throws ReflectiveOperationException {
        NewbornAssessmentRequestDTO dto = new NewbornAssessmentRequestDTO();
        Field f = NewbornAssessmentRequestDTO.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(dto, value);
        assertThat(rejected(dto)).contains(field);
    }

    @Test
    @DisplayName("a newborn's real extremes and a high value save, so the alert and the flag can fire")
    void realNewbornValuesPass() {
        assertThat(rejected(NewbornAssessmentRequestDTO.builder().heartRateBpm(1000).respirationsPerMin(200)
            .glucoseMgDl(900).oxygenSaturationPercent(100).systolicBpMmHg(400).diastolicBpMmHg(300).build())).isEmpty();
        assertThat(rejected(NewbornAssessmentRequestDTO.builder().glucoseValue(0.0).glucoseUnit(com.example.hms.enums.GlucoseUnit.MG_DL).build())).isEmpty();
        assertThat(rejected(NewbornAssessmentRequestDTO.builder().glucoseValue(-0.1).build())).contains("glucoseValue");
        assertThat(rejected(NewbornAssessmentRequestDTO.builder().heartRateBpm(0).respirationsPerMin(0)
            .oxygenSaturationPercent(40).glucoseMgDl(18).build())).isEmpty();
        assertThat(rejected(NewbornAssessmentRequestDTO.builder().heartRateBpm(250).respirationsPerMin(85)
            .glucoseMgDl(0).systolicBpMmHg(35).diastolicBpMmHg(18).build())).isEmpty();
    }

    @Test
    @DisplayName("a glucose value with no unit is refused; the mg/dL field names its unit")
    void aGlucoseValueNeedsItsUnit() {
        assertThat(rejected(NewbornAssessmentRequestDTO.builder().glucoseValue(5.6).build())).contains("glucoseUnitGiven");
        assertThat(rejected(NewbornAssessmentRequestDTO.builder().glucoseValue(5.6)
            .glucoseUnit(com.example.hms.enums.GlucoseUnit.MMOL_L).build())).isEmpty();
        assertThat(rejected(NewbornAssessmentRequestDTO.builder().glucoseMgDl(18).build())).isEmpty();
    }

    @Test
    @DisplayName("the partograph pulse and pressures sit in the same bounds as the vitals API")
    void thePartographUsesTheSameBounds() {
        assertThat(rejected(PartographEntryRequestDTO.builder().pulseBpm(0).build())).contains("pulseBpm");
        assertThat(rejected(PartographEntryRequestDTO.builder().systolicBpMmHg(320).build())).contains("systolicBpMmHg");
        assertThat(rejected(PartographEntryRequestDTO.builder().diastolicBpMmHg(10).build())).contains("diastolicBpMmHg");
    }

    @Test
    @DisplayName("the forms the rule already covered keep it")
    void theExistingFormsAreStillInTheScan() {
        assertThat(List.of(PatientVitalSignRequestDTO.class, NurseVitalCaptureRequestDTO.class,
            TriageSubmissionRequestDTO.class, HomeVitalReadingDTO.class))
            .allSatisfy(type -> assertThat(type.getMethod("isBloodPressureOrdered")).isNotNull());
    }

    private static Set<String> rejected(Object dto) {
        Set<String> paths = new java.util.TreeSet<>();
        VALIDATOR.validate(dto).forEach(v -> paths.add(v.getPropertyPath().toString()));
        return paths;
    }
}
