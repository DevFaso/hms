package com.example.hms.repository;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.EntityGraph;

import java.lang.reflect.Method;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the {@code @EntityGraph} on the finder the write-audit interceptor
 * uses from {@code afterCompletion}. Without it the assignment's lazy
 * {@code hospital} is a dead proxy by the time the interceptor reads its name,
 * and every hospital-scoped write audit is lost (dev, 2026-09-05). The bug
 * class is "annotation missing", so reflection is the cheapest guard; the
 * runtime proof lives in {@code UserRoleHospitalAssignmentRepositoryOutOfSessionIT}.
 */
class UserRoleHospitalAssignmentRepositoryEntityGraphTest {

    @Test
    void outOfSessionFinderFetchesHospitalAndRoleInTheSameQuery() throws NoSuchMethodException {
        Method finder = UserRoleHospitalAssignmentRepository.class.getMethod(
            "findFirstWithHospitalAndRoleByUser_IdAndHospital_IdAndActiveTrue", UUID.class, UUID.class);

        EntityGraph graph = finder.getAnnotation(EntityGraph.class);
        assertThat(graph)
            .as("the interceptor reads hospital.name with no session open; the finder must fetch it")
            .isNotNull();
        assertThat(graph.attributePaths()).contains("hospital", "role");
    }
}
