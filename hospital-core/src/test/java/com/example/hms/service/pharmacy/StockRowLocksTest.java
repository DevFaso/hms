package com.example.hms.service.pharmacy;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.orm.jpa.JpaSystemException;
import static org.assertj.core.api.Assertions.assertThat;

class StockRowLocksTest {
    @Test
    void nativeAndTranslatedLockFailuresAreRecognizedButOtherDatabaseErrorsAreNot() {
        assertThat(StockRowLocks.isContention(new CannotAcquireLockException("Synthetic lock conflict"))).isTrue();
        assertThat(StockRowLocks.isContention(new JpaSystemException(new RuntimeException(
            new SQLException("Synthetic lock conflict", "55P03"))))).isTrue();
        assertThat(StockRowLocks.isContention(new JpaSystemException(new RuntimeException(
            new SQLException("Synthetic query error", "42601"))))).isFalse();
    }
}
