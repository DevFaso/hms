package com.example.hms.repository;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D280: {@code GET /departments/{id}} answered 404 for every department.
 * The by-id finders the detail and statistics pages use were written as an
 * INNER {@code JOIN FETCH} of an optional association — the head of
 * department, the translations — so a department with no head (every
 * department on dev) or no translation row was never found. Both must stay
 * {@code LEFT JOIN FETCH}.
 */
class DepartmentByIdQueryTest {

    @Test
    void theDetailFinderFindsADepartmentWithoutAHead() throws NoSuchMethodException {
        assertLeftJoinFetch("findByIdWithHeadOfDepartment", "d.headOfDepartment");
    }

    @Test
    void theStatisticsFinderFindsADepartmentWithoutATranslation() throws NoSuchMethodException {
        assertLeftJoinFetch("findByIdWithTranslations", "d.departmentTranslations");
    }

    private static void assertLeftJoinFetch(String finder, String association) throws NoSuchMethodException {
        Method method = DepartmentRepository.class.getDeclaredMethod(finder, UUID.class);
        Query query = method.getAnnotation(Query.class);
        assertThat(query).as("%s carries an explicit @Query", finder).isNotNull();
        String jpql = query.value().replaceAll("\\s+", " ");
        assertThat(jpql).as("%s must LEFT JOIN FETCH the optional %s", finder, association)
            .containsIgnoringCase("LEFT JOIN FETCH " + association)
            .doesNotContainPattern("(?i)(?<!LEFT )JOIN FETCH " + java.util.regex.Pattern.quote(association));
    }
}
