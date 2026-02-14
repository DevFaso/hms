package com.example.hms.service;

import com.example.hms.exception.BusinessRuleException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.StaffAvailabilityMapper;
import com.example.hms.model.Department;
import com.example.hms.model.Hospital;
import com.example.hms.model.Staff;
import com.example.hms.model.StaffAvailability;
import com.example.hms.payload.dto.StaffAvailabilityRequestDTO;
import com.example.hms.payload.dto.StaffAvailabilityResponseDTO;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.StaffAvailabilityRepository;
import com.example.hms.repository.StaffRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class StaffAvailabilityServiceImpl implements StaffAvailabilityService {

    private final StaffRepository staffRepository;
    private final HospitalRepository hospitalRepository;
    private final StaffAvailabilityRepository availabilityRepository;
    private final StaffAvailabilityMapper mapper;
    private final MessageSource messageSource;
    private final DepartmentRepository departmentRepository;

    @Override
    @Transactional
    public StaffAvailabilityResponseDTO create(StaffAvailabilityRequestDTO dto, Locale locale) {

        Hospital hospital = hospitalRepository.findById(dto.hospitalId())
            .orElseThrow(() -> new ResourceNotFoundException(
                getLocalizedMessage("hospital.not.found", new Object[]{dto.hospitalId()}, locale)));

        Staff staff = staffRepository.findById(dto.staffId())
            .orElseThrow(() -> new ResourceNotFoundException(
                getLocalizedMessage("staff.not.found", new Object[]{dto.staffId()}, locale)));

        if (!staff.isActive()) {
            throw new BusinessRuleException(getLocalizedMessage("staff.inactive", new Object[]{dto.staffId()}, locale));
        }
        if (!staff.getHospital().getId().equals(dto.hospitalId())) {
            throw new BusinessRuleException(getLocalizedMessage("staff.not.assigned",
                new Object[]{staff.getFullName() + " (" + dto.staffId() + ")",
                    hospital.getName() + " (" + dto.hospitalId() + ")"}, locale));
        }

        Department department = departmentRepository.findById(dto.departmentId())
            .orElseThrow(() -> new ResourceNotFoundException(
                getLocalizedMessage("department.not.found", new Object[]{dto.departmentId()}, locale)));

        if (!department.getHospital().getId().equals(hospital.getId())) {
            throw new BusinessRuleException(getLocalizedMessage("department.hospital.mismatch", null, locale));
        }
        if (staff.getDepartment() == null) {
            throw new BusinessRuleException(getLocalizedMessage("staff.department.required", null, locale));
        }
        if (!staff.getDepartment().getId().equals(department.getId())) {
            throw new BusinessRuleException(getLocalizedMessage("staff.department.mismatch", null, locale));
        }

        if (availabilityRepository.existsByStaff_IdAndDate(dto.staffId(), dto.date())) {
            throw new BusinessRuleException(
                getLocalizedMessage("availability.exists", new Object[]{dto.date()}, locale));
        }

        if (!dto.dayOff()) {
            if (dto.availableFrom() == null || dto.availableTo() == null) {
                throw new BusinessRuleException(getLocalizedMessage("availability.time.required", null, locale));
            }
            if (!dto.availableTo().isAfter(dto.availableFrom())) {
                throw new BusinessRuleException(getLocalizedMessage("availability.time.range.invalid", null, locale));
            }
        }

        if (dto.date().isBefore(LocalDate.now())) {
            throw new BusinessRuleException(getLocalizedMessage("availability.date.past", null, locale));
        }

        StaffAvailability availability = mapper.toEntity(dto, staff, hospital);
        return mapper.toDto(availabilityRepository.save(availability));
    }

    @Override
    public boolean isStaffAvailable(UUID staffId, LocalDateTime appointmentDateTime) {
        if (!staffRepository.existsByIdAndActiveTrue(staffId)) {
            log.warn("Staff not found or inactive: {}", staffId);
            return false;
        }
        LocalDate date = appointmentDateTime.toLocalDate();
        LocalTime time = appointmentDateTime.toLocalTime();

        return availabilityRepository.findByStaff_IdAndDate(staffId, date)
            .map(a -> {
                log.info("Checking staff availability: staffId={}, date={}, time={}, availableFrom={}, availableTo={}, dayOff={}",
                    staffId, date, time, a.getAvailableFrom(), a.getAvailableTo(), a.isDayOff());
                if (a.isDayOff()) {
                    log.info("Staff {} is on day off on {}", staffId, date);
                    return false;
                }
                if (a.getAvailableFrom() == null || a.getAvailableTo() == null) {
                    log.warn("Staff {} has invalid availability times on {}: availableFrom={}, availableTo={}",
                        staffId, date, a.getAvailableFrom(), a.getAvailableTo());
                    return false;
                }
                boolean ok = !time.isBefore(a.getAvailableFrom()) && !time.isAfter(a.getAvailableTo());
                if (!ok) {
                    log.info("Staff {} not available at {} (available {}-{})",
                        staffId, time, a.getAvailableFrom(), a.getAvailableTo());
                } else {
                    log.info("Staff {} is available at {} (available {}-{})",
                        staffId, time, a.getAvailableFrom(), a.getAvailableTo());
                }
                return ok;
            })
            .orElseGet(() -> {
                log.info("No availability record found for staff {} on {}", staffId, date);
                return false;
            });
    }

    private String getLocalizedMessage(String code, Object[] args, Locale locale) {
        try { return messageSource.getMessage(code, args, locale); }
        catch (org.springframework.context.NoSuchMessageException e) {
            log.warn("Missing translation for code '{}' in locale '{}'", code, locale);
            if (!Locale.ENGLISH.equals(locale)) {
                try { return messageSource.getMessage(code, args, Locale.ENGLISH); }
                catch (org.springframework.context.NoSuchMessageException ignored) {}
            }
            return code + (args != null && args.length > 0 ? " " + java.util.Arrays.toString(args) : "");
        }
    }
}
