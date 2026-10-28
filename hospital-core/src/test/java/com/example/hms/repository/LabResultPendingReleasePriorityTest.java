package com.example.hms.repository;

import com.example.hms.mapper.LabResultMapper;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D963: the validation queue listed a STAT result behind a day of routine
 * ones (sorted by date only), and nothing on it said STAT. The queue now
 * orders STAT, then URGENT, then the rest, and every result carries its
 * order's priority for the badge. The query is executed against a real
 * database by LabOrderCrossHospitalIT.
 */
class LabResultPendingReleasePriorityTest {

    @Test
    void theReleaseQueueListsStatThenUrgentFirst() throws NoSuchMethodException {
        Method finder = LabResultRepository.class.getDeclaredMethod(
            "findPendingReleaseHandledBy", UUID.class, Pageable.class);
        String jpql = finder.getAnnotation(Query.class).value().replaceAll("\\s+", " ");

        assertThat(jpql).contains(
            "ORDER BY CASE r.labOrder.priority WHEN 'STAT' THEN 0 WHEN 'URGENT' THEN 1 ELSE 2 END");
    }

    @Test
    void aResultCarriesItsOrdersPriority() {
        LabOrder order = new LabOrder();
        order.setId(UUID.randomUUID());
        order.setPriority("STAT");
        LabResult result = new LabResult();
        result.setId(UUID.randomUUID());
        result.setLabOrder(order);

        assertThat(new LabResultMapper().toResponseDTO(result).getOrderPriority()).isEqualTo("STAT");
    }
}
