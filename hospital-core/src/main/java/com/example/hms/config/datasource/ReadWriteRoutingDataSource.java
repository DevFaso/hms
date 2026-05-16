package com.example.hms.config.datasource;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * Routes JDBC connections to the write primary or the read replica based on
 * the current Spring transaction's {@code readOnly} flag.
 *
 * <p>Pre-existing {@code @Transactional(readOnly = true)} methods (already
 * sprinkled across the hot read paths in {@code PatientService},
 * {@code MeController}, the various dashboards, etc.) trigger
 * {@link TransactionSynchronizationManager#isCurrentTransactionReadOnly()}
 * to return {@code true} <em>before</em> the connection is acquired from
 * the pool, so the routing happens at transaction begin and stays sticky
 * for the duration of that transaction.
 *
 * <p>When no transaction is active, the default route is WRITE — that is
 * the safe default because:
 * <ul>
 *   <li>The few read paths that <em>don't</em> declare a read-only
 *       transaction would otherwise silently land on the replica and read
 *       stale data without the caller intending it.</li>
 *   <li>Tests that use {@code @DataJpaTest} or otherwise short-circuit the
 *       transaction manager continue to talk to the write primary.</li>
 *   <li>Streaming-replication lag (typically &lt; 100 ms on Railway's
 *       managed Postgres, but spikes happen) does not surface to the
 *       clinical write paths.</li>
 * </ul>
 *
 * <p>Operators flip a specific read path to the replica by adding
 * {@code @Transactional(readOnly = true)} to its outermost service method
 * — no routing-aware code changes are required. The
 * {@link Route#name() route name} is exposed via Hikari pool MXBeans so
 * Grafana dashboards can chart per-pool utilisation.
 */
public class ReadWriteRoutingDataSource extends AbstractRoutingDataSource {

    /**
     * Lookup keys used by {@link #determineCurrentLookupKey()}. Strongly
     * typed so a typo in a property file or a future refactor can't
     * silently route to the wrong pool.
     */
    public enum Route {
        WRITE, READ
    }

    /**
     * @param writeDataSource required; used as both a routing target and the default fallback
     * @param readDataSource  may be {@code null} only in unit tests that exercise the
     *                        routing-key derivation in isolation; production wiring always
     *                        supplies a replica. When {@code null}, the lookup falls
     *                        through to the write default via {@link #setLenientFallback}.
     */
    public ReadWriteRoutingDataSource(DataSource writeDataSource, DataSource readDataSource) {
        if (writeDataSource == null) {
            throw new IllegalArgumentException("writeDataSource is required");
        }
        Map<Object, Object> targets = HashMap.newHashMap(2);
        targets.put(Route.WRITE, writeDataSource);
        if (readDataSource != null) {
            targets.put(Route.READ, readDataSource);
        }
        setTargetDataSources(targets);
        setDefaultTargetDataSource(writeDataSource);
        // Lenient fallback so a missing READ target (replica bean failed
        // to wire) degrades to the write default rather than blowing
        // up the request; pool-utilisation MXBean still surfaces the
        // misrouting.
        setLenientFallback(true);
        afterPropertiesSet();
    }

    @Override
    protected Object determineCurrentLookupKey() {
        return TransactionSynchronizationManager.isCurrentTransactionReadOnly()
            ? Route.READ
            : Route.WRITE;
    }
}
