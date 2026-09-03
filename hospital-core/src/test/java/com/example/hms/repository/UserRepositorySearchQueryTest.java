package com.example.hms.repository;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for the dev incident on 2026-05-02 where
 * {@code GET /api/users/search?name=a} returned 500 with PostgreSQL
 * complaining {@code function lower(bytea) does not exist} (Position 824
 * in the generated SQL).
 *
 * <p>Root cause: PostgreSQL cannot infer the JDBC type of an unknown bind
 * parameter that is surrounded only by untyped string literals — i.e.
 * {@code ('%' || ? || '%')}. The planner picked {@code bytea} for the
 * parameter, then failed because {@code lower(bytea)} is not a defined
 * function. The fix is to wrap each parameter in {@code cast(:p AS string)}
 * so the parameter has an unambiguous text type at SQL parse time.
 *
 * <p>This test inspects the {@code @Query} annotation on
 * {@link UserRepository#searchUsers} and asserts the cast tokens are still
 * in place. H2 (used by other tests) silently accepts the broken bind, so
 * this is the only place where a regression would be caught short of a
 * Postgres-backed integration test.
 */
class UserRepositorySearchQueryTest {

    @Test
    void searchUsersQueryExplicitlyCastsParametersToString() throws NoSuchMethodException {
        Method searchUsers = UserRepository.class.getDeclaredMethod(
                "searchUsers", String.class, String.class, String.class,
                boolean.class, boolean.class, Pageable.class);
        Query query = searchUsers.getAnnotation(Query.class);

        assertThat(query).as("@Query annotation must be present on searchUsers").isNotNull();

        assertThat(query.value())
                .as("searchUsers JPQL must cast :name to string to avoid PG bytea inference")
                .contains("cast(:name AS string)")
                .as("searchUsers JPQL must cast :email to string to avoid PG bytea inference")
                .contains("cast(:email AS string)")
                .as("searchUsers JPQL must cast :role to string to avoid PG bytea inference")
                .contains("cast(:role AS string)");

        assertThat(query.countQuery())
                .as("searchUsers count query must cast :name to string")
                .contains("cast(:name AS string)")
                .as("searchUsers count query must cast :email to string")
                .contains("cast(:email AS string)")
                .as("searchUsers count query must cast :role to string")
                .contains("cast(:role AS string)");
    }
}
