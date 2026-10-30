package com.example.hms.immunization;

import com.example.hms.fhir.mapper.ImmunizationFhirMapper;
import com.example.hms.mapper.ImmunizationMapper;
import com.example.hms.model.PatientImmunization;
import com.example.hms.payload.dto.medicalhistory.ImmunizationRequestDTO;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1003 (round 17): the CVX code of a vaccine is optional. CVX is a US
 * code system; a Burkina Faso EPI (PEV) vaccine may have none (MenAfriVac,
 * malaria), and a transcribed dose may come from a card that never named one.
 *
 * <p>Class: "a vaccine code treated as mandatory somewhere on the write or
 * read path". The guard scans every payload class for a field that carries a
 * vaccine/CVX code and fails when one is annotated as required, so a new
 * immunisation DTO (a transcription, a portal self-report, a bulk import)
 * cannot reintroduce the rule. The rest pins the choke points every write
 * path goes through: the mapper (create and edit) and the FHIR export.
 */
class ImmunizationCvxOptionalGuardTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    private static final Set<String> CODE_FIELD_NAMES = Set.of("vaccinecode", "cvx", "cvxcode");

    @Test
    void noPayloadMakesAVaccineCodeMandatory() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter((reader, readerFactory) -> true);
        List<String> scanned = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("com.example.hms.payload")) {
            Class<?> type = Class.forName(candidate.getBeanClassName());
            for (Field field : type.getDeclaredFields()) {
                if (!CODE_FIELD_NAMES.contains(field.getName().toLowerCase(Locale.ROOT))) {
                    continue;
                }
                scanned.add(type.getSimpleName() + "." + field.getName());
                if (field.isAnnotationPresent(NotBlank.class) || field.isAnnotationPresent(NotNull.class)
                        || field.isAnnotationPresent(NotEmpty.class)) {
                    violations.add(type.getName() + "." + field.getName());
                }
            }
        }
        // The scan must see the request DTO, or it proves nothing.
        assertThat(scanned).contains("ImmunizationRequestDTO.vaccineCode");
        assertThat(violations)
            .as("QA D1003: a CVX/vaccine code is optional on every payload")
            .isEmpty();
    }

    @Test
    void aDoseWithoutCodeIsAValidRequest() {
        ImmunizationRequestDTO dto = ImmunizationRequestDTO.builder()
            .patientId(java.util.UUID.randomUUID())
            .hospitalId(java.util.UUID.randomUUID())
            .vaccineDisplay("Méningite A (MenAfriVac)")
            .administrationDate(LocalDate.of(2021, 3, 1))
            .status("COMPLETED")
            .build();

        assertThat(validator.validate(dto))
            .extracting(v -> v.getPropertyPath().toString())
            .doesNotContain("vaccineCode");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void createAndEditStoreNoCodeAsNull(String code) {
        ImmunizationMapper mapper = new ImmunizationMapper();
        ImmunizationRequestDTO dto = ImmunizationRequestDTO.builder()
            .vaccineCode(code)
            .vaccineDisplay("VAA (fièvre jaune)")
            .administrationDate(LocalDate.of(2024, 1, 10))
            .status("COMPLETED")
            .build();

        PatientImmunization created = mapper.toEntity(dto, null, null, null, null);
        assertThat(created.getVaccineCode()).isNull();

        PatientImmunization edited = PatientImmunization.builder().vaccineCode("19").build();
        mapper.updateEntity(edited, dto);
        assertThat(edited.getVaccineCode()).isNull();
    }

    @Test
    void aTypedCodeIsKeptTrimmed() {
        ImmunizationMapper mapper = new ImmunizationMapper();
        ImmunizationRequestDTO dto = ImmunizationRequestDTO.builder()
            .vaccineCode(" 184 ")
            .vaccineDisplay("VAA (fièvre jaune)")
            .build();

        assertThat(mapper.toEntity(dto, null, null, null, null).getVaccineCode()).isEqualTo("184");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  "})
    void fhirExportOfADoseWithoutCodeOrTypeIsTextOnly(String blank) {
        PatientImmunization dose = PatientImmunization.builder()
            .vaccineCode(blank)
            .vaccineType(blank)
            .vaccineDisplay("Vaccin antipaludique")
            .administrationDate(LocalDate.of(2025, 5, 2))
            .build();

        CodeableConcept concept = new ImmunizationFhirMapper().toFhir(dose).getVaccineCode();

        assertThat(concept.getText()).isEqualTo("Vaccin antipaludique");
        assertThat(concept.getCoding()).isEmpty();
    }

    @Test
    void fhirExportFallsBackToTheLocalTypeWithoutCode() {
        PatientImmunization dose = PatientImmunization.builder()
            .vaccineType("MENA")
            .vaccineDisplay("Méningite A (MenAfriVac)")
            .build();

        CodeableConcept concept = new ImmunizationFhirMapper().toFhir(dose).getVaccineCode();

        assertThat(concept.getCoding()).singleElement()
            .satisfies(c -> {
                assertThat(c.getSystem()).isEqualTo("urn:hms:vaccine:code");
                assertThat(c.getCode()).isEqualTo("MENA");
            });
    }

    @Test
    void fhirExportUsesTheCvxSystemWhenACodeIsPresent() {
        PatientImmunization dose = PatientImmunization.builder()
            .vaccineCode("19")
            .vaccineDisplay("BCG (tuberculose)")
            .build();

        CodeableConcept concept = new ImmunizationFhirMapper().toFhir(dose).getVaccineCode();

        assertThat(concept.getCoding()).singleElement()
            .satisfies(c -> {
                assertThat(c.getSystem()).isEqualTo("http://hl7.org/fhir/sid/cvx");
                assertThat(c.getCode()).isEqualTo("19");
            });
    }
}
