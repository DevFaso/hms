package com.example.hms.controller;

import com.example.hms.repository.AuditEventLogRepository;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.InBasketItemRepository;
import com.example.hms.repository.InstrumentOutboxRepository;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.repository.LabSpecimenRepository;
import com.example.hms.repository.LabTestDefinitionRepository;
import com.example.hms.repository.NotificationRepository;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientProblemHistoryRepository;
import com.example.hms.repository.PatientProblemRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.RoleRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import org.springframework.context.ApplicationContext;

/**
 * The one FK-ordered wipe for the controller ITs that seed hospitals, staff,
 * patients and lab orders into the H2 database they all share.
 *
 * <p>Each of them used to carry its own copy of this list, and each copy knew
 * only about the rows its own class writes. A sibling that left a row one of
 * them did not know about (a lab order pinning a doctor's staff row, a
 * diagnosis pinning it through {@code fk_problem_staff}, an audit row pinning
 * an assignment through {@code fk_audit_assignment}) made the next class's
 * {@code deleteAll()} fail on the FK. So the list is the union, children
 * before parents, and a table added to one flow is added here for all of them.
 */
final class SharedClinicalRowsCleanup {

    private SharedClinicalRowsCleanup() {
    }

    /**
     * The audit table is append-only on PostgreSQL (V382). A subclass run on PostgreSQL
     * (AppointmentBookingEndpointPostgresIT) wipes it with the trigger switched off around the wipe,
     * in its own throwaway container; on H2 there is no trigger.
     */
    private static void wipeAuditRows(ApplicationContext context) {
        org.springframework.jdbc.core.JdbcTemplate jdbc = context.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
        Boolean postgres = jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) connection ->
            connection.getMetaData().getDatabaseProductName().toLowerCase(java.util.Locale.ROOT).contains("postgres"));
        if (!Boolean.TRUE.equals(postgres)) {
            context.getBean(AuditEventLogRepository.class).deleteAllInBatch();
            return;
        }
        jdbc.execute("ALTER TABLE support.audit_event_logs DISABLE TRIGGER trg_audit_append_only");
        try {
            jdbc.update("DELETE FROM support.audit_event_logs");
        } finally {
            jdbc.execute("ALTER TABLE support.audit_event_logs ENABLE TRIGGER trg_audit_append_only");
        }
    }

    static void deleteAll(ApplicationContext context) {
        wipeAuditRows(context);
        context.getBean(NotificationRepository.class).deleteAll();
        // A released result files an in-basket RESULT row for the ordering
        // clinician (QA D178), which pins the user through fk_inbasket_recipient.
        context.getBean(InBasketItemRepository.class).deleteAllInBatch();
        // Releasing a result enqueues an outbound message that references the
        // order, so the outbox goes before the orders.
        context.getBean(InstrumentOutboxRepository.class).deleteAll();
        // QA r70 (C5): an incident names its lab result (V363), so it goes first.
        context.getBean(com.example.hms.repository.CriticalResultIncidentRepository.class).deleteAllInBatch();
        context.getBean(LabResultRepository.class).deleteAll();
        // QA D820: a voided order's results do not load (LabResult's
        // restriction), so the repository wipe above cannot see them; they
        // would pin their order through fk_labresult_order.
        context.getBean(org.springframework.jdbc.core.JdbcTemplate.class).update("DELETE FROM lab.lab_results");
        context.getBean(LabSpecimenRepository.class).deleteAll();
        context.getBean(LabOrderRepository.class).deleteAll();
        context.getBean(LabTestDefinitionRepository.class).deleteAll();
        // QA D605: labour charts pin the patient, the hospital and the staff.
        context.getBean(com.example.hms.repository.ObstetricAlertEscalationRepository.class).deleteAllInBatch();
        // V386: an unresolved alert's incident names its hospital.
        context.getBean(com.example.hms.repository.UrgentAlertIncidentRepository.class).deleteAllInBatch();
        // QA D2580: a partograph entry, postpartum check or newborn assessment charts a vital-sign row.
        context.getBean(com.example.hms.repository.PatientVitalSignRepository.class).deleteAllInBatch();
        context.getBean(com.example.hms.repository.LaborPartographEntryRepository.class).deleteAll();
        // QA D1592: a recorded live birth registers its babies, linked to the delivery.
        context.getBean(com.example.hms.repository.DeliveryNewbornRepository.class).deleteAllInBatch();
        context.getBean(com.example.hms.repository.DeliveryRecordRepository.class).deleteAll();
        context.getBean(com.example.hms.repository.LaborEpisodeRepository.class).deleteAll();
        // QA D796: an unbooked labour opens the pregnancy record (maternal
        // history), which pins the patient, the hospital and the staff.
        context.getBean(com.example.hms.repository.MaternalHistoryRepository.class).deleteAll();
        // QA D1212 (round 24): proxy grants pin the grantor and the proxy
        // user; immunizations pin the patient and the hospital.
        context.getBean(com.example.hms.repository.PatientProxyRepository.class).deleteAllInBatch();
        context.getBean(com.example.hms.repository.ImmunizationRepository.class).deleteAllInBatch();
        context.getBean(PatientHospitalRegistrationRepository.class).deleteAll();
        // patient_problems (and their history) reference staff.
        context.getBean(PatientProblemHistoryRepository.class).deleteAllInBatch();
        context.getBean(PatientProblemRepository.class).deleteAllInBatch();
        context.getBean(PatientRepository.class).deleteAll();
        context.getBean(StaffRepository.class).deleteAll();
        // DepartmentControllerIT (and anything that creates a department)
        // leaves departments behind; a department pins its head's assignment
        // through fk_department_assignment. Only the CI shard order exposed it.
        context.getBean(DepartmentRepository.class).deleteAll();
        context.getBean(UserRoleHospitalAssignmentRepository.class).deleteAll();
        context.getBean(UserRepository.class).deleteAll();
        context.getBean(RoleRepository.class).deleteAll();
        // Billing-money plan PR 6: the payer catalogue pins its hospital (fk_payer_hospital and the plans' and
        // rules' composite FKs); a coverage row may still name a plan (fk_pi_payer_plan).
        org.springframework.jdbc.core.JdbcTemplate jdbc = context.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
        jdbc.update("UPDATE clinical.patient_insurances SET payer_plan_id = NULL WHERE payer_plan_id IS NOT NULL");
        jdbc.update("DELETE FROM billing.payer_plan_rules");
        jdbc.update("DELETE FROM billing.payer_plans");
        jdbc.update("DELETE FROM billing.payers");
        context.getBean(HospitalRepository.class).deleteAll();
        context.getBean(OrganizationRepository.class).deleteAll();
    }
}
