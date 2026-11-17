package com.example.hms.controller;

import com.example.hms.security.context.HospitalContextHolder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Replays the inherited front-desk round trips, including reschedule/rebook,
 * on the migrated PostgreSQL schema with all foreign keys enabled. */
@Testcontainers
class AppointmentBookingEndpointPostgresIT extends FrontDeskRoundTripIT {
    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_booking_endpoint").withUsername("hms_test_user").withPassword("hms_test_pass");

    @DynamicPropertySource
    static void realDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.liquibase.enabled", () -> "true");
        registry.add("spring.sql.init.mode", () -> "never");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "6");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void concurrentHttpBookingCommitsOneVisitAndAnUnchangedRetryReplaysIt(boolean explicit) throws Exception {
        String legacyBody = appointmentBody(aicha, LocalDate.now().plusDays(10), "09:00", "09:30", "Concurrent booking");
        String body = explicit ? legacyBody.replace("\"patientId\"",
            "\"bookingRequestId\":\"" + UUID.randomUUID() + "\",\"patientId\"") : legacyBody;
        long before = appointmentRepository.count();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<MvcResult> first = concurrentRequest(body, ready, start);
        CompletableFuture<MvcResult> second = concurrentRequest(body, ready, start);
        try {
            assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
        } finally {
            start.countDown();
        }
        List<MvcResult> results = List.of(first.get(90, TimeUnit.SECONDS), second.get(90, TimeUnit.SECONDS));
        assertThat(results.stream().map(result -> result.getResponse().getStatus()))
            .contains(201).allMatch(status -> status == 201 || status == 400 || status == 409);
        UUID winner = null;
        for (MvcResult result : results) {
            String response = result.getResponse().getContentAsString();
            if (result.getResponse().getStatus() == 201) {
                UUID id = UUID.fromString(com.jayway.jsonpath.JsonPath.read(response, "$.id"));
                if (winner == null) winner = id;
                else assertThat(id).isEqualTo(winner);
            } else {
                assertThat(response).doesNotContain("duplicate key", "uq_appointment", "SQLException", "org.hibernate");
                String message = com.jayway.jsonpath.JsonPath.read(response, "$.message");
                assertThat(message).isNotBlank();
            }
        }
        assertThat(appointmentRepository.count()).isEqualTo(before + 1);
        assertThat(createBooking(body)).isEqualTo(winner);
        assertThat(appointmentRepository.count()).isEqualTo(before + 1);
    }

    private CompletableFuture<MvcResult> concurrentRequest(String body, CountDownLatch ready, CountDownLatch start) {
        return CompletableFuture.supplyAsync(() -> {
            ready.countDown();
            try {
                if (!start.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("Booking start timed out");
                return mockMvc.perform(post("/api/appointments").contextPath("/api")
                    .contentType(MediaType.APPLICATION_JSON).header("Accept-Language", "fr")
                    .content(body).with(asReceptionist())).andReturn();
            } catch (Exception ex) {
                throw new IllegalStateException("Local booking request failed", ex);
            } finally {
                SecurityContextHolder.clearContext();
                HospitalContextHolder.clear();
            }
        });
    }
}
