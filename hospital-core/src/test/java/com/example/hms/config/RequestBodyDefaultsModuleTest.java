package com.example.hms.config;

import com.example.hms.payload.dto.DepartmentRequestDTO;
import com.example.hms.payload.dto.PatientRequestDTO;
import com.example.hms.payload.dto.medication.MedicationCatalogItemRequestDTO;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.beans.Introspector;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * QA D23: a request body that sends {@code null} for a primitive boolean is
 * accepted and the property keeps its declared default. Covers every request
 * DTO under {@code payload.dto} that has one, so a new DTO is checked the day
 * it is added.
 */
class RequestBodyDefaultsModuleTest {

    private static final JsonMapper WITH_MODULE = JsonMapper.builder()
        .addModule(new RequestBodyDefaultsModule())
        .build();

    private static final JsonMapper PLAIN = JsonMapper.builder().build();

    @Test
    @DisplayName("without the module, Jackson 3 refuses the patient form's null isActive (the defect)")
    void plainJackson3RefusesNullForPrimitive() {
        assertThatThrownBy(() -> PLAIN.readValue("{\"isActive\":null}", PatientRequestDTO.class))
            .hasMessageContaining("null");
    }

    @Test
    @DisplayName("without the module, Jackson 3 refuses a department body that leaves active out (the defect)")
    void plainJackson3RefusesOmittedPrimitive() {
        assertThatThrownBy(() -> PLAIN.readValue("{\"name\":\"Cardiologie\"}", DepartmentRequestDTO.class))
            .hasMessageContaining("null");
    }

    @Test
    @DisplayName("a body that leaves the booleans out keeps every declared default")
    void omittedFieldsKeepDefaults() {
        DepartmentRequestDTO dept = WITH_MODULE.readValue("{\"name\":\"Cardiologie\"}", DepartmentRequestDTO.class);
        assertThat(dept.isActive()).isTrue();
        assertThat(dept.getName()).isEqualTo("Cardiologie");
        assertThat(WITH_MODULE.readValue("{}", PatientRequestDTO.class).isActive()).isTrue();
        assertThat(WITH_MODULE.readValue("{}", MedicationCatalogItemRequestDTO.class).isActive()).isTrue();
    }

    @Test
    @DisplayName("patient registration: a null isActive keeps the default true")
    void patientKeepsDefault() {
        PatientRequestDTO dto = WITH_MODULE.readValue(
            "{\"isActive\":null,\"active\":null,\"firstName\":\"Awa\"}", PatientRequestDTO.class);
        assertThat(dto.isActive()).isTrue();
        assertThat(dto.getFirstName()).isEqualTo("Awa");
    }

    @Test
    @DisplayName("department: a null active keeps the default true, an explicit false still wins")
    void departmentKeepsDefault() {
        assertThat(WITH_MODULE.readValue("{\"active\":null}", DepartmentRequestDTO.class).isActive()).isTrue();
        assertThat(WITH_MODULE.readValue("{\"active\":false}", DepartmentRequestDTO.class).isActive()).isFalse();
    }

    @Test
    @DisplayName("medication catalog: null active, essentialList and controlled keep their defaults")
    void catalogKeepsDefaults() {
        MedicationCatalogItemRequestDTO dto = WITH_MODULE.readValue(
            "{\"active\":null,\"essentialList\":null,\"controlled\":null}", MedicationCatalogItemRequestDTO.class);
        assertThat(dto.isActive()).isTrue();
        assertThat(dto.isEssentialList()).isFalse();
        assertThat(dto.isControlled()).isFalse();
    }

    @Test
    @DisplayName("record bodies: a null or missing flag takes the documented default")
    void recordFlagsTakeTheirDefaults() {
        assertThat(WITH_MODULE.readValue("{\"skipConflicts\":null}",
            com.example.hms.payload.dto.BulkShiftRequestDTO.class).skipConflicts()).isTrue();
        assertThat(WITH_MODULE.readValue("{}",
            com.example.hms.payload.dto.BulkShiftRequestDTO.class).skipConflicts()).isTrue();
        assertThat(WITH_MODULE.readValue("{\"attachPdf\":null}",
            com.example.hms.payload.dto.EmailInvoiceRequest.class).attachPdf()).isFalse();
        assertThat(WITH_MODULE.readValue("{\"dayOff\":null}",
            com.example.hms.payload.dto.StaffAvailabilityRequestDTO.class).dayOff()).isFalse();
        assertThat(WITH_MODULE.readValue("{\"requiresCoverage\":null}",
            com.example.hms.payload.dto.StaffLeaveRequestDTO.class).requiresCoverage()).isFalse();
    }

    static Stream<Class<?>> requestDtosWithPrimitiveBooleans() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
                return definition.getMetadata().isConcrete();
            }
        };
        scanner.addIncludeFilter((reader, factory) -> {
            String name = reader.getClassMetadata().getClassName();
            return name.contains("Request") && !name.endsWith("Builder");
        });
        List<Class<?>> found = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("com.example.hms.payload.dto")) {
            Class<?> type = Class.forName(definition.getBeanClassName());
            if (!primitiveBooleanFields(type).isEmpty()) {
                found.add(type);
            }
        }
        assertThat(found).as("the scan must find the DTOs QA D23 named").contains(
            PatientRequestDTO.class, DepartmentRequestDTO.class, MedicationCatalogItemRequestDTO.class);
        return found.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("requestDtosWithPrimitiveBooleans")
    @DisplayName("every request DTO: null for a primitive boolean is accepted and keeps the default")
    void everyRequestDtoKeepsItsDefaults(Class<?> type) throws Exception {
        Object baseline = WITH_MODULE.readValue("{}", type);
        ObjectNode body = WITH_MODULE.createObjectNode();
        for (Field field : primitiveBooleanFields(type)) {
            body.putNull(jsonName(field, WITH_MODULE.valueToTree(baseline)));
        }

        Object parsed = WITH_MODULE.treeToValue(body, type);

        for (Field field : primitiveBooleanFields(type)) {
            field.setAccessible(true);
            assertThat(field.getBoolean(parsed))
                .as("%s.%s after a null", type.getSimpleName(), field.getName())
                .isEqualTo(field.getBoolean(baseline));
        }
    }

    private static List<Field> primitiveBooleanFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            if (field.getType() == boolean.class && !Modifier.isStatic(field.getModifiers())
                    && !field.isSynthetic() && field.getName().indexOf('$') < 0) {
                fields.add(field);
            }
        }
        return fields;
    }

    /** The JSON key Jackson uses for this field: its @JsonProperty, or the bean name the serialiser wrote. */
    private static String jsonName(Field field, JsonNode serialized) {
        JsonProperty property = field.getAnnotation(JsonProperty.class);
        if (property != null && !property.value().isEmpty()) {
            return property.value();
        }
        String name = field.getName();
        if (serialized.has(name)) {
            return name;
        }
        if (name.startsWith("is") && name.length() > 2 && Character.isUpperCase(name.charAt(2))) {
            String stripped = Introspector.decapitalize(name.substring(2));
            if (serialized.has(stripped)) {
                return stripped;
            }
        }
        return name;
    }
}
