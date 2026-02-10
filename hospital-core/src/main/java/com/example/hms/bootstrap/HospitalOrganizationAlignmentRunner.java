package com.example.hms.bootstrap;

import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
@Profile("!test")
@RequiredArgsConstructor
@Slf4j
public class HospitalOrganizationAlignmentRunner implements ApplicationRunner {

    private static final String KOURITENGA_ORGANIZATION_CODE = "KPL";

    private final HospitalRepository hospitalRepository;
    private final OrganizationRepository organizationRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        organizationRepository.findByCode(KOURITENGA_ORGANIZATION_CODE)
            .ifPresentOrElse(this::linkUnassignedHospitals,
                () -> log.debug("Skipping hospital-to-organization alignment. Organization with code '{}' not found.",
                    KOURITENGA_ORGANIZATION_CODE));
    }

    private void linkUnassignedHospitals(Organization organization) {
        List<Hospital> unassignedHospitals = hospitalRepository.findByOrganizationIsNull();
        if (unassignedHospitals.isEmpty()) {
            log.debug("No unassigned hospitals detected for organization {} ({})", organization.getName(), organization.getCode());
            return;
        }

        unassignedHospitals.forEach(hospital -> hospital.setOrganization(organization));
        hospitalRepository.saveAll(unassignedHospitals);
        log.info("Linked {} hospitals to organization {} ({})", unassignedHospitals.size(), organization.getName(), organization.getCode());
    }
}
