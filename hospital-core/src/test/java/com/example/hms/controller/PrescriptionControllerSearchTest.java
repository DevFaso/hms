package com.example.hms.controller;

import com.example.hms.exception.FieldBusinessException;
import com.example.hms.service.PrescriptionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** QA D1314: {@code GET /prescriptions?search=}: a search, the plain list, or refused; never a different list. */
@ExtendWith(MockitoExtension.class)
@DisplayName("D1314: the list endpoint's search parameter")
class PrescriptionControllerSearchTest {

    private static final Pageable PAGE = PageRequest.of(0, 50);

    @Mock private PrescriptionService service;
    @InjectMocks private PrescriptionController controller;

    @Test
    void aTermIsASearchWithItsStatuses() {
        when(service.search("morph", List.of(), PAGE, Locale.FRENCH)).thenReturn(Page.empty());

        assertThat(controller.search(null, null, null, List.of(), "morph", PAGE, Locale.FRENCH).getStatusCode().value()).isEqualTo(200);

        verify(service).search("morph", List.of(), PAGE, Locale.FRENCH);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void noTermIsThePlainList(String blank) {
        when(service.list(any(), any(), any(), any(), any(), any())).thenReturn(Page.empty());

        controller.search(null, null, null, null, blank, PAGE, Locale.FRENCH);

        verify(service).list(null, null, null, null, PAGE, Locale.FRENCH);
    }

    @ParameterizedTest
    @CsvSource({"true,false,false", "false,true,false", "false,false,true"})
    void aTermWithAnIdFilterIsRefused(boolean patient, boolean staff, boolean encounter) {
        UUID patientId = patient ? UUID.randomUUID() : null;
        UUID staffId = staff ? UUID.randomUUID() : null;
        UUID encounterId = encounter ? UUID.randomUUID() : null;

        assertThatThrownBy(() -> controller.search(patientId, staffId, encounterId, null, "morph", PAGE, Locale.FRENCH))
            .isInstanceOf(FieldBusinessException.class);

        verifyNoInteractions(service);
    }
}
