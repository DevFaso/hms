package com.example.hms.service;

import com.example.hms.exception.BusinessException;
import com.example.hms.model.LabOrder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** QA D266: URGENT and STAT lab orders were saved as ROUTINE. */
class LabOrderPriorityTest {

    @Test
    void theRequestedPriorityIsKept() {
        assertThat(LabOrderServiceImpl.resolvePriority("URGENT", null)).isEqualTo("URGENT");
        assertThat(LabOrderServiceImpl.resolvePriority(" stat ", null)).isEqualTo("STAT");
        assertThat(LabOrderServiceImpl.resolvePriority(null, null)).isEqualTo("ROUTINE");
    }

    @Test
    void anEditWithoutAPriorityKeepsTheOrdersPriority() {
        LabOrder existing = new LabOrder();
        existing.setPriority("STAT");
        assertThat(LabOrderServiceImpl.resolvePriority("", existing)).isEqualTo("STAT");
        assertThat(LabOrderServiceImpl.resolvePriority("ROUTINE", existing)).isEqualTo("ROUTINE");
    }

    @Test
    void anUnknownPriorityIsRefusedNotDowngraded() {
        assertThatThrownBy(() -> LabOrderServiceImpl.resolvePriority("ASAP", null))
            .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "lab.order.priority.invalid");
    }
}
