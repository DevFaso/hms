package com.example.hms.mapper;

import com.example.hms.model.Department;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.User;
import com.example.hms.payload.dto.PatientRequestDTO;
import com.example.hms.payload.dto.PatientResponseDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class PatientMapper {

    private final MessageSource messageSource;

    /* -------------------------------------------------------
     * Public mapping API
     * ------------------------------------------------------- */

    public PatientResponseDTO toPatientDTO(Patient patient, UUID hospitalScopeId) {
        return toPatientDTO(patient, hospitalScopeId, true, true);
    }

    public PatientResponseDTO toPatientDTO(Patient patient) {
        return toPatientDTO(patient, null, true, true);
    }

    /**
     * Core mapper with flags allowing the caller to include
     * primary hospital and department lookup costs.
     */
    @SuppressWarnings("java:S3776")
    public PatientResponseDTO toPatientDTO(Patient patient,
                                           UUID scopedHospitalId,
                                           boolean includeHospital,
                                           boolean includeDepartment) {
        if (patient == null) return null;

        // ---------- SAFE DEFAULTS (never-null targets) ----------
    final UUID nilUuid = uuidNil();
    UUID outHospitalId = nilUuid;
        String outHospitalName = "";
    UUID outOrganizationId = optionalUUID(patient.getOrganizationId(), nilUuid);
    UUID outDepartmentId = optionalUUID(patient.getDepartmentId(), nilUuid);

    UUID outPatientId = optionalUUID(patient.getId(), nilUuid);
        String outPatientName = buildFullName(patient.getFirstName(), patient.getMiddleName(), patient.getLastName());
        String outPatientEmail = defaultString(patient.getEmail());
        String outPatientPhone = defaultString(firstNonBlank(patient.getPhoneNumberPrimary(), patient.getPhoneNumberSecondary()));

    UUID outPrimaryHospitalId = nilUuid;
        String outPrimaryHospitalName = "";
        String outPrimaryHospitalAddress = "";
        String outPrimaryHospitalCode = "";
        LocalDate outRegistrationDate = LocalDate.of(1970, 1, 1);

        // MRI scoped to provided hospital if present
        String mrn = (scopedHospitalId != null) ? patient.getMrnForHospital(scopedHospitalId) : null;

        PatientHospitalRegistration scopedRegistration = resolveScopedRegistration(patient, scopedHospitalId);
        Hospital scopedHospital = scopedRegistration != null ? scopedRegistration.getHospital() : null;

        if (includeHospital && scopedHospital != null) {
            if (scopedHospital.getId() != null) {
                outHospitalId = scopedHospital.getId();
                outPrimaryHospitalId = scopedHospital.getId();
            }
            if (scopedHospital.getName() != null) {
                outHospitalName = scopedHospital.getName();
                outPrimaryHospitalName = scopedHospital.getName();
            }
            if (scopedHospital.getOrganization() != null && scopedHospital.getOrganization().getId() != null) {
                outOrganizationId = scopedHospital.getOrganization().getId();
            }
            if (scopedRegistration != null && scopedRegistration.getRegistrationDate() != null) {
                outRegistrationDate = scopedRegistration.getRegistrationDate();
            }
            if (scopedHospital.getAddress() != null) {
                outPrimaryHospitalAddress = scopedHospital.getAddress();
            }
            if (scopedHospital.getCode() != null) {
                outPrimaryHospitalCode = scopedHospital.getCode();
            }
            if (mrn == null && scopedRegistration != null && scopedRegistration.getMrn() != null) {
                mrn = scopedRegistration.getMrn();
            }
        }

        // ---------- PRIMARY HOSPITAL MIRRORING ----------
        if (includeHospital && outPrimaryHospitalId.equals(nilUuid) && patient.getPrimaryHospital() != null) {
            Hospital h = patient.getPrimaryHospital();
            if (h.getId() != null) {
                outPrimaryHospitalId = h.getId();
                outHospitalId = h.getId(); // backward/legacy mirror
            }
            if (h.getName() != null) {
                outPrimaryHospitalName = h.getName();
                outHospitalName = h.getName(); // backward/legacy mirror
            }
            if (h.getAddress() != null) outPrimaryHospitalAddress = h.getAddress();
            if (h.getCode() != null) outPrimaryHospitalCode = h.getCode();

            // If you have a true registration date per hospital, set it there.
            // As a reasonable fallback, use createdAt date.
            if (patient.getCreatedAt() != null) {
                outRegistrationDate = patient.getCreatedAt().toLocalDate();

            }
        }

        if (mrn == null || mrn.isBlank()) {
            mrn = Optional.ofNullable(patient.getHospitalRegistrations())
                .orElseGet(Collections::emptySet)
                .stream()
                .filter(Objects::nonNull)
                .filter(PatientHospitalRegistration::isActive)
                .sorted(Comparator.comparing(
                    PatientHospitalRegistration::getRegistrationDate,
                    Comparator.nullsLast(Comparator.naturalOrder())
                ))
                .map(PatientHospitalRegistration::getMrn)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(null);
        }

        String resolvedMrn = nullIfBlank(mrn);

        // ---------- DEPARTMENT (latest encounter) ----------
        String departmentName = null;
        if (includeDepartment) {
            Collection<Encounter> encounters = Optional.ofNullable(patient.getEncounters()).orElseGet(Collections::emptySet);
            departmentName = encounters.stream()
                .sorted(Comparator.comparing(Encounter::getEncounterDate,
                    Comparator.nullsLast(Comparator.reverseOrder())))
                .map(Encounter::getDepartment)
                .filter(Objects::nonNull)
                .map(Department::getName)
                .findFirst()
                .orElse(null);
        }

        // ---------- BUILD DTO (do not pass null to never-null outputs) ----------

        return PatientResponseDTO.builder()
            .id(patient.getId())
            .firstName(patient.getFirstName())
            .lastName(patient.getLastName())
            .middleName(patient.getMiddleName())
            .dateOfBirth(patient.getDateOfBirth())
            .gender(patient.getGender())
            .address(resolveMessage(patient.getAddress()))
            .addressLine1(patient.getAddressLine1())
            .addressLine2(patient.getAddressLine2())
            .city(patient.getCity())
            .state(patient.getState())
            .zipCode(patient.getZipCode())
            .postalCode(patient.getZipCode())
            .country(patient.getCountry())
            .phoneNumberPrimary(patient.getPhoneNumberPrimary())
            .phoneNumberSecondary(patient.getPhoneNumberSecondary())
            .email(patient.getEmail())
            .emergencyContactName(patient.getEmergencyContactName())
            .emergencyContactPhone(patient.getEmergencyContactPhone())
            .emergencyContactRelationship(patient.getEmergencyContactRelationship())
            .bloodType(patient.getBloodType())
            .allergies(resolveMessage(patient.getAllergies()))
            .medicalHistorySummary(resolveMessage(patient.getMedicalHistorySummary()))
            .preferredPharmacy(patient.getPreferredPharmacy())
            .careTeamNotes(patient.getCareTeamNotes())
            .chronicConditions(parseChronicConditions(patient.getChronicConditions()))
            .active(patient.isActive())
            .mrn(resolvedMrn)
            .displayName(nullIfBlank(firstNonBlank(
                scopedRegistration != null ? scopedRegistration.getPatientFullName() : null,
                outPatientName
            )))
            .username(patient.getUser() != null ? patient.getUser().getUsername() : null)
            .room(scopedRegistration != null ? nullIfBlank(firstNonBlank(
                scopedRegistration.getCurrentRoom(),
                scopedRegistration.getCurrentBed()
            )) : null)
            .bed(scopedRegistration != null ? nullIfBlank(scopedRegistration.getCurrentBed()) : null)

            .organizationId(outOrganizationId)

            .hospitalId(outHospitalId)
            .hospitalName(outHospitalName)
            .departmentId(outDepartmentId)

            .patientId(outPatientId)
            .patientName(outPatientName)
            .patientEmail(outPatientEmail)
            .patientPhoneNumber(outPatientPhone)

            .primaryHospitalId(outPrimaryHospitalId)
            .primaryHospitalName(outPrimaryHospitalName)
            .primaryHospitalAddress(outPrimaryHospitalAddress)
            .primaryHospitalCode(outPrimaryHospitalCode)
            .registrationDate(outRegistrationDate)

            .departmentName(departmentName)
            .createdAt(patient.getCreatedAt())
            .updatedAt(patient.getUpdatedAt())
            .build();
    }

    /* -------------------------------------------------------
     * Entity <-> Request mapping
     * ------------------------------------------------------- */

    public Patient toPatient(PatientRequestDTO dto, User user) {
        if (dto == null) return null;
        Patient patient = new Patient();
        mapCommonFields(dto, patient);
        patient.setUser(user);
        return patient;
    }

    public void updatePatientFromDto(PatientRequestDTO dto, Patient patient, User user) {
        if (dto == null || patient == null) return;
        mapCommonFields(dto, patient);
        if (user != null) patient.setUser(user);
    }

    private void mapCommonFields(PatientRequestDTO dto, Patient patient) {
        patient.setFirstName(dto.getFirstName());
        patient.setLastName(dto.getLastName());
        patient.setMiddleName(dto.getMiddleName());
        patient.setDateOfBirth(dto.getDateOfBirth());
        patient.setGender(dto.getGender());
        patient.setAddress(dto.getAddress());
    patient.setAddressLine1(dto.getAddressLine1());
    patient.setAddressLine2(dto.getAddressLine2());
        patient.setCity(dto.getCity());
        patient.setState(dto.getState());
        patient.setZipCode(dto.getZipCode());
        patient.setCountry(dto.getCountry());
        patient.setPhoneNumberPrimary(dto.getPhoneNumberPrimary());
        patient.setPhoneNumberSecondary(dto.getPhoneNumberSecondary());
        patient.setEmail(dto.getEmail());
        patient.setEmergencyContactName(dto.getEmergencyContactName());
        patient.setEmergencyContactPhone(dto.getEmergencyContactPhone());
        patient.setEmergencyContactRelationship(dto.getEmergencyContactRelationship());
        patient.setBloodType(dto.getBloodType());
        patient.setAllergies(dto.getAllergies());
        patient.setMedicalHistorySummary(dto.getMedicalHistorySummary());
    patient.setPreferredPharmacy(dto.getPreferredPharmacy());
    patient.setCareTeamNotes(dto.getCareTeamNotes());
    patient.setChronicConditions(joinChronicConditions(dto.getChronicConditions()));
        patient.setActive(dto.isActive());
        if (dto.getOrganizationId() != null) {
            patient.setOrganizationId(dto.getOrganizationId());
        }
        if (dto.getHospitalId() != null) {
            patient.setHospitalId(dto.getHospitalId());
        }
        if (dto.getDepartmentId() != null) {
            patient.setDepartmentId(dto.getDepartmentId());
        }
    }

    /* -------------------------------------------------------
     * Helpers
     * ------------------------------------------------------- */

    private PatientHospitalRegistration resolveScopedRegistration(Patient patient, UUID scopedHospitalId) {
        if (patient == null || scopedHospitalId == null) {
            return null;
        }

        return Optional.ofNullable(patient.getHospitalRegistrations())
            .orElseGet(Collections::emptySet)
            .stream()
            .filter(reg -> reg != null && reg.isActive())
            .filter(reg -> reg.getHospital() != null && reg.getHospital().getId() != null)
            .filter(reg -> scopedHospitalId.equals(reg.getHospital().getId()))
            .findFirst()
            .orElse(null);
    }

    private String resolveMessage(String code) {
        if (code == null) return null;
        try {
            return messageSource.getMessage(code, null, LocaleContextHolder.getLocale());
        } catch (RuntimeException ignored) {
            return code; // Return raw string if not a resolvable message key
        }
    }

    private static UUID uuidNil() {
        return UUID.fromString("00000000-0000-0000-0000-000000000000");
    }

    private static UUID optionalUUID(UUID value, UUID fallback) {
        return value != null ? value : fallback;
    }

    private static String defaultString(String s) {
        return s == null ? "" : s;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return "";
    }

    private static String nullIfBlank(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String buildFullName(String first, String middle, String last) {
        String f = first == null ? "" : first.trim();
        String m = middle == null ? "" : middle.trim();
        String l = last == null ? "" : last.trim();
        String full = (f + " " + (m.isEmpty() ? "" : m + " ") + l).replaceAll("\\s+", " ").trim();
        return full.isEmpty() ? "" : full;
    }

    private static List<String> parseChronicConditions(String stored) {
        if (stored == null || stored.isBlank()) {
            return new ArrayList<>();
        }
        return Arrays.stream(stored.split(","))
            .map(String::trim)
            .filter(token -> !token.isEmpty())
            .toList();
    }

    private static String joinChronicConditions(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(token -> !token.isEmpty())
            .collect(Collectors.joining(","));
    }
}
