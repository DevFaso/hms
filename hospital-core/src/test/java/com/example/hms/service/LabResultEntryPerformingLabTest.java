package com.example.hms.service;

import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * QA D4150 (r81): the result of an order sent to another laboratory is entered and edited by that laboratory,
 * as it is released by it.
 */
@DisplayName("QA D4150: only the performing laboratory enters the result of an order sent out")
class LabResultEntryPerformingLabTest {

    private final Hospital ordering = hospital("CMA Pissy");
    private final Hospital performing = hospital("Centre Test B");

    private static Hospital hospital(String name) {
        Hospital hospital = Hospital.builder().name(name).code(name.substring(0, 3)).build();
        hospital.setId(UUID.randomUUID());
        return hospital;
    }

    @Test
    void theOrderingLaboratoryDoesNotEnterTheResultOfAnOrderItSentOut() {
        LabOrder sentOut = LabOrder.builder().hospital(ordering).performingHospital(performing).build();
        UUID orderingId = ordering.getId();

        assertThatThrownBy(() -> LabResultServiceImpl.requireEnteredByPerformingLab(sentOut, orderingId))
            .isInstanceOf(FieldBusinessException.class)
            .satisfies(refused -> assertThat(((FieldBusinessException) refused).getField()).isEqualTo("labOrderId"))
            .extracting("messageKey").isEqualTo("lab.result.entryOtherLab");
    }

    @Test
    void thePerformingLaboratoryEntersIt() {
        LabOrder sentOut = LabOrder.builder().hospital(ordering).performingHospital(performing).build();

        assertThatCode(() -> LabResultServiceImpl.requireEnteredByPerformingLab(sentOut, performing.getId()))
            .doesNotThrowAnyException();
    }

    @Test
    void anOrderPerformedAtHomeIsEnteredAtHome_andAGlobalViewIsNotJudged() {
        LabOrder local = LabOrder.builder().hospital(ordering).build();
        LabOrder namedItself = LabOrder.builder().hospital(ordering).performingHospital(ordering).build();
        LabOrder sentOut = LabOrder.builder().hospital(ordering).performingHospital(performing).build();

        assertThatCode(() -> LabResultServiceImpl.requireEnteredByPerformingLab(local, ordering.getId()))
            .doesNotThrowAnyException();
        assertThatCode(() -> LabResultServiceImpl.requireEnteredByPerformingLab(namedItself, ordering.getId()))
            .doesNotThrowAnyException();
        assertThatCode(() -> LabResultServiceImpl.requireEnteredByPerformingLab(sentOut, null))
            .doesNotThrowAnyException();
    }

    /** The class: every write of a result (its entry, its edit, its deletion) asks the rule, in its own method. */
    @Test
    void theEntryTheEditAndTheDeleteAllAskTheRule() throws IOException {
        String source = Files.readString(Path.of("src/main/java/com/example/hms/service/LabResultServiceImpl.java"),
            StandardCharsets.UTF_8).replace("\r\n", "\n");
        for (String method : List.of("private LabResultResponseDTO createLabResult(",
                "public LabResultResponseDTO updateLabResult(", "public void deleteLabResult(")) {
            int start = source.indexOf(method);
            assertThat(start).as(method).isPositive();
            int end = source.indexOf("\n    }\n", start);
            assertThat(source.substring(start, end)).as(method + "…) asks who performs the order")
                .contains("requireEnteredByPerformingLab(");
        }
    }
}
