package com.example.hms.mapper;

import com.example.hms.exception.FieldBusinessException;
import com.example.hms.i18n.TestMessageSources;
import com.example.hms.model.LabInstrument;
import com.example.hms.payload.dto.LabInstrumentRequestDTO;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LabInstrumentMapperStatusTest {
    @Test
    void invalidStatusIsNamedAndLocalizedOnCreateAndUpdate() {
        var mapper = new LabInstrumentMapper();
        var request = LabInstrumentRequestDTO.builder().status("BROKEN").build();
        assertThatThrownBy(() -> mapper.toEntity(request, null, null))
            .isInstanceOfSatisfying(FieldBusinessException.class, e -> {
                assertThat(e.getField()).isEqualTo("status");
                assertThat(e.getMessageKey()).isEqualTo("lab.instrument.invalidStatus");
            });
        assertThatThrownBy(() -> mapper.updateEntity(new LabInstrument(), request, null))
            .isInstanceOfSatisfying(FieldBusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("lab.instrument.invalidStatus"));
        var messages = TestMessageSources.bundles();
        assertThat(messages.getMessage("lab.instrument.invalidStatus", null, Locale.FRENCH)).contains("statut");
        assertThat(messages.getMessage("lab.instrument.invalidStatus", null, Locale.ENGLISH)).contains("status");
    }
}
