package com.example.hms.architecture;

import com.example.hms.repository.DischargeSummaryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1760: visit summaries were ordered by {@code dischargeDate} alone, a
 * day, so the visits closed on one day came back in no order (the patient's
 * "Mes résumés", the staff patient and provider lists). Every list query of
 * the repository must end with {@link DischargeSummaryRepository#NEWEST_FIRST},
 * which breaks the tie by time and creation. A derived query (no
 * {@code @Query}) would order by the day only, so it is refused too.
 */
class DischargeSummaryOrderingGuardTest {

    @Test
    void everyListQueryIsNewestFirstByDateAndTime() {
        List<String> offenders = new ArrayList<>();
        int lists = 0;
        for (Method method : DischargeSummaryRepository.class.getDeclaredMethods()) {
            if (!List.class.isAssignableFrom(method.getReturnType())) {
                continue;
            }
            lists++;
            Query query = method.getAnnotation(Query.class);
            if (query == null || !query.value().endsWith(DischargeSummaryRepository.NEWEST_FIRST)) {
                offenders.add(method.getName());
            }
        }
        assertThat(lists).as("list queries found").isGreaterThanOrEqualTo(10);
        assertThat(offenders).as("list queries without the date-time tiebreak").isEmpty();
    }

    @Test
    void theOrderBreaksASameDayTieByTimeThenCreation() {
        assertThat(DischargeSummaryRepository.NEWEST_FIRST)
            .contains("ds.dischargeDate DESC", "ds.dischargeTime DESC NULLS LAST", "ds.createdAt DESC");
    }
}
