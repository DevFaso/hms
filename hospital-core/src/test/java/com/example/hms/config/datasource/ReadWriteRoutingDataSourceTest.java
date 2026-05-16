package com.example.hms.config.datasource;

import com.example.hms.config.datasource.ReadWriteRoutingDataSource.Route;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ReadWriteRoutingDataSourceTest {

    private DataSource writeDs;
    private DataSource readDs;
    private ReadWriteRoutingDataSource routing;

    @BeforeEach
    void setUp() {
        writeDs = mock(DataSource.class);
        readDs = mock(DataSource.class);
        routing = new ReadWriteRoutingDataSource(writeDs, readDs);
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    }

    @Test
    @DisplayName("WRITE route when no transaction-read-only flag is set")
    void defaultsToWrite() {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        assertThat(determineRoute()).isEqualTo(Route.WRITE);
    }

    @Test
    @DisplayName("READ route when @Transactional(readOnly=true) is in effect")
    void routesReadOnly() {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        assertThat(determineRoute()).isEqualTo(Route.READ);
    }

    @Test
    @DisplayName("WRITE route after the read-only flag is cleared")
    void clearedFlagReturnsToWrite() {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        assertThat(determineRoute()).isEqualTo(Route.WRITE);
    }

    @Test
    @DisplayName("Rejects null write datasource")
    void rejectsNullWrite() {
        assertThatThrownBy(() -> new ReadWriteRoutingDataSource(null, readDs))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("writeDataSource");
    }

    @Test
    @DisplayName("Tolerates null read datasource — falls back to write")
    void nullReadFallsBackToWriteOnLookup() {
        ReadWriteRoutingDataSource writeOnly = new ReadWriteRoutingDataSource(writeDs, null);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        Object key = invokeDetermineKey(writeOnly);
        // Routing still asks for READ — the AbstractRoutingDataSource lookup
        // then falls back to the default (write) at getConnection() time
        // via setLenientFallback(true). The key itself stays READ so the
        // pool MXBean surfaces the misrouting in dashboards.
        assertThat(key).isEqualTo(Route.READ);
    }

    private Route determineRoute() {
        return (Route) invokeDetermineKey(routing);
    }

    /**
     * {@link org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource#determineCurrentLookupKey()}
     * is protected. The test exercises it directly via reflection — the
     * alternative (calling {@code routing.getConnection()}) would require
     * a real JDBC driver and gain nothing for the routing-key assertion.
     */
    private static Object invokeDetermineKey(ReadWriteRoutingDataSource ds) {
        try {
            Method m = ds.getClass().getDeclaredMethod("determineCurrentLookupKey");
            m.setAccessible(true);
            return m.invoke(ds);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
