package com.example.hms.service.recordaccess;

import com.example.hms.enums.SensitivityCategory;
import com.example.hms.model.Admission;
import com.example.hms.model.Consultation;
import com.example.hms.model.Department;
import com.example.hms.model.Encounter;
import com.example.hms.model.NursingNote;
import com.example.hms.model.PatientProblem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SensitivityClassifierImpl")
class SensitivityClassifierImplTest {

    private final SensitivityClassifierImpl classifier = new SensitivityClassifierImpl();

    private static Department department(SensitivityCategory defaultCategory) {
        Department d = new Department();
        d.setDefaultSensitivityCategory(defaultCategory);
        return d;
    }

    @Nested
    @DisplayName("effective category")
    class Effective {

        @Test
        @DisplayName("the row's explicit tag wins over the department default")
        void explicitWins() {
            Encounter e = new Encounter();
            e.setSensitivityCategory(SensitivityCategory.HIV);
            e.setDepartment(department(SensitivityCategory.BEHAVIOURAL_HEALTH));

            assertThat(classifier.effectiveCategory(e)).isEqualTo(SensitivityCategory.HIV);
        }

        @Test
        @DisplayName("an untagged row inherits its department's default")
        void inheritsDepartmentDefault() {
            Encounter e = new Encounter();
            e.setDepartment(department(SensitivityCategory.BEHAVIOURAL_HEALTH));

            assertThat(classifier.effectiveCategory(e)).isEqualTo(SensitivityCategory.BEHAVIOURAL_HEALTH);
        }

        @Test
        @DisplayName("an untagged row in an untagged department is untagged")
        void neitherTagged() {
            Encounter e = new Encounter();
            e.setDepartment(department(null));

            assertThat(classifier.effectiveCategory(e)).isNull();
        }

        @Test
        @DisplayName("a row with no department at all falls back to its own tag")
        void noDepartment() {
            Encounter tagged = new Encounter();
            tagged.setSensitivityCategory(SensitivityCategory.SUBSTANCE_USE);

            assertThat(classifier.effectiveCategory(tagged)).isEqualTo(SensitivityCategory.SUBSTANCE_USE);
            assertThat(classifier.effectiveCategory(new Encounter())).isNull();
        }

        @Test
        @DisplayName("an admission resolves through its own department")
        void admission() {
            Admission a = new Admission();
            a.setDepartment(department(SensitivityCategory.BEHAVIOURAL_HEALTH));

            assertThat(classifier.effectiveCategory(a)).isEqualTo(SensitivityCategory.BEHAVIOURAL_HEALTH);
        }

        @Test
        @DisplayName("a consultation inherits the encounter's explicit tag, not just the department default")
        void consultationInheritsEncounterTag() {
            // The encounter was tagged by hand in a general clinic. Without
            // this the consultation inside it would read as untagged and
            // travel — the leak this item exists to prevent.
            Encounter e = new Encounter();
            e.setSensitivityCategory(SensitivityCategory.REPRODUCTIVE_HEALTH);
            e.setDepartment(department(null));
            Consultation c = new Consultation();
            c.setEncounter(e);

            assertThat(classifier.effectiveCategory(c)).isEqualTo(SensitivityCategory.REPRODUCTIVE_HEALTH);
        }

        @Test
        @DisplayName("a consultation's own tag still wins over the encounter's")
        void consultationOwnTagWins() {
            Encounter e = new Encounter();
            e.setSensitivityCategory(SensitivityCategory.BEHAVIOURAL_HEALTH);
            Consultation c = new Consultation();
            c.setEncounter(e);
            c.setSensitivityCategory(SensitivityCategory.HIV);

            assertThat(classifier.effectiveCategory(c)).isEqualTo(SensitivityCategory.HIV);
        }

        @Test
        @DisplayName("a consultation with no encounter is just its own tag")
        void consultationNoEncounter() {
            assertThat(classifier.effectiveCategory(new Consultation())).isNull();
        }

        @Test
        @DisplayName("problems and nursing notes carry only their own tag — they have no department")
        void problemsAndNotes() {
            PatientProblem p = new PatientProblem();
            p.setSensitivityCategory(SensitivityCategory.HIV);
            NursingNote n = new NursingNote();
            n.setSensitivityCategory(SensitivityCategory.BEHAVIOURAL_HEALTH);

            assertThat(classifier.effectiveCategory(p)).isEqualTo(SensitivityCategory.HIV);
            assertThat(classifier.effectiveCategory(n)).isEqualTo(SensitivityCategory.BEHAVIOURAL_HEALTH);
            assertThat(classifier.effectiveCategory(new PatientProblem())).isNull();
            assertThat(classifier.effectiveCategory(new NursingNote())).isNull();
        }

        @Test
        @DisplayName("nulls in, null out — never an exception on a partially loaded row")
        void nullSafe() {
            assertThat(classifier.effectiveCategory((Encounter) null)).isNull();
            assertThat(classifier.effectiveCategory((Admission) null)).isNull();
            assertThat(classifier.effectiveCategory((Consultation) null)).isNull();
            assertThat(classifier.effectiveCategory((PatientProblem) null)).isNull();
            assertThat(classifier.effectiveCategory((NursingNote) null)).isNull();
        }
    }

    @Nested
    @DisplayName("default-withhold")
    class Withhold {

        @ParameterizedTest
        @EnumSource(SensitivityCategory.class)
        @DisplayName("NO named category travels cross-hospital — including any added later")
        void noCategoryTravels(SensitivityCategory category) {
            // Parameterised over the enum on purpose: a value added to
            // SensitivityCategory tomorrow is withheld by default rather than
            // silently travelling until someone remembers to list it.
            assertThat(classifier.travelsCrossHospital(category)).isFalse();
        }

        @Test
        @DisplayName("only an untagged row travels")
        void untaggedTravels() {
            assertThat(classifier.travelsCrossHospital(null)).isTrue();
        }
    }
}
