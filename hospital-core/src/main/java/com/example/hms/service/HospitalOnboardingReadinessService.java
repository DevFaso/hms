package com.example.hms.service;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.payload.dto.HospitalOnboardingReadinessDTO;
import com.example.hms.payload.dto.HospitalOnboardingReadinessDTO.Item;
import com.example.hms.payload.dto.HospitalOnboardingReadinessDTO.Step;
import com.example.hms.repository.BedRepository;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PayerRepository;
import com.example.hms.repository.PriceListItemRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.repository.scheduling.SessionTemplateRepository;
import com.example.hms.service.scheduling.AppointmentSlotPolicy;
import com.example.hms.utility.HospitalCalendar;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * QA r65 D2731: the onboarding checklist of one hospital.
 *
 * <p>A hospital set up by the book (organisation, hospital, departments,
 * staff) gave its patients a booking form that could never succeed: no
 * clinician was attached to a department, and no slot was published. Nothing
 * told the patient why and nothing warned the admin. This reads, for ONE
 * hospital, the counts each step stands on. Every query takes the hospital
 * id; the caller's right to ask about that hospital is the controller's.
 *
 * <p>"Bookable" is not a new rule: it is the two reads the patient booking
 * form already makes ({@code StaffRepository.findDepartmentIdsWithActiveProviders}
 * for the Service list, {@code AppointmentSlotPolicy.departmentsWithOpenSlots}
 * for the times), joined.
 */
@Service
@RequiredArgsConstructor
public class HospitalOnboardingReadinessService {

    private final HospitalRepository hospitalRepository;
    private final DepartmentRepository departmentRepository;
    private final StaffRepository staffRepository;
    private final SessionTemplateRepository sessionTemplateRepository;
    private final BedRepository bedRepository;
    private final PriceListItemRepository priceListItemRepository;
    private final PayerRepository payerRepository;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    /** Owns what "a slot can still be taken" means, for the patient form and for this checklist. */
    private final AppointmentSlotPolicy slotPolicy;

    @Transactional(readOnly = true)
    public HospitalOnboardingReadinessDTO readiness(UUID hospitalId) {
        // A pharmacy or laboratory row (or an unknown id) has no checklist: 404, as any clinical read.
        Hospital hospital = hospitalRepository.findClinicalById(hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException("hospital.notFound", hospitalId));

        long hospitalAdmins = assignmentRepository.countHospitalAdminAssignmentsAt(hospitalId);
        long departments = departmentRepository.countByHospitalId(hospitalId);
        long staff = staffRepository.countActiveByHospitalId(hospitalId);
        Set<UUID> staffedDepartments = staffRepository.findDepartmentIdsWithActiveProviders(hospitalId);
        long templates = sessionTemplateRepository.countByHospital_IdAndActiveTrue(hospitalId);
        // Slots still to come on the hospital's own clock, as the patient lookup judges them.
        Set<UUID> departmentsWithSlots =
            slotPolicy.departmentsWithOpenSlots(hospitalId, HospitalCalendar.now(hospital));
        Set<UUID> bookableDepartments = new HashSet<>(staffedDepartments);
        bookableDepartments.retainAll(departmentsWithSlots);
        long beds = bedRepository.countByWard_Hospital_IdAndActiveTrue(hospitalId);
        long prices = priceListItemRepository.countByHospitalIdAndActiveTrue(hospitalId);
        long payers = payerRepository.countByHospitalIdAndActiveTrue(hospitalId);

        List<Item> items = List.of(
            // First: a department cannot be created until the hospital has its administrator.
            item(Step.HOSPITAL_ADMIN, hospitalAdmins, true),
            item(Step.DEPARTMENTS, departments, true),
            item(Step.STAFF, staff, true),
            item(Step.PROVIDERS_IN_DEPARTMENT, staffedDepartments.size(), true),
            item(Step.SESSION_TEMPLATES, templates, true),
            item(Step.OPEN_SLOTS, bookableDepartments.size(), true),
            item(Step.BEDS, beds, false),
            item(Step.PRICE_LIST, prices, false),
            item(Step.PAYERS, payers, false));
        return new HospitalOnboardingReadinessDTO(hospitalId, !bookableDepartments.isEmpty(), items);
    }

    private static Item item(Step step, long count, boolean requiredForBooking) {
        return new Item(step, count > 0, count, requiredForBooking);
    }
}
