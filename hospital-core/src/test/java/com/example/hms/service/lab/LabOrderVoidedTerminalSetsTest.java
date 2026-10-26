package com.example.hms.service.lab;

import com.example.hms.enums.LabOrderStatus;
import com.example.hms.service.EncounterServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D820: an order voided as entered in error is finished with everywhere a
 * terminal set decides: an encounter is not held open by it.
 */
class LabOrderVoidedTerminalSetsTest {

    @Test
    @DisplayName("a voided lab order does not hold an encounter open")
    @SuppressWarnings("unchecked")
    void encounterTerminalSetCountsAVoidAsDone() throws ReflectiveOperationException {
        Field field = EncounterServiceImpl.class.getDeclaredField("LAB_TERMINAL");
        field.setAccessible(true);
        Set<LabOrderStatus> terminal = (Set<LabOrderStatus>) field.get(null);

        assertThat(terminal).contains(LabOrderStatus.ENTERED_IN_ERROR, LabOrderStatus.CANCELLED,
            LabOrderStatus.COMPLETED);
    }
}
