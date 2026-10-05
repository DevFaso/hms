package com.example.hms.service;

import com.example.hms.enums.EducationComprehensionStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Session;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

/**
 * The insert of a patient's progress row on an education resource, inside
 * the CALLER's transaction, so the row exists only if the caller's request
 * commits.
 *
 * <p>Since V176 there is one row per (patient, resource)
 * ({@code uk_patient_education_progress_patient_resource}). When two first
 * requests race, the loser's insert meets that key. On PostgreSQL a failed
 * statement aborts the whole transaction, so the insert runs under a JDBC
 * savepoint: a unique violation rolls back to the savepoint only, the
 * caller's transaction stays usable, and the caller re-reads and updates
 * the winner's row instead of answering 500.
 *
 * <p>Why not the alternatives: a {@code REQUIRES_NEW} insert commits the row
 * even when the rest of the request fails (the material would then show as
 * assigned to the patient although staff got an error); {@code ON CONFLICT DO
 * NOTHING} is rejected by H2 2.4 (the test schema and the local-h2 profile);
 * Spring's {@code NESTED} propagation would mean changing the shared
 * {@code JpaTransactionManager}'s nested-transaction setting for one write.
 * The statement is plain JDBC on the
 * session's own connection: nothing enters the persistence context, so a
 * rolled-back savepoint leaves no stale entity behind.
 */
@Component
public class EducationProgressWrites {

    /** SQLState of a unique violation on PostgreSQL and H2 alike. */
    private static final String UNIQUE_VIOLATION = "23505";

    private static final String INSERT_SQL = "INSERT INTO clinical.patient_education_progress "
        + "(id, patient_id, resource_id, hospital_id, comprehension_status, progress_percentage, "
        + "started_at, time_spent_seconds, access_count, needs_clarification, confirmed_understanding, "
        + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, 0, ?, 0, 0, FALSE, FALSE, ?, ?)";

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Inserts the (patient, resource) row in the caller's transaction.
     *
     * @return true when this call created it; false when the row already
     *         exists (a concurrent request created it first), in which case
     *         the caller's transaction is left exactly as it was
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean insertIfAbsent(UUID patientId, UUID resourceId, UUID hospitalId,
                                  EducationComprehensionStatus initialStatus) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now(ZoneId.systemDefault()));
        return entityManager.unwrap(Session.class).doReturningWork(connection -> {
            Savepoint savepoint = connection.setSavepoint();
            try (PreparedStatement insert = connection.prepareStatement(INSERT_SQL)) {
                insert.setObject(1, UUID.randomUUID());
                insert.setObject(2, patientId);
                insert.setObject(3, resourceId);
                insert.setObject(4, hospitalId);
                insert.setString(5, initialStatus.name());
                insert.setTimestamp(6, now);
                insert.setTimestamp(7, now);
                insert.setTimestamp(8, now);
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
