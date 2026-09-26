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
                boolean.class, boolean.class, boolean.class, java.util.Collection.class, Pageable.class);
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

    @Test
    void directoryValueAndCountQueriesShareOneScopedWhereClause() throws NoSuchMethodException {
        Method search = UserRepository.class.getDeclaredMethod(
                "searchUsers", String.class, String.class, String.class,
                boolean.class, boolean.class, boolean.class, java.util.Collection.class, Pageable.class);
        Method list = UserRepository.class.getDeclaredMethod(
                "findAllPaged", boolean.class, boolean.class, boolean.class,
                java.util.Collection.class, Pageable.class);

        for (Query query : new Query[] {search.getAnnotation(Query.class), list.getAnnotation(Query.class)}) {
            // The count carries exactly the value query's WHERE, so a page total
            // can never count a row the page would not show.
            assertThat(query.countQuery().replace("SELECT COUNT(u)", "SELECT u"))
                    .isEqualTo(query.value());
            assertThat(query.value())
                    .contains(UserRepository.DIRECTORY_VISIBLE)
                    .contains("IN :hospitalIds")
                    .contains("u.isDeleted = false");
        }
        // Scoped, a role only counts through an assignment at the caller's hospitals.
        assertThat(UserRepository.DIRECTORY_SEARCH_FILTERS)
                .contains("(:scoped = false OR a.hospital.id IN :hospitalIds)");
        String globalRole = UserRepository.DIRECTORY_SEARCH_FILTERS.substring(
                UserRepository.DIRECTORY_SEARCH_FILTERS.indexOf("OR (:scoped = false"));
        assertThat(globalRole).as("a global UserRole counts only unscoped")
                .contains("SELECT 1 FROM UserRole ur");
    }
}
