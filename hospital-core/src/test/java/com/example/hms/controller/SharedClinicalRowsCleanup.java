package com.example.hms.controller;

import com.example.hms.repository.AuditEventLogRepository;
import com.example.hms.repository.HospitalRepository;
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

    static void deleteAll(ApplicationContext context) {
        context.getBean(AuditEventLogRepository.class).deleteAllInBatch();
        context.getBean(NotificationRepository.class).deleteAll();
        // Releasing a result enqueues an outbound message that references the
        // order, so the outbox goes before the orders.
        context.getBean(InstrumentOutboxRepository.class).deleteAll();
        context.getBean(LabResultRepository.class).deleteAll();
        context.getBean(LabSpecimenRepository.class).deleteAll();
        context.getBean(LabOrderRepository.class).deleteAll();
        context.getBean(LabTestDefinitionRepository.class).deleteAll();
        context.getBean(PatientHospitalRegistrationRepository.class).deleteAll();
        // patient_problems (and their history) reference staff.
        context.getBean(PatientProblemHistoryRepository.class).deleteAllInBatch();
        context.getBean(PatientProblemRepository.class).deleteAllInBatch();
        context.getBean(PatientRepository.class).deleteAll();
        context.getBean(StaffRepository.class).deleteAll();
        context.getBean(UserRoleHospitalAssignmentRepository.class).deleteAll();
        context.getBean(UserRepository.class).deleteAll();
        context.getBean(RoleRepository.class).deleteAll();
        context.getBean(HospitalRepository.class).deleteAll();
        context.getBean(OrganizationRepository.class).deleteAll();
    }
}
