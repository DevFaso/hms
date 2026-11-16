package com.example.hms.service.empi;

import com.example.hms.enums.empi.EmpiAliasType;
import com.example.hms.model.Patient;
import com.example.hms.model.empi.EmpiIdentityAlias;
import com.example.hms.model.empi.EmpiMasterIdentity;
import com.example.hms.repository.empi.EmpiIdentityAliasRepository;
import com.example.hms.repository.empi.EmpiMasterIdentityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Internal patient-save path. IU aliases never participate in public alias lookup/matching. */
@Service
@RequiredArgsConstructor
public class PatientNationalUniqueIdAliases {
    private final EmpiIdentityAliasRepository aliases;
    private final EmpiMasterIdentityRepository identities;
    private final com.example.hms.repository.empi.EmpiMergeEventRepository merges;

    @Transactional(propagation = Propagation.MANDATORY)
    public void synchronize(Patient patient) {
        var existing = aliases.findByNationalUniqueIdPatientId(patient.getId());
        if (patient.getNationalUniqueIdHash() == null) {
            existing.ifPresent(alias -> {
                alias.getMasterIdentity().getAliases().removeIf(row -> row.getId().equals(alias.getId()));
                aliases.delete(alias);
            });
            return;
        }
        EmpiIdentityAlias alias = existing.orElseGet(() -> {
            EmpiMasterIdentity identity = identities.findByPatientId(patient.getId()).orElseGet(() ->
                identities.save(EmpiMasterIdentity.builder().patientId(patient.getId())
                    .hospitalId(patient.getHospitalId()).organizationId(patient.getOrganizationId())
                    .empiNumber(issueEmpiNumber()).sourceSystem("HMS_PATIENT_IDENTITY").build()));
            identity = survivingIdentity(identity);
            EmpiIdentityAlias added = EmpiIdentityAlias.builder().masterIdentity(identity)
                .nationalUniqueIdPatientId(patient.getId()).aliasType(EmpiAliasType.NATIONAL_ID)
                .aliasValue(patient.getNationalUniqueIdHash())
                .sourceSystem("HMS_PATIENT_IDENTITY").build();
            identity.addAlias(added);
            return added;
        });
        alias.setAliasValue(patient.getNationalUniqueIdHash());
        alias.setActive(true);
        aliases.save(alias);
    }

    private EmpiMasterIdentity survivingIdentity(EmpiMasterIdentity identity) {
        java.util.Set<java.util.UUID> visited = new java.util.HashSet<>();
        while (visited.add(identity.getId())) {
            var locked = identities.findWithLockById(identity.getId()).orElseThrow(() -> unavailable());
            if (identities.findStatusById(locked.getId()) != com.example.hms.enums.empi.EmpiIdentityStatus.MERGED) return locked;
            identity = merges.findTopBySecondaryIdentity_IdOrderByMergedAtDesc(locked.getId())
                .map(com.example.hms.model.empi.EmpiMergeEvent::getPrimaryIdentity).orElseThrow(() -> unavailable());
        }
        throw unavailable();
    }

    private static com.example.hms.exception.FieldBusinessException unavailable() {
        return new com.example.hms.exception.FieldBusinessException("nationalUniqueId", "patient.iu.unavailable");
    }

    private String issueEmpiNumber() {
        var random = new java.security.SecureRandom();
        for (int attempt = 0; attempt < 25; attempt++) {
            String candidate = String.format(java.util.Locale.ROOT, "EMP-%06d", random.nextInt(1_000_000));
            if (!identities.existsByEmpiNumberIgnoreCase(candidate)) return candidate;
        }
        throw unavailable();
    }
}
