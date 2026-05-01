package com.example.hms.service;

import com.example.hms.enums.DataDomain;
import com.example.hms.enums.ShareScope;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientConsent;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.payload.dto.BreakGlassSessionResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientConsentRepository;
import com.example.hms.repository.PatientRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Tiered consent resolution:
 *
 * <pre>
 * Tier 1 -- SAME_HOSPITAL
 *   The requesting hospital IS already a source hospital for this patient.
 *   The patient is registered there -> data is local. No consent lookup needed.
 *
 * Tier 2 -- INTRA_ORG
 *   Both hospitals share the same Organisation.
 *   Walk every sibling hospital in that org for an active consent granting
 *   the requesting hospital access, and pick the first valid one.
 *
 * Tier 3 -- CROSS_ORG
 *   Different organisations. Require an explicit bilateral PatientConsent
 *   from any hospital to the requesting hospital.
 * </pre>
 */
@Service
@Slf4j
public class ConsentResolutionServiceImpl implements ConsentResolutionService {

    private final PatientRepository patientRepository;
    private final HospitalRepository hospitalRepository;
    private final PatientConsentRepository consentRepository;
    /**
     * Optional break-glass dependency. Field-injected so the consent resolver
     * still works in tests / contexts that don't wire a {@link BreakGlassService}.
     * When absent, {@link #resolveForDomain} simply skips the emergency fallback.
     */
    @Autowired(required = false)
    private BreakGlassService breakGlassService;

    public ConsentResolutionServiceImpl(PatientRepository patientRepository,
                                        HospitalRepository hospitalRepository,
                                        PatientConsentRepository consentRepository) {
        this.patientRepository = patientRepository;
        this.hospitalRepository = hospitalRepository;
        this.consentRepository = consentRepository;
    }

    // -- Public entry point -------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public ConsentContext resolve(UUID patientId, UUID requestingHospitalId) {
        Patient patient = patientRepository.findById(patientId)
            .orElseThrow(() -> new ResourceNotFoundException(
                "Patient not found with id: " + patientId));

        Hospital requestingHospital = hospitalRepository.findById(requestingHospitalId)
            .orElseThrow(() -> new ResourceNotFoundException(
                "Requesting hospital not found with id: " + requestingHospitalId));

        return tryTier1SameHospital(patient, requestingHospital)
            .or(() -> tryTier2IntraOrg(patient, requestingHospital))
            .or(() -> tryTier3CrossOrg(patient, requestingHospital))
            .orElseThrow(() -> noConsentException(patient, requestingHospital));
    }

    @Override
    @Transactional
    public ConsentContext resolveForDomain(UUID patientId, UUID requestingHospitalId, DataDomain domain) {
        if (domain == null) {
            throw new BusinessException("Requested data domain must not be null.");
        }

        Patient patient = patientRepository.findById(patientId)
            .orElseThrow(() -> new ResourceNotFoundException(
                "Patient not found with id: " + patientId));

        Hospital requestingHospital = hospitalRepository.findById(requestingHospitalId)
            .orElseThrow(() -> new ResourceNotFoundException(
                "Requesting hospital not found with id: " + requestingHospitalId));

        // SAME_HOSPITAL: data is already local — no domain-scope check applies.
        Optional<ConsentContext> sameHospital = tryTier1SameHospital(patient, requestingHospital);
        if (sameHospital.isPresent()) {
            return sameHospital.get();
        }

        // INTRA / CROSS: a tiered consent must exist AND its scope must cover the domain.
        Optional<ConsentContext> consented = tryTier2IntraOrg(patient, requestingHospital)
            .or(() -> tryTier3CrossOrg(patient, requestingHospital))
            .filter(ctx -> ctx.consent() != null && ctx.consent().coversDomain(domain));
        if (consented.isPresent()) {
            return consented.get();
        }

        // Fall through to break-glass.
        Optional<BreakGlassSessionResponseDTO> bg = (breakGlassService == null)
            ? Optional.empty()
            : breakGlassService.consumeIfLive(patient.getId(), null,
                "Domain access via break-glass: " + domain.name());

        if (bg.isPresent()) {
            log.warn(" [BREAK_GLASS] Patient {} domain {} authorised via session {}.",
                patient.getId(), domain, bg.get().getId());
            return new ConsentContext(
                ShareScope.BREAK_GLASS, requestingHospital, requestingHospital, null, patient);
        }

        throw scopeOrConsentException(patient, requestingHospital, domain);
    }

    private BusinessException scopeOrConsentException(Patient patient, Hospital requestingHospital, DataDomain domain) {
        log.warn(" Domain access denied: patient {} domain {} hospital {} — no covering consent or break-glass.",
            patient.getId(), domain, requestingHospital.getId());
        return new BusinessException(
            "No active consent covers domain '" + domain.name()
                + "' for patient at hospital '" + requestingHospital.getName()
                + "'. Obtain consent or declare break-the-glass access.");
    }

    // -- Tier 1: SAME_HOSPITAL ----------------------------------------------

    private Optional<ConsentContext> tryTier1SameHospital(Patient patient, Hospital requestingHospital) {
        UUID requestingHospitalId = requestingHospital.getId();
        boolean registered = activeRegistrations(patient)
            .stream()
            .anyMatch(reg -> requestingHospitalId.equals(hospitalIdOf(reg)));

        if (registered) {
            log.info(" [SAME_HOSPITAL] Patient {} is registered at requesting hospital {}.",
                patient.getId(), requestingHospitalId);
            return Optional.of(new ConsentContext(
                ShareScope.SAME_HOSPITAL, requestingHospital, requestingHospital, null, patient));
        }
        return Optional.empty();
    }

    // -- Tier 2: INTRA_ORG -------------------------------------------------

    private Optional<ConsentContext> tryTier2IntraOrg(Patient patient, Hospital requestingHospital) {
        Organization org = requestingHospital.getOrganization();
        if (org == null) {
            return Optional.empty();
        }

        Set<UUID> patientHospitalIds = activeRegistrations(patient)
            .stream()
            .map(this::hospitalIdOf)
            .filter(Objects::nonNull)
            .collect(java.util.stream.Collectors.toCollection(HashSet::new));

        return hospitalRepository.findByOrganizationIdOrderByNameAsc(org.getId())
            .stream()
            .filter(h -> !h.getId().equals(requestingHospital.getId()))
            .filter(h -> patientHospitalIds.contains(h.getId()))
            .map(sibling -> findActiveConsent(patient.getId(), sibling.getId(), requestingHospital.getId())
                .map(consent -> {
                    log.info(" [INTRA_ORG] Patient {} via {} -> {} (org {}).",
                        patient.getId(), sibling.getId(), requestingHospital.getId(), org.getId());
                    return new ConsentContext(
                        ShareScope.INTRA_ORG, sibling, requestingHospital, consent, patient);
                }))
            .filter(Optional::isPresent)
            .map(Optional::get)
            .findFirst();
    }

    // -- Tier 3: CROSS_ORG -------------------------------------------------

    private Optional<ConsentContext> tryTier3CrossOrg(Patient patient, Hospital requestingHospital) {
        UUID requestingOrgId = requestingHospital.getOrganization() != null
            ? requestingHospital.getOrganization().getId() : null;

        return activeRegistrations(patient)
            .stream()
            .map(PatientHospitalRegistration::getHospital)
            .filter(Objects::nonNull)
            .filter(h -> !h.getId().equals(requestingHospital.getId()))
            .filter(h -> !sameOrg(h, requestingOrgId))
            .map(source -> findActiveConsent(patient.getId(), source.getId(), requestingHospital.getId())
                .map(consent -> {
                    log.info(" [CROSS_ORG] Patient {} via {} -> {}.",
                        patient.getId(), source.getId(), requestingHospital.getId());
                    return new ConsentContext(
                        ShareScope.CROSS_ORG, source, requestingHospital, consent, patient);
                }))
            .filter(Optional::isPresent)
            .map(Optional::get)
            .findFirst();
    }

    // -- Helpers ------------------------------------------------------------

    private List<PatientHospitalRegistration> activeRegistrations(Patient patient) {
        return patient.getHospitalRegistrations()
            .stream()
            .filter(PatientHospitalRegistration::isActive)
            .toList();
    }

    private UUID hospitalIdOf(PatientHospitalRegistration reg) {
        return reg.getHospital() != null ? reg.getHospital().getId() : null;
    }

    private boolean sameOrg(Hospital hospital, UUID orgId) {
        if (orgId == null || hospital.getOrganization() == null) return false;
        return orgId.equals(hospital.getOrganization().getId());
    }

    private Optional<PatientConsent> findActiveConsent(UUID patientId, UUID fromHospitalId, UUID toHospitalId) {
        return consentRepository
            .findByPatientIdAndFromHospitalIdAndToHospitalId(patientId, fromHospitalId, toHospitalId)
            .filter(PatientConsent::isConsentActive);
    }

    private BusinessException noConsentException(Patient patient, Hospital requestingHospital) {
        log.warn(" No active consent found for patient {} at any tier for requesting hospital {}.",
            patient.getId(), requestingHospital.getId());
        return new BusinessException(
            "No active consent found for this patient to share records with hospital '"
            + requestingHospital.getName() + "'. "
            + "Please obtain patient consent before sharing records.");
    }
}
