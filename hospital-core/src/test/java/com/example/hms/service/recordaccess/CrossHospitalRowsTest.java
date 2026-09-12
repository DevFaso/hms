package com.example.hms.service.recordaccess;

import com.example.hms.enums.SensitivityCategory;
import com.example.hms.model.Hospital;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CrossHospitalRowsTest {

    private static final UUID ACTING = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();

    @Test
    @DisplayName("a row from the acting hospital always surfaces, whatever its category")
    void localRowsAlwaysSurface() {
        assertThat(CrossHospitalRows.maySurface(ACTING, ACTING, null)).isTrue();
        assertThat(CrossHospitalRows.maySurface(ACTING, ACTING, SensitivityCategory.HIV)).isTrue();
    }

    @Test
    @DisplayName("a foreign row surfaces only when untagged (decision D3)")
    void foreignRowsSurfaceOnlyUntagged() {
        assertThat(CrossHospitalRows.maySurface(OTHER, ACTING, null)).isTrue();
        for (SensitivityCategory category : SensitivityCategory.values()) {
            assertThat(CrossHospitalRows.maySurface(OTHER, ACTING, category)).as(category.name()).isFalse();
        }
    }

    @Test
    @DisplayName("a row with no hospital is not foreign")
    void hospitalLessRowsAreNotForeign() {
        assertThat(CrossHospitalRows.maySurface((UUID) null, ACTING, SensitivityCategory.HIV)).isTrue();
        assertThat(CrossHospitalRows.maySurface((Hospital) null, ACTING, SensitivityCategory.HIV)).isTrue();
    }

    @Test
    @DisplayName("the entity overload reads the hospital id off the entity")
    void entityOverload() {
        Hospital other = new Hospital();
        other.setId(OTHER);
        assertThat(CrossHospitalRows.maySurface(other, ACTING, SensitivityCategory.SUBSTANCE_USE)).isFalse();
        assertThat(CrossHospitalRows.maySurface(other, ACTING, null)).isTrue();
    }
}
