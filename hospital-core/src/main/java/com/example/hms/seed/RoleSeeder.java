package com.example.hms.seed;

import com.example.hms.model.Role;
import com.example.hms.repository.RoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Seeds the 24 reference roles for local-h2 / local profiles where
 * Liquibase is disabled. In dev / uat / prod the same data is loaded
 * by Liquibase migration V4__seed_roles.sql.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@Order(1) // roles must be seeded before assignments
@Profile({"local-h2", "local"})
@ConditionalOnProperty(prefix = "app.seed", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RoleSeeder implements CommandLineRunner {

    private final RoleRepository roleRepository;

    @Override
    public void run(String... args) {
        Map<String, String> rolesToSeed = buildRoleCatalog();

        rolesToSeed.forEach((roleCode, description) -> {
            Optional<Role> existingByCode = roleRepository.findByCode(roleCode);
            Optional<Role> existingByName = roleRepository.findByNameIgnoreCase(roleCode);

            if (existingByCode.isPresent()) {
                // Back-fill description on existing roles that lack one
                Role existing = existingByCode.get();
                if (existing.getDescription() == null || existing.getDescription().isBlank()) {
                    existing.setDescription(description);
                    roleRepository.save(existing);
                    log.info("Back-filled description for role: {}", roleCode);
                }
                return;
            }

            if (existingByName.isPresent()) {
                Role existing = existingByName.get();
                if (existing.getDescription() == null || existing.getDescription().isBlank()) {
                    existing.setDescription(description);
                    roleRepository.save(existing);
                    log.info("Back-filled description for role: {}", roleCode);
                }
                return;
            }

            Role role = Role.builder()
                .name(roleCode.toUpperCase())
                .code(roleCode.toUpperCase())
                .description(description)
                .build();
            log.info("Seeding missing role: {}", roleCode.toUpperCase());
            roleRepository.save(role);
        });
    }

    private static Map<String, String> buildRoleCatalog() {
        Map<String, String> roles = new LinkedHashMap<>();
        roles.put("ROLE_SUPER_ADMIN",        "System-wide super administrator with full access to all organizations, hospitals, and system settings");
        roles.put("ROLE_HOSPITAL_ADMIN",     "Hospital administrator responsible for managing staff, departments, billing, and hospital-level configuration");
        roles.put("ROLE_DOCTOR",             "Licensed physician authorized to diagnose, prescribe, order tests, and manage patient treatment plans");
        roles.put("ROLE_NURSE",              "Registered nurse providing patient care, administering medications, and documenting vital signs");
        roles.put("ROLE_PHYSICIAN",          "General physician with clinical privileges for patient consultation and treatment");
        roles.put("ROLE_MIDWIFE",            "Certified midwife specializing in maternal care, labor support, and newborn assessment");
        roles.put("ROLE_ANESTHESIOLOGIST",   "Anesthesia specialist managing pre-operative assessment, sedation, and pain management");
        roles.put("ROLE_LAB_SCIENTIST",      "Laboratory scientist responsible for processing specimens, running tests, and publishing results");
        roles.put("ROLE_PHARMACIST",         "Licensed pharmacist handling medication dispensing, drug interaction checks, and inventory management");
        roles.put("ROLE_RECEPTIONIST",       "Front-desk receptionist managing patient check-in, appointments, and visitor coordination");
        roles.put("ROLE_ADMIN",              "General administrative user with elevated operational privileges");
        roles.put("ROLE_USER",               "Standard system user with basic access permissions");
        roles.put("ROLE_MODERATOR",          "Content and activity moderator with oversight and review capabilities");
        roles.put("ROLE_PATIENT",            "Registered patient with self-service access to medical records, appointments, and prescriptions");
        roles.put("ROLE_ACCOUNTANT",         "Financial accountant managing ledger entries, expense tracking, and financial reconciliation");
        roles.put("ROLE_TECHNICIAN",         "Medical or IT technician responsible for equipment maintenance and technical support");
        roles.put("ROLE_RADIOLOGIST",        "Imaging specialist interpreting X-rays, CT scans, MRIs, and generating radiology reports");
        roles.put("ROLE_SURGEON",            "Surgical specialist performing operations, managing surgical plans, and post-operative care");
        roles.put("ROLE_BILLING_SPECIALIST", "Billing specialist handling invoicing, insurance claims, payment processing, and financial reports");
        roles.put("ROLE_PHYSIOTHERAPIST",    "Physical therapist designing rehabilitation programs, therapy sessions, and mobility assessments");
        roles.put("ROLE_CLEANER",            "Facility maintenance staff responsible for sanitation and cleanliness of hospital premises");
        roles.put("ROLE_SECURITY",           "Security personnel managing access control, surveillance, and facility safety protocols");
        roles.put("ROLE_SUPPORT",            "Technical or customer support staff assisting users with system issues and inquiries");
        roles.put("ROLE_MANAGER",            "Department or operational manager overseeing staff coordination and resource planning");
        return roles;
    }
}
