package com.example.hms.security.tenant.schema;

import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings({"unchecked", "rawtypes"})
class TenantSchemaLookupTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final TenantSchemaLookup lookup = new TenantSchemaLookup(jdbc, Duration.ofMinutes(5));

    @Test
    void returnsEmptyForNullId() {
        assertThat(lookup.schemaFor(null)).isEmpty();
        verify(jdbc, never()).queryForObject(anyString(), any(RowMapper.class), any(Object[].class));
    }

    @Test
    void returnsSchemaWhenHospitalIsSchemaIsolated() throws Exception {
        UUID id = UUID.randomUUID();
        stubRow(id, "SCHEMA", "tenant_alpha");

        assertThat(lookup.schemaFor(id)).contains("tenant_alpha");
    }

    @Test
    void returnsEmptyForRowLevelHospital() throws Exception {
        UUID id = UUID.randomUUID();
        stubRow(id, "ROW_LEVEL", null);

        assertThat(lookup.schemaFor(id)).isEmpty();
    }

    @Test
    void returnsEmptyAndCachesWhenHospitalNotFound() {
        UUID id = UUID.randomUUID();
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(id)))
            .thenThrow(new EmptyResultDataAccessException(1));

        assertThat(lookup.schemaFor(id)).isEmpty();
        // Second call must not re-hit the DB — the empty result is cached too
        assertThat(lookup.schemaFor(id)).isEmpty();
        verify(jdbc, times(1)).queryForObject(anyString(), any(RowMapper.class), eq(id));
    }

    @Test
    void cachesResultsAcrossCalls() throws Exception {
        UUID id = UUID.randomUUID();
        stubRow(id, "SCHEMA", "tenant_beta");

        for (int i = 0; i < 5; i++) {
            assertThat(lookup.schemaFor(id)).contains("tenant_beta");
        }

        verify(jdbc, times(1)).queryForObject(anyString(), any(RowMapper.class), eq(id));
    }

    @Test
    void invalidateForcesRefetch() throws Exception {
        UUID id = UUID.randomUUID();
        stubRow(id, "SCHEMA", "tenant_gamma");

        Optional<String> first = lookup.schemaFor(id);
        lookup.invalidate(id);
        Optional<String> second = lookup.schemaFor(id);

        assertThat(first).contains("tenant_gamma");
        assertThat(second).contains("tenant_gamma");
        verify(jdbc, times(2)).queryForObject(anyString(), any(RowMapper.class), eq(id));
    }

    @Test
    void expiresAfterTtl() throws Exception {
        UUID id = UUID.randomUUID();
        TenantSchemaLookup shortTtl = new TenantSchemaLookup(jdbc, Duration.ofNanos(1));
        stubRow(id, "SCHEMA", "tenant_delta");

        shortTtl.schemaFor(id);
        // Yield + spin to let the 1-ns TTL expire — Instant.now() granularity is finer than this anyway
        for (int i = 0; i < 100; i++) {
            Thread.onSpinWait();
        }
        shortTtl.schemaFor(id);

        verify(jdbc, atLeastOnce()).queryForObject(anyString(), any(RowMapper.class), eq(id));
    }

    @SuppressWarnings("unchecked")
    private void stubRow(UUID id, String mode, String schemaName) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("isolation_mode")).thenReturn(mode);
        when(rs.getString("tenant_schema_name")).thenReturn(schemaName);
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(id)))
            .thenAnswer(inv -> {
                RowMapper<Object> mapper = inv.getArgument(1);
                return mapper.mapRow(rs, 0);
            });
    }
}
