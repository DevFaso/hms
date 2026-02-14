package com.example.hms.mapper;

import com.example.hms.model.Department;
import com.example.hms.model.DepartmentTranslation;
import com.example.hms.model.Hospital;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.DepartmentRequestDTO;
import com.example.hms.payload.dto.DepartmentResponseDTO;
import com.example.hms.payload.dto.DepartmentTranslationRequestDTO;
import com.example.hms.payload.dto.DepartmentTranslationResponseDTO;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

@Component
public class DepartmentMapper {

    public DepartmentResponseDTO toDepartmentResponseDTO(Department department, Locale locale) {
        if (department == null) {
            return null;
        }

        DepartmentResponseDTO dto = new DepartmentResponseDTO();
        dto.setId(department.getId() != null ? department.getId().toString() : null);

        Hospital hospital = department.getHospital();
        dto.setHospitalId(extractHospitalId(hospital));
        dto.setHospitalName(extractHospitalName(hospital));
        dto.setPhoneNumber(defaultString(department.getPhoneNumber()));
        dto.setEmail(defaultString(department.getEmail()));
        dto.setActive(department.isActive());
        dto.setCreatedAt(department.getCreatedAt());
        dto.setUpdatedAt(department.getUpdatedAt());
        dto.setDepartmentCode(defaultString(department.getCode()));

        applyHeadOfDepartment(department, dto);
        applyLocalizedContent(department, dto, locale);

        dto.setTranslations(extractTranslationNames(department));
        dto.setStaffCount(department.getStaffMembers() != null ? department.getStaffMembers().size() : 0);
        dto.setBedCount(department.getBedCapacity() != null ? department.getBedCapacity() : 0);

        applyHospitalContactInfo(dto, hospital);

        return dto;
    }

    public Department toDepartment(
        DepartmentRequestDTO dto,
        Hospital hospital,
        Staff headOfDepartment,
        UserRoleHospitalAssignment assignment
    ) {
        if (dto == null) return null;

        Department department = new Department();
        department.setName(dto.getName());
        department.setDescription(dto.getDescription());
        department.setPhoneNumber(dto.getPhoneNumber());
        department.setEmail(dto.getEmail());
        department.setHospital(hospital);
        department.setHeadOfDepartment(headOfDepartment);
        department.setAssignment(assignment);
        department.setActive(dto.isActive());
        department.setCode(dto.getCode());
        department.setBedCapacity(dto.getBedCapacity());

        department.setDepartmentTranslations(buildDepartmentTranslations(dto, department, assignment));

        return department;
    }

    public void updateDepartmentFromDto(
        DepartmentRequestDTO dto,
        Department department,
        Hospital hospital,
        Staff headOfDepartment
    ) {
        if (dto == null || department == null) return;

        department.setName(dto.getName());
        department.setDescription(dto.getDescription());
        department.setPhoneNumber(dto.getPhoneNumber());
        department.setEmail(dto.getEmail());
        department.setHospital(hospital);
        department.setHeadOfDepartment(headOfDepartment);
        department.setActive(dto.isActive());
        department.setCode(dto.getCode());
    department.setBedCapacity(dto.getBedCapacity());

        UserRoleHospitalAssignment assignment = department.getAssignment();

        List<DepartmentTranslation> newTranslations = new ArrayList<>();
        if (dto.getTranslations() != null) {
            for (DepartmentTranslationRequestDTO tr : dto.getTranslations()) {
                DepartmentTranslation t = toDepartmentTranslation(tr);
                if (t != null) {
                    t.setDepartment(department);
                    t.setAssignment(assignment);
                    newTranslations.add(t);
                }
            }
        }
        department.setDepartmentTranslations(newTranslations);

    }

    public DepartmentTranslationResponseDTO mapTranslationForLocale(List<DepartmentTranslation> translations, Locale locale) {
        if (translations == null || translations.isEmpty()) return null;
        String lang = (locale != null ? locale.getLanguage() : null);

        return translations.stream()
            .filter(Objects::nonNull)
            .filter(t -> lang != null && lang.equalsIgnoreCase(t.getLanguageCode()))
            .findFirst()
            .or(() -> translations.stream()
                .filter(t -> "en".equalsIgnoreCase(t.getLanguageCode()))
                .findFirst())
            .or(() -> translations.stream().findFirst())
            .map(this::toDepartmentTranslationDTO)
            .orElse(null);
    }

    private String extractUserFullName(Staff staff) {
        if (staff == null || staff.getUser() == null) return null;
        User u = staff.getUser();
        String f = u.getFirstName() != null ? u.getFirstName().trim() : "";
        String l = u.getLastName()  != null ? u.getLastName().trim()  : "";
        String full = (f + " " + l).trim();
        return full.isEmpty() ? null : full;
    }

    public List<DepartmentResponseDTO> toDepartmentResponseDTOs(List<Department> departments, Locale locale) {
        if (departments == null || departments.isEmpty()) {
            return Collections.emptyList();
        }

        List<DepartmentResponseDTO> responses = new ArrayList<>(departments.size());
        for (Department dep : departments) {
            if (dep != null) {
                responses.add(toDepartmentResponseDTO(dep, locale));
            }
        }
        return responses;
    }


    public DepartmentTranslation toDepartmentTranslation(DepartmentTranslationRequestDTO dto) {
        if (dto == null || dto.getLanguageCode() == null || dto.getName() == null) return null;

        DepartmentTranslation translation = new DepartmentTranslation();
        translation.setLanguageCode(dto.getLanguageCode());
        translation.setName(dto.getName());
        translation.setDescription(dto.getDescription());
        return translation;
    }

    public DepartmentTranslationResponseDTO toDepartmentTranslationDTO(DepartmentTranslation entity) {
        if (entity == null) return null;

        DepartmentTranslationResponseDTO dto = new DepartmentTranslationResponseDTO();
        dto.setId(entity.getId());
        dto.setDepartmentId(Optional.ofNullable(entity.getDepartment()).map(Department::getId).orElse(null));
        dto.setLanguageCode(entity.getLanguageCode());
        dto.setName(entity.getName());
        dto.setDescription(entity.getDescription());
        return dto;
    }

    public List<DepartmentTranslationResponseDTO> mapTranslations(List<DepartmentTranslation> translations) {
        if (translations == null || translations.isEmpty()) return Collections.emptyList();

        List<DepartmentTranslationResponseDTO> results = new ArrayList<>(translations.size());
        for (DepartmentTranslation translation : translations) {
            if (translation != null) {
                DepartmentTranslationResponseDTO dto = toDepartmentTranslationDTO(translation);
                if (dto != null) {
                    results.add(dto);
                }
            }
        }
        return results;
    }

    private String extractHospitalId(Hospital hospital) {
        return hospital != null && hospital.getId() != null ? hospital.getId().toString() : null;
    }

    private String extractHospitalName(Hospital hospital) {
        if (hospital == null) {
            return "";
        }
        String name = hospital.getName();
        return name != null ? name : "";
    }

    private String defaultString(String value) {
        return value != null ? value : "";
    }

    private void applyHeadOfDepartment(Department department, DepartmentResponseDTO dto) {
        Staff head = department.getHeadOfDepartment();
        dto.setHeadOfDepartmentName(head != null ? defaultString(extractUserFullName(head)) : "");
    }

    private void applyLocalizedContent(Department department, DepartmentResponseDTO dto, Locale locale) {
        DepartmentTranslationResponseDTO localized = mapTranslationForLocale(
            department.getDepartmentTranslations(),
            locale
        );
        dto.setName(localized != null ? localized.getName() : department.getName());
        dto.setDescription(localized != null ? localized.getDescription() : department.getDescription());
    }

    private List<String> extractTranslationNames(Department department) {
        if (department.getDepartmentTranslations() == null) {
            return Collections.emptyList();
        }
        List<String> names = new ArrayList<>();
        for (DepartmentTranslation translation : department.getDepartmentTranslations()) {
            if (translation != null && translation.getName() != null) {
                names.add(translation.getName());
            }
        }
        return Collections.unmodifiableList(names);
    }

    private void applyHospitalContactInfo(DepartmentResponseDTO dto, Hospital hospital) {
        if (hospital == null) {
            dto.setHospitalAddress("");
            dto.setHospitalMainPhone("");
            dto.setHospitalEmail("");
            dto.setHospitalWebsite("");
            return;
        }

        dto.setHospitalAddress(defaultString(hospital.getAddress()));
        dto.setHospitalMainPhone(defaultString(hospital.getPhoneNumber()));
        dto.setHospitalEmail(defaultString(hospital.getEmail()));
        dto.setHospitalWebsite(defaultString(hospital.getWebsite()));
    }

    private List<DepartmentTranslation> buildDepartmentTranslations(
        DepartmentRequestDTO dto,
        Department department,
        UserRoleHospitalAssignment assignment
    ) {
        if (dto == null || dto.getTranslations() == null || dto.getTranslations().isEmpty()) {
            return new ArrayList<>();
        }

        List<DepartmentTranslation> translations = new ArrayList<>();
        for (DepartmentTranslationRequestDTO translationDto : dto.getTranslations()) {
            DepartmentTranslation translation = toDepartmentTranslation(translationDto);
            if (translation != null) {
                translation.setDepartment(department);
                translation.setAssignment(assignment);
                translations.add(translation);
            }
        }
        return translations;
    }

}

