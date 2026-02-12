package com.example.hms.model.empi;

import com.example.hms.enums.empi.EmpiAliasType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class EmpiIdentityAliasTest {

    @Test
    @SuppressWarnings("java:S3011")
    void normalizeTrimsFields() throws Exception {
        EmpiIdentityAlias alias = EmpiIdentityAlias.builder()
            .aliasType(EmpiAliasType.MRN)
            .aliasValue(" 12345 ")
            .sourceSystem("  ehr  ")
            .build();

        Method normalize = EmpiIdentityAlias.class.getDeclaredMethod("normalize");
        normalize.setAccessible(true);
        normalize.invoke(alias);

        assertThat(alias.getAliasValue()).isEqualTo("12345");
        assertThat(alias.getSourceSystem()).isEqualTo("ehr");
    }
}
