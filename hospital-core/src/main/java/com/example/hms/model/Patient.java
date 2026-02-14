package com.example.hms.model;

import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.tenant.TenantEntityListener;
import com.example.hms.security.tenant.TenantScoped;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(
    name = "patients",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_patient_email", columnList = "email"),
        @Index(name = "idx_patient_phone_primary", columnList = "phone_number_primary"),
        @Index(name = "idx_patient_user", columnList = "user_id")
    }
)
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@EntityListeners(TenantEntityListener.class)
@ToString(exclude = {
    "user", "hospitalRegistrations", "appointments", "encounters",
    "billingInvoices", "patientInsurances", "labOrders", "allergyEntries"
})
public class Patient extends BaseEntity implements TenantScoped {

    @NotBlank @Size(max = 100)
    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @NotBlank @Size(max = 100)
    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Size(max = 100)
    @Column(name = "middle_name", length = 100)
    private String middleName;

    @NotNull @Past
    @Column(name = "date_of_birth", nullable = false)
    private LocalDate dateOfBirth;

    @Size(max = 10)
    @Column(name = "gender", length = 10)
    private String gender;

    @Column(name = "address", length = 1024)
    private String address;

    @Size(max = 255)
    @Column(name = "address_line1", length = 255)
    private String addressLine1;

    @Size(max = 255)
    @Column(name = "address_line2", length = 255)
    private String addressLine2;

    @Size(max = 100) @Column(name = "city", length = 100)
    private String city;

    @Size(max = 100) @Column(name = "state", length = 100)
    private String state;

    @Size(max = 100) @Column(name = "zip_code", length = 100)
    private String zipCode;

    @Size(max = 100) @Column(name = "country", length = 100)
    private String country;

    @NotBlank @Size(max = 100)
    @Column(name = "phone_number_primary", length = 100, nullable = false, unique = true)
    private String phoneNumberPrimary;

    @Size(max = 100)
    @Column(name = "phone_number_secondary", length = 100)
    private String phoneNumberSecondary;

    @Email @NotBlank @Size(max = 150)
    @Column(name = "email", length = 150, nullable = false, unique = true)
    private String email;

    @Size(max = 100)
    @Column(name = "emergency_contact_name", length = 100)
    private String emergencyContactName;

    @Size(max = 20)
    @Column(name = "emergency_contact_phone", length = 20)
    private String emergencyContactPhone;

    @Size(max = 50)
    @Column(name = "emergency_contact_relationship", length = 50)
    private String emergencyContactRelationship;

    @Size(max = 5)
    @Column(name = "blood_type", length = 5)
    private String bloodType;

    @Size(max = 2048)
    @Column(name = "allergies", length = 2048)
    private String allergies;

    @Size(max = 2048)
    @Column(name = "medical_history_summary", length = 2048)
    private String medicalHistorySummary;

    @Size(max = 255)
    @Column(name = "preferred_pharmacy", length = 255)
    private String preferredPharmacy;

    @Size(max = 2000)
    @Column(name = "care_team_notes", length = 2000)
    private String careTeamNotes;

    @Size(max = 2048)
    @Column(name = "chronic_conditions", length = 2048)
    private String chronicConditions;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "hospital_id")
    private UUID hospitalId;

    @Column(name = "department_id")
    private UUID departmentId;

    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true,
        foreignKey = @ForeignKey(name = "fk_patient_user"))
    private User user;

    @EqualsAndHashCode.Exclude
    @Builder.Default
    @OneToMany(mappedBy = "patient", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<PatientHospitalRegistration> hospitalRegistrations = new HashSet<>();

    @EqualsAndHashCode.Exclude
    @Builder.Default
    @OneToMany(mappedBy = "patient", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<Appointment> appointments = new HashSet<>();

    @EqualsAndHashCode.Exclude
    @Builder.Default
    @OneToMany(mappedBy = "patient", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<Encounter> encounters = new HashSet<>();

    @EqualsAndHashCode.Exclude
    @Builder.Default
    @OneToMany(mappedBy = "patient", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<BillingInvoice> billingInvoices = new HashSet<>();

    @EqualsAndHashCode.Exclude
    @Builder.Default
    @OneToMany(mappedBy = "patient", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<PatientInsurance> patientInsurances = new HashSet<>();

    @EqualsAndHashCode.Exclude
    @Builder.Default
    @OneToMany(mappedBy = "patient", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<LabOrder> labOrders = new HashSet<>();

    @EqualsAndHashCode.Exclude
    @Builder.Default
    @OneToMany(mappedBy = "patient", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<PatientAllergy> allergyEntries = new HashSet<>();

    @EqualsAndHashCode.Exclude
    @Builder.Default
    @OneToMany(mappedBy = "patient", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<PatientVitalSign> vitalSignCaptures = new HashSet<>();

    /* ---------- Lifecycle normalization ---------- */
    @PrePersist @PreUpdate
    private void normalize() {
        if (email != null) email = email.trim().toLowerCase();
        if (firstName != null) firstName = firstName.trim();
        if (middleName != null) middleName = middleName.trim();
        if (lastName != null) lastName = lastName.trim();
        if (gender != null) gender = gender.trim();
        if (bloodType != null) bloodType = bloodType.trim().toUpperCase();
        if (phoneNumberPrimary != null) phoneNumberPrimary = phoneNumberPrimary.trim();
        if (phoneNumberSecondary != null) phoneNumberSecondary = phoneNumberSecondary.trim();
        if (emergencyContactPhone != null) emergencyContactPhone = emergencyContactPhone.trim();
        if (addressLine1 != null) addressLine1 = addressLine1.trim();
        if (addressLine2 != null) addressLine2 = addressLine2.trim();
        if (preferredPharmacy != null) preferredPharmacy = preferredPharmacy.trim();
    }

    /* ---------- Convenience helpers ---------- */

    public boolean isRegisteredInHospital(UUID hospitalId) {
        if (hospitalId == null || hospitalRegistrations == null) return false;
        return hospitalRegistrations.stream()
            .filter(reg -> reg != null && reg.getHospital() != null && reg.getHospital().getId() != null)
            .anyMatch(reg -> hospitalId.equals(reg.getHospital().getId()) && reg.isActive());
    }

    public String getMrnForHospital(UUID hospitalId) {
        if (hospitalId == null || hospitalRegistrations == null) return null;
        return hospitalRegistrations.stream()
            .filter(reg -> reg != null && reg.getHospital() != null && reg.getHospital().getId() != null)
            .filter(reg -> hospitalId.equals(reg.getHospital().getId()))
            .map(PatientHospitalRegistration::getMrn)
            .findFirst()
            .orElse(null);
    }

    public Hospital getPrimaryHospital() {
        if (hospitalRegistrations == null) return null;
        return hospitalRegistrations.stream()
            .filter(reg -> reg != null && reg.isActive())
            .map(PatientHospitalRegistration::getHospital)
            .filter(h -> h != null && h.getId() != null)
            .findFirst()
            .orElse(null);
    }

    @Transient
    public String getFullName() {
        String f = firstName != null ? firstName.trim() : "";
        String m = middleName != null && !middleName.isBlank() ? (" " + middleName.trim()) : "";
        String l = lastName != null ? (" " + lastName.trim()) : "";
        String full = (f + m + l).trim();
        return full.isEmpty() ? null : full;
    }

    @Override
    public UUID getTenantOrganizationId() {
        return organizationId;
    }

    @Override
    public UUID getTenantHospitalId() {
        return hospitalId;
    }

    @Override
    public UUID getTenantDepartmentId() {
        return departmentId;
    }

    @Override
    public void applyTenantScope(HospitalContext context) {
        HospitalContext effectiveContext = (context != null) ? context : HospitalContext.empty();

        if (organizationId == null && effectiveContext.getActiveOrganizationId() != null) {
            organizationId = effectiveContext.getActiveOrganizationId();
        }
        if (hospitalId == null && effectiveContext.getActiveHospitalId() != null) {
            hospitalId = effectiveContext.getActiveHospitalId();
        }
        if (departmentId == null && !effectiveContext.getPermittedDepartmentIds().isEmpty()) {
            departmentId = effectiveContext.getPermittedDepartmentIds().iterator().next();
        }

        Hospital primaryHospital = getPrimaryHospital();
        if (hospitalId == null && primaryHospital != null && primaryHospital.getId() != null) {
            hospitalId = primaryHospital.getId();
        }
        if (organizationId == null && primaryHospital != null && primaryHospital.getOrganization() != null
            && primaryHospital.getOrganization().getId() != null) {
            organizationId = primaryHospital.getOrganization().getId();
        }
    }
}
