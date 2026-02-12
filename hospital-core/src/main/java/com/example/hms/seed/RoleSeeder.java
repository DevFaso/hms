package com.example.hms.seed;

import com.example.hms.model.Role;
import com.example.hms.repository.RoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "app.seed", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RoleSeeder implements CommandLineRunner {

    private final RoleRepository roleRepository;

    @Override
    public void run(String... args) {
        List<String> rolesToSeed = List.of(
            "ROLE_SUPER_ADMIN",
            "ROLE_HOSPITAL_ADMIN",
            "ROLE_DOCTOR",
            "ROLE_NURSE",
            "ROLE_PHYSICIAN",
            "ROLE_MIDWIFE",
            "ROLE_ANESTHESIOLOGIST",
            "ROLE_LAB_SCIENTIST",
            "ROLE_PHARMACIST",
            "ROLE_RECEPTIONIST",
            "ROLE_ADMIN",
            "ROLE_USER",
            "ROLE_MODERATOR",
            "ROLE_PATIENT",
            "ROLE_ACCOUNTANT",
            "ROLE_TECHNICIAN",
            "ROLE_RADIOLOGIST",
            "ROLE_SURGEON",
            "ROLE_BILLING_SPECIALIST",
            "ROLE_PHYSIOTHERAPIST",
            "ROLE_CLEANER",
            "ROLE_SECURITY",
            "ROLE_SUPPORT",
            "ROLE_MANAGER"
        );

        rolesToSeed.forEach(roleCode -> {
            boolean existsByCode = roleRepository.findByCode(roleCode).isPresent();
            boolean existsByName = roleRepository.findByNameIgnoreCase(roleCode).isPresent();

            if (existsByCode || existsByName) {
                if (log.isDebugEnabled()) {
                    log.debug("Skipping seed for role {} because it already exists", roleCode);
                }
                return;
            }

            Role role = Role.builder()
                .name(roleCode.toUpperCase())
                .code(roleCode.toUpperCase())
                .build();
            log.info("Seeding missing role: {}", roleCode.toUpperCase());
            roleRepository.save(role);
        });
    }
}
