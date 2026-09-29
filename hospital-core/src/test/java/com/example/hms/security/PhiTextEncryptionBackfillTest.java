package com.example.hms.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The backfill must never hold readiness: the ApplicationReadyEvent listener
 * returns at once and the work runs on its own thread, one batch per
 * transaction.
 */
class PhiTextEncryptionBackfillTest {

    private SecretKey originalKey;

    @BeforeEach
    void installKey() throws Exception {
        originalKey = EncryptionKeyHolder.getKeyForTesting();
        KeyGenerator gen = KeyGenerator.getInstance("AES");
        gen.init(256);
        EncryptionKeyHolder.setKeyForTesting(gen.generateKey());
    }

    @AfterEach
    void restoreKey() {
        EncryptionKeyHolder.setKeyForTesting(originalKey);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theReadyListenerReturnsWhileTheBackfillIsStillRunning() throws Exception {
        CountDownLatch queried = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class))).thenAnswer(inv -> {
            queried.countDown();
            release.await(10, TimeUnit.SECONDS);
            return List.of();
        });
        PlatformTransactionManager txm = mock(PlatformTransactionManager.class);
        TransactionStatus status = mock(TransactionStatus.class);
        when(txm.getTransaction(any())).thenReturn(status);
        PhiTextEncryptionBackfill backfill = new PhiTextEncryptionBackfill(jdbc, txm);

        long start = System.nanoTime();
        backfill.startAfterReady();
        long returnedAfterMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        try {
            assertThat(queried.await(5, TimeUnit.SECONDS)).as("the backfill does run").isTrue();
            assertThat(release.getCount()).as("still blocked in its first batch").isEqualTo(1);
            assertThat(returnedAfterMs).as("the listener did not wait for it").isLessThan(2_000);
        } finally {
            release.countDown();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void eachBatchIsItsOwnTransactionUntilNothingIsLeft() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        // Two batches of legacy rows for each target, then none.
        when(jdbc.query(anyString(), any(RowMapper.class)))
            .thenAnswer(inv -> legacyRows(2)).thenAnswer(inv -> legacyRows(1)).thenReturn(List.of())
            .thenAnswer(inv -> legacyRows(1)).thenReturn(List.of());
        when(jdbc.batchUpdate(anyString(), anyList())).thenAnswer(inv -> {
            List<Object[]> args = inv.getArgument(1);
            int[] counts = new int[args.size()];
            java.util.Arrays.fill(counts, 1);
            return counts;
        });
        PlatformTransactionManager txm = mock(PlatformTransactionManager.class);
        TransactionStatus status = mock(TransactionStatus.class);
        when(txm.getTransaction(any())).thenReturn(status);

        new PhiTextEncryptionBackfill(jdbc, txm).backfill();

        // merge notes: 2 batches + the empty one; payload: 1 + the empty one.
        verify(txm, times(5)).getTransaction(any());
        verify(txm, times(5)).commit(any());
    }

    private static List<PhiTextEncryptionBackfill.LegacyValue> legacyRows(int n) {
        java.util.ArrayList<PhiTextEncryptionBackfill.LegacyValue> rows = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            rows.add(new PhiTextEncryptionBackfill.LegacyValue(java.util.UUID.randomUUID(), "plaintext " + i));
        }
        return rows;
    }
}
