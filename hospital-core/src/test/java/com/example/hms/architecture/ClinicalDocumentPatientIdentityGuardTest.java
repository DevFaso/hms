package com.example.hms.architecture;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.RegexPatternTypeFilter;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1923 (r53): the imaging results list and report drawer named no
 * patient. A report was revised and signed with no name or MRN on screen,
 * because its response carried only the order id.
 *
 * <p>Guard: every report or result response a clinician reads in a list
 * (each {@code *ReportResponseDTO} / {@code *ResultResponseDTO} under
 * {@code payload.dto}) names its patient: an id and a display name. A
 * response read only inside one patient's own record is listed below with
 * why, so a new document type has to decide.
 */
class ClinicalDocumentPatientIdentityGuardTest {

    private static final String DTO_PACKAGE = "com.example.hms.payload.dto";
    private static final Pattern DOCUMENT_RESPONSE = Pattern.compile(".*(Report|Result)ResponseDTO");

    private static final Set<String> PATIENT_ID = Set.of("patientId");
    private static final Set<String> PATIENT_NAME = Set.of("patientDisplayName", "patientFullName", "patientName");

    /** Responses that never leave one patient's own record. */
    private static final Map<String, String> PATIENT_CONTEXT_ONLY = Map.of(
        "PatientLabResultResponseDTO", "the patient portal's own results: the reader is the patient",
        "UltrasoundReportResponseDTO", "read inside one pregnancy's record (maternity ultrasound tab)");

    @Test
    void everyClinicalDocumentResponseNamesItsPatient() throws ClassNotFoundException {
        List<String> missing = new ArrayList<>();
        for (Class<?> dto : documentResponses()) {
            if (PATIENT_CONTEXT_ONLY.containsKey(dto.getSimpleName())) {
                continue;
            }
            Set<String> fields = Arrays.stream(dto.getDeclaredFields()).map(Field::getName)
                .collect(Collectors.toSet());
            boolean id = fields.stream().anyMatch(PATIENT_ID::contains);
            boolean name = fields.stream().anyMatch(PATIENT_NAME::contains);
            if (!id || !name) {
                missing.add(dto.getName() + (id ? "" : " (no patientId)") + (name ? "" : " (no patient name)"));
            }
        }
        assertThat(missing)
            .as("a report or result read in a list must say whose it is (QA D1923)")
            .isEmpty();
    }

    /** The scan sees the shape: the imaging report is one of the documents it checks. */
    @Test
    void theScanFindsTheDocumentResponses() throws ClassNotFoundException {
        Set<String> names = new TreeSet<>();
        for (Class<?> dto : documentResponses()) {
            names.add(dto.getSimpleName());
        }
        assertThat(names).contains("ImagingReportResponseDTO", "LabResultResponseDTO");
        assertThat(names).containsAll(PATIENT_CONTEXT_ONLY.keySet());
    }

    private static List<Class<?>> documentResponses() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new RegexPatternTypeFilter(DOCUMENT_RESPONSE));
        List<Class<?>> found = new ArrayList<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents(DTO_PACKAGE)) {
            found.add(Class.forName(candidate.getBeanClassName()));
        }
        return found;
    }
}
