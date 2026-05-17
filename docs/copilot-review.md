hospital-core/src/main/java/com/example/hms/health/ReadReplicaHealthIndicator.java
Comment on lines +170 to +192
        return readOnlyTemplate.execute(status -> {
            try (Connection conn = primaryDataSource.getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT 1")) {
                rs.next();
                String url = conn.getMetaData().getURL();
                // The replica properties' URL is the operator's
                // declared identity for the replica pool. A match on
                // the connection's JDBC URL is the most reliable way
                // to confirm the lenient-fallback didn't engage —
                // querying pool name via reflection on the Hikari
                // wrapper is brittle across version bumps.
                if (properties.getUrl() != null
                    && properties.getUrl().equalsIgnoreCase(url)) {
                    return Route.READ;
                }
                return Route.WRITE;
            } catch (java.sql.SQLException ex) {
                throw new IllegalStateException(
                    "read-route SELECT 1 failed: " + ex.getMessage(), ex);
            }
        });
    }
Copilot commented 3 minutes ago
Copilot
Copilot
AI
3 minutes ago
Medium
primaryDataSource.getConnection() is called directly inside the TransactionTemplate callback rather than through DataSourceUtils.getConnection(primaryDataSource). The connection acquired this way is not bound to the active Spring transaction, so it bypasses transaction management entirely (no commit/rollback through the transaction manager, no proper resource release through Spring's synchronization). The routing key happens to still work because AbstractRoutingDataSource.determineCurrentLookupKey() reads from TransactionSynchronizationManager.isCurrentTransactionReadOnly() regardless. Using DataSourceUtils.getConnection(primaryDataSource) (and releaseConnection) would be the Spring-idiomatic way to acquire a transaction-bound connection.

This issue also appears on line 175 of the same file.

Copilot uses AI. Check for mistakes.
@TiegoOuedraogo	Reply...
hospital-core/src/test/java/com/example/hms/health/ReadReplicaHealthIndicatorTest.java
Comment on lines +201 to +205

    @SuppressWarnings("unused")
    private static DataSource unused(DataSource ds) {
        return mock(DataSource.class);
    }
Copilot commented 3 minutes ago
Copilot
Copilot
AI
3 minutes ago
Low
Dead code: the unused static helper is annotated @SuppressWarnings("unused") and is never invoked anywhere in the test. It should be removed.

Suggested changeset 1 (1)
hospital-core/src/test/java/com/example/hms/health/ReadReplicaHealthIndicatorTest.java
Original file line number	Diff line number	Diff line change
         lenient().when(resultSet.getTimestamp("last_replay"))
             .thenReturn(Timestamp.valueOf(LocalDateTime.of(2026, 5, 17, 16, 0)));
     }
     @SuppressWarnings("unused")
     private static DataSource unused(DataSource ds) {
         return mock(DataSource.class);
     }
 }
Copilot uses AI. Check for mistakes.
@TiegoOuedraogo	Reply...
scripts/db/replica-preflight.sh
Comment on lines +12 to +20
# Runs five checks in order, exits non-zero on the first failure:
#
#   1. Required env vars present and well-formed.
#   2. Primary reachable + write-pool role can run SELECT 1.
#   3. Replica reachable + REPLICA_USER can run SELECT 1.
#   4. Replica is actually a hot-standby (pg_is_in_recovery()=true).
#   5. Replication lag is within REPLICA_LAG_BUDGET_SECONDS (default 5s).
#   6. REPLICA_USER has pg_read_all_data (Postgres >= 14) OR explicit
#      SELECT grants on every schema HMS reads from.
Copilot commented 3 minutes ago
Copilot
Copilot
AI
3 minutes ago
Low
The header comment says "Runs five checks in order" but six numbered checks follow (1–6). Either re-word to "six checks" or merge the env-var check (1) into the invocation preamble to match. The same inconsistency appears in docs/runbooks/postgres-pool-replica-sizing.md ("Six checks: ...").

This issue also appears on line 32 of the same file.

Copilot uses AI. Check for mistakes.
@TiegoOuedraogo	Reply...
grafana/prometheus-multigeo.example.yml
Comment on lines +40 to +71
    geo: ${GEO_LABEL}

remote_write:
  - url: ${MIMIR_REMOTE_WRITE_URL}
    # Keep this region's identity on every series. Mimir's tenant
    # federation drops `__replica__` but preserves external_labels.
    write_relabel_configs:
      - source_labels: [__name__]
        regex: 'probe_.*|up'
        action: keep

scrape_configs:

  - job_name: 'blackbox_http'
    metrics_path: /probe
    scrape_interval: 30s
    scrape_timeout: 15s
    params:
      module: [http_health_2xx]
    static_configs:
      - targets:
          - ${HMS_PUBLIC_BASE_URL}/api/actuator/health/liveness
          - ${HMS_PUBLIC_BASE_URL}/api/actuator/health/readiness
        labels:
          probe_module: 'http_health_2xx'
    relabel_configs:
      - source_labels: [__address__]
        target_label: __param_target
      - source_labels: [__param_target]
        target_label: instance
      - target_label: __address__
        replacement: ${BLACKBOX_HOST}
Copilot commented 3 minutes ago
Copilot
Copilot
AI
3 minutes ago
Low
${HMS_PUBLIC_BASE_URL} is used both as the target URL value in static_configs.targets and as a substitution placeholder. Prometheus YAML does not perform shell-style ${VAR} expansion at load time (Prometheus only supports $(VAR) substitution under certain limited paths, and ${...} substitution is not part of stock Prometheus config syntax). Operators copying this template must literally sed-substitute the placeholders before deploying — please call this out more explicitly in the file header (the current wording in the README block says "substitute the four placeholders" but readers familiar with envsubst-style configs may assume Prometheus does the expansion).