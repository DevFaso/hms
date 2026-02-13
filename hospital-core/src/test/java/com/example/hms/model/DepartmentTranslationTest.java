package com.example.hms.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class DepartmentTranslationTest {

    private Hospital hospital;
    private UserRoleHospitalAssignment assignment;
    private Department department;

    @BeforeEach
    void setUp() {
        hospital = new Hospital();
        hospital.setId(UUID.randomUUID());

        assignment = new UserRoleHospitalAssignment();
        assignment.setId(UUID.randomUUID());
        assignment.setHospital(hospital);

        department = new Department();
        department.setId(UUID.randomUUID());
        department.setHospital(hospital);
    }

    // ─── Constructors ────────────────────────────────────────────

    @Test
    void noArgConstructor() {
        DepartmentTranslation dt = new DepartmentTranslation();
        assertThat(dt.getId()).isNull();
        assertThat(dt.getAssignment()).isNull();
        assertThat(dt.getLanguageCode()).isNull();
        assertThat(dt.getDepartment()).isNull();
        assertThat(dt.getName()).isNull();
        assertThat(dt.getDescription()).isNull();
        assertThat(dt.getLanguage()).isNull();
    }

    @Test
    void allArgsConstructor() {
        DepartmentTranslation dt = new DepartmentTranslation(
                assignment, "en", department, "Cardiology", "Heart department", "English"
        );
        assertThat(dt.getAssignment()).isEqualTo(assignment);
        assertThat(dt.getLanguageCode()).isEqualTo("en");
        assertThat(dt.getDepartment()).isEqualTo(department);
        assertThat(dt.getName()).isEqualTo("Cardiology");
        assertThat(dt.getDescription()).isEqualTo("Heart department");
        assertThat(dt.getLanguage()).isEqualTo("English");
    }

    @Test
    void builderAllFields() {
        DepartmentTranslation dt = DepartmentTranslation.builder()
                .assignment(assignment)
                .languageCode("fr")
                .department(department)
                .name("Cardiologie")
                .description("Département cardiaque")
                .language("French")
                .build();

        assertThat(dt.getAssignment()).isEqualTo(assignment);
        assertThat(dt.getLanguageCode()).isEqualTo("fr");
        assertThat(dt.getDepartment()).isEqualTo(department);
        assertThat(dt.getName()).isEqualTo("Cardiologie");
        assertThat(dt.getDescription()).isEqualTo("Département cardiaque");
        assertThat(dt.getLanguage()).isEqualTo("French");
    }

    @Test
    void builderMinimal() {
        DepartmentTranslation dt = DepartmentTranslation.builder().build();
        assertThat(dt.getAssignment()).isNull();
        assertThat(dt.getLanguageCode()).isNull();
        assertThat(dt.getDepartment()).isNull();
        assertThat(dt.getName()).isNull();
        assertThat(dt.getDescription()).isNull();
        assertThat(dt.getLanguage()).isNull();
    }

    // ─── Getters/Setters ─────────────────────────────────────────

    @Test
    void setAndGetAssignment() {
        DepartmentTranslation dt = new DepartmentTranslation();
        dt.setAssignment(assignment);
        assertThat(dt.getAssignment()).isEqualTo(assignment);
    }

    @Test
    void setAndGetLanguageCode() {
        DepartmentTranslation dt = new DepartmentTranslation();
        dt.setLanguageCode("pt-BR");
        assertThat(dt.getLanguageCode()).isEqualTo("pt-BR");
    }

    @Test
    void setAndGetDepartment() {
        DepartmentTranslation dt = new DepartmentTranslation();
        dt.setDepartment(department);
        assertThat(dt.getDepartment()).isEqualTo(department);
    }

    @Test
    void setAndGetName() {
        DepartmentTranslation dt = new DepartmentTranslation();
        dt.setName("Surgery");
        assertThat(dt.getName()).isEqualTo("Surgery");
    }

    @Test
    void setAndGetDescription() {
        DepartmentTranslation dt = new DepartmentTranslation();
        dt.setDescription("Surgical procedures");
        assertThat(dt.getDescription()).isEqualTo("Surgical procedures");
    }

    @Test
    void setAndGetLanguage() {
        DepartmentTranslation dt = new DepartmentTranslation();
        dt.setLanguage("Portuguese");
        assertThat(dt.getLanguage()).isEqualTo("Portuguese");
    }

    // ─── BaseEntity inheritance ──────────────────────────────────

    @Test
    void idFromBaseEntity() {
        DepartmentTranslation dt = new DepartmentTranslation();
        UUID id = UUID.randomUUID();
        dt.setId(id);
        assertThat(dt.getId()).isEqualTo(id);
    }

    // ─── toString (excludes department and assignment) ───────────

    @Test
    void toStringContainsNameButExcludesDepartmentAndAssignment() {
        DepartmentTranslation dt = DepartmentTranslation.builder()
                .assignment(assignment)
                .department(department)
                .languageCode("en")
                .name("Cardiology")
                .description("Heart")
                .language("English")
                .build();
        String s = dt.toString();
        assertThat(s).contains("languageCode=en");
        assertThat(s).contains("name=Cardiology");
        assertThat(s).contains("description=Heart");
        assertThat(s).contains("language=English");
        // Excluded by @ToString(exclude = {"department", "assignment"})
        assertThat(s).doesNotContain("department=");
        assertThat(s).doesNotContain("assignment=");
    }

    // ─── normalizeAndValidate (@PrePersist / @PreUpdate) ─────────

    @Nested
    class NormalizeAndValidate {

        private void invokeNormalizeAndValidate(DepartmentTranslation dt) throws Exception {
            Method m = DepartmentTranslation.class.getDeclaredMethod("normalizeAndValidate");
            m.setAccessible(true);
            try {
                m.invoke(dt);
            } catch (java.lang.reflect.InvocationTargetException e) {
                if (e.getCause() instanceof RuntimeException re) throw re;
                throw new RuntimeException(e.getCause());
            }
        }

        @Test
        void happyPathNormalizesLanguageCode() throws Exception {
            DepartmentTranslation dt = DepartmentTranslation.builder()
                    .assignment(assignment)
                    .department(department)
                    .languageCode("  EN  ")
                    .name("Cardiology")
                    .build();

            invokeNormalizeAndValidate(dt);

            assertThat(dt.getLanguageCode()).isEqualTo("en");
        }

        @Test
        void nullLanguageCodeStaysNull() throws Exception {
            // languageCode is null — normalization should not NPE; validation will still run
            DepartmentTranslation dt = DepartmentTranslation.builder()
                    .assignment(assignment)
                    .department(department)
                    .languageCode(null)
                    .name("Test")
                    .build();

            // Should still pass validation (hospitals match)
            invokeNormalizeAndValidate(dt);
            assertThat(dt.getLanguageCode()).isNull();
        }

        @Test
        void assignmentNullThrows() {
            DepartmentTranslation dt = DepartmentTranslation.builder()
                    .assignment(null)
                    .department(department)
                    .languageCode("en")
                    .name("Test")
                    .build();

            assertThatThrownBy(() -> invokeNormalizeAndValidate(dt))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must match");
        }

        @Test
        void assignmentHospitalNullThrows() {
            UserRoleHospitalAssignment noHospitalAssignment = new UserRoleHospitalAssignment();
            noHospitalAssignment.setId(UUID.randomUUID());
            noHospitalAssignment.setHospital(null);

            DepartmentTranslation dt = DepartmentTranslation.builder()
                    .assignment(noHospitalAssignment)
                    .department(department)
                    .languageCode("en")
                    .name("Test")
                    .build();

            assertThatThrownBy(() -> invokeNormalizeAndValidate(dt))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must match");
        }

        @Test
        void departmentNullThrows() {
            DepartmentTranslation dt = DepartmentTranslation.builder()
                    .assignment(assignment)
                    .department(null)
                    .languageCode("en")
                    .name("Test")
                    .build();

            assertThatThrownBy(() -> invokeNormalizeAndValidate(dt))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must match");
        }

        @Test
        void departmentHospitalNullThrows() {
            Department deptNoHospital = new Department();
            deptNoHospital.setId(UUID.randomUUID());
            deptNoHospital.setHospital(null);

            DepartmentTranslation dt = DepartmentTranslation.builder()
                    .assignment(assignment)
                    .department(deptNoHospital)
                    .languageCode("en")
                    .name("Test")
                    .build();

            assertThatThrownBy(() -> invokeNormalizeAndValidate(dt))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must match");
        }

        @Test
        void hospitalIdMismatchThrows() {
            Hospital otherHospital = new Hospital();
            otherHospital.setId(UUID.randomUUID());
            Department deptOtherHospital = new Department();
            deptOtherHospital.setId(UUID.randomUUID());
            deptOtherHospital.setHospital(otherHospital);

            DepartmentTranslation dt = DepartmentTranslation.builder()
                    .assignment(assignment) // assignment.hospital = hospital
                    .department(deptOtherHospital) // department.hospital = otherHospital
                    .languageCode("en")
                    .name("Test")
                    .build();

            assertThatThrownBy(() -> invokeNormalizeAndValidate(dt))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must match");
        }

        @Test
        void hospitalsMatchNoException() throws Exception {
            DepartmentTranslation dt = DepartmentTranslation.builder()
                    .assignment(assignment)
                    .department(department)
                    .languageCode("FR")
                    .name("Chirurgie")
                    .build();

            invokeNormalizeAndValidate(dt);

            // no exception; languageCode normalized
            assertThat(dt.getLanguageCode()).isEqualTo("fr");
        }
    }

    // ─── equals/hashCode from BaseEntity ─────────────────────────

    @Test
    void equalsAndHashCodeById() {
        UUID id = UUID.randomUUID();
        DepartmentTranslation a = new DepartmentTranslation();
        a.setId(id);
        DepartmentTranslation b = new DepartmentTranslation();
        b.setId(id);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void notEqualDifferentIds() {
        DepartmentTranslation a = new DepartmentTranslation();
        a.setId(UUID.randomUUID());
        DepartmentTranslation b = new DepartmentTranslation();
        b.setId(UUID.randomUUID());

        assertThat(a).isNotEqualTo(b);
    }
}
