package com.example.hms.service.billing.cash;

import com.example.hms.enums.CashSessionStatus;
import com.example.hms.enums.CashTill;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Session;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

/**
 * Billing-money plan PR 10: opens a cashier's session for a till and day if none exists, in the caller's transaction.
 * Two movements at the same instant both try; the unique key (hospital, cashier, till, day) lets one insert win and
 * the other roll back to its savepoint only, so the caller's transaction stays usable and both then read the one
 * session (the same idea as {@code EducationProgressWrites}).
 */
@Component
public class CashSessionWrites {

    /** SQLState of a unique violation on PostgreSQL and H2 alike. */
    private static final String UNIQUE_VIOLATION = "23505";

    private static final String INSERT_SQL = "INSERT INTO billing.cash_sessions "
        + "(id, hospital_id, cashier_user_id, till, business_date, status, opened_at, close_round, created_at, "
        + "updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, ?)";

    @PersistenceContext
    private EntityManager entityManager;

    /** The zone Hibernate stores timestamps in ({@code hibernate.jdbc.time_zone}). */
    private final ZoneId jdbcZone;

    public CashSessionWrites(@Value("${spring.jpa.properties.hibernate.jdbc.time_zone:UTC}") String jdbcTimeZone) {
        this.jdbcZone = ZoneId.of(jdbcTimeZone);
    }

    /** True when this call created the session; false when it already existed. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean insertIfAbsent(UUID hospitalId, UUID cashierId, CashTill till, LocalDate day) {
        LocalDateTime now = LocalDateTime.ofInstant(Instant.now(), jdbcZone);
        return entityManager.unwrap(Session.class).doReturningWork(connection -> {
            Savepoint savepoint = connection.setSavepoint();
            try (PreparedStatement insert = connection.prepareStatement(INSERT_SQL)) {
                insert.setObject(1, UUID.randomUUID());
                insert.setObject(2, hospitalId);
                insert.setObject(3, cashierId);
                insert.setString(4, till.name());
                insert.setObject(5, day);
                insert.setString(6, CashSessionStatus.OPEN.name());
                insert.setObject(7, now);
                insert.setObject(8, now);
                insert.setObject(9, now);
                insert.executeUpdate();
            } catch (SQLException e) {
                connection.rollback(savepoint);
                if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
                    return false;
                }
                throw e;
            }
            connection.releaseSavepoint(savepoint);
            return true;
        });
    }
}
