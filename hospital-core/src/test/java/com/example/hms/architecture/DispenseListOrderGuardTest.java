package com.example.hms.architecture;

import com.example.hms.repository.pharmacy.DispenseRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1128 guard (round 20): "Délivrances récentes" read page 0 of an
 * unordered query, so the screen showed ten arbitrary (in practice the oldest)
 * fills, the client sorted only those ten, and a fill made today could not be
 * found to be cancelled.
 *
 * <p>Every paged read of dispenses declares its own order in its query
 * (newest first, {@link DispenseRepository#NEWEST_FIRST}); a derived
 * {@code findBy…(…, Pageable)} with no {@code ORDER BY} is the defect. No
 * allowlist.
 */
@DisplayName("Every paged dispense list is ordered by the query itself")
class DispenseListOrderGuardTest {

    @Test
    @DisplayName("each Page<Dispense> read declares ORDER BY")
    void everyPagedDispenseReadIsOrdered() {
        List<String> unordered = new ArrayList<>();
        List<Method> paged = Arrays.stream(DispenseRepository.class.getDeclaredMethods())
            .filter(m -> Page.class.isAssignableFrom(m.getReturnType()))
            .toList();
        for (Method m : paged) {
            Query query = m.getAnnotation(Query.class);
            boolean ordered = query != null
                && query.value().toUpperCase(Locale.ROOT).contains("ORDER BY");
            if (!ordered && !m.getName().contains("OrderBy")) {
                unordered.add(m.getName());
            }
        }

        assertThat(paged).as("the scan found the paged reads").hasSizeGreaterThanOrEqualTo(3);
        assertThat(unordered)
            .as("a paged dispense list with no order shows an arbitrary page (QA D1128)")
            .isEmpty();
        assertThat(DispenseRepository.NEWEST_FIRST).contains("DESC");
    }
}
