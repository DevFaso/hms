package com.example.hms.service.recordaccess;

import com.example.hms.enums.SensitivityCategory;
import com.example.hms.model.Department;
import com.example.hms.model.Hospital;
import com.example.hms.payload.dto.RestrictedRowsDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** E9 #64 — the tally behind "Dossier restreint (hôpital, département, n)". */
class WithheldRowsTest {

    private static final UUID ACTING = UUID.randomUUID();

    private static Hospital hospital(String name) {
        Hospital h = new Hospital();
        h.setId(UUID.randomUUID());
        h.setName(name);
        return h;
    }

    private static Department department(String name) {
        Department d = new Department();
        d.setName(name);
        return d;
    }

    @Test
    @DisplayName("a local row is admitted whatever its category, and never counted")
    void localRowIsAdmittedAndNotCounted() {
        Hospital acting = hospital("Acting");
        acting.setId(ACTING);
        WithheldRows withheld = new WithheldRows();

        assertThat(withheld.admit(acting, department("Psychiatrie"), ACTING, SensitivityCategory.BEHAVIOURAL_HEALTH))
            .isTrue();
        assertThat(withheld.admit(null, null, ACTING, SensitivityCategory.HIV)).isTrue();

        assertThat(withheld.isEmpty()).isTrue();
        assertThat(withheld.summaries()).isEmpty();
    }

    @Test
    @DisplayName("an untagged foreign row travels and is not counted")
    void untaggedForeignRowIsAdmitted() {
        WithheldRows withheld = new WithheldRows();

        assertThat(withheld.admit(hospital("CHU Yalgado"), department("Cardiologie"), ACTING, null)).isTrue();

        assertThat(withheld.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("refused rows are counted per hospital and department, in a stable order")
    void refusedRowsAreCountedPerHospitalAndDepartment() {
        Hospital yalgado = hospital("CHU Yalgado");
        Hospital bobo = hospital("CHU Bobo");
        Department psychiatry = department("Psychiatrie");
        WithheldRows withheld = new WithheldRows();

        assertThat(withheld.admit(yalgado, psychiatry, ACTING, SensitivityCategory.BEHAVIOURAL_HEALTH)).isFalse();
        assertThat(withheld.admit(yalgado, psychiatry, ACTING, SensitivityCategory.BEHAVIOURAL_HEALTH)).isFalse();
        // A problem carries no department: its own line under the hospital.
        assertThat(withheld.admit(yalgado, null, ACTING, SensitivityCategory.HIV)).isFalse();
        assertThat(withheld.admit(bobo, department("Infectiologie"), ACTING, SensitivityCategory.HIV)).isFalse();

        assertThat(withheld.isEmpty()).isFalse();
        assertThat(withheld.summaries())
            .extracting(RestrictedRowsDTO::getHospitalName, RestrictedRowsDTO::getDepartmentName,
                RestrictedRowsDTO::getCount)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("CHU Bobo", "Infectiologie", 1L),
                org.assertj.core.groups.Tuple.tuple("CHU Yalgado", null, 1L),
                org.assertj.core.groups.Tuple.tuple("CHU Yalgado", "Psychiatrie", 2L));
        assertThat(withheld.summaries().get(1).getHospitalId()).isEqualTo(yalgado.getId());
    }

    @Test
    @DisplayName("under a live break-the-glass session nothing is withheld, so nothing is counted")
    void unlockedReadCountsNothing() {
        WithheldRows withheld = new WithheldRows();

        assertThat(withheld.admit(hospital("CHU Yalgado"), department("Psychiatrie"), ACTING,
            SensitivityCategory.BEHAVIOURAL_HEALTH, true)).isTrue();

        assertThat(withheld.isEmpty()).isTrue();
    }
}
