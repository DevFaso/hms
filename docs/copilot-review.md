hospital-core/src/main/java/com/example/hms/analytics/KpiMaterializedViewRefreshScheduler.java

    private static void runConcurrentOrFallback(Connection connection, String safeName) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("REFRESH MATERIALIZED VIEW CONCURRENTLY " + safeName);
Make sure using a dynamically formatted SQL query is safe here.

 try (Statement fallback = connection.createStatement()) {
                fallback.executeUpdate("REFRESH MATERIALIZED VIEW " + safeName);
Make sure using a dynamically formatted SQL query is safe here.

hospital-core/src/main/java/com/example/hms/security/tenant/schema/TenantProvisioningService.java

        entityManager.createNativeQuery(
            "CREATE SCHEMA IF NOT EXISTS " + sqlSchemaName).executeUpdate();
Make sure using a dynamically formatted SQL query is safe here.


        entityManager.createNativeQuery(
            "GRANT USAGE ON SCHEMA " + sqlSchemaName + " TO " + sqlAppRole).executeUpdate();
Make sure using a dynamically formatted SQL query is safe here.


            "ALTER DEFAULT PRIVILEGES IN SCHEMA " + sqlSchemaName
Make sure using a dynamically formatted SQL query is safe here.

                + " GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO " + sqlAppRole)
            .executeUpdate();