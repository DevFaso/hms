package com.example.hms.fhir.read;

import com.example.hms.fhir.mapper.PatientFhirMapper;
import com.example.hms.repository.PatientRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Loads {@code Patient} rows for the FHIR read and search interactions and
 * maps them to FHIR while the persistence context is still open.
 *
 * <p>{@link PatientFhirMapper#toFhir} walks the LAZY
 * {@code Patient.hospitalRegistrations} (one MRN identifier per
 * registration). {@code spring.jpa.open-in-view} is off and the HAPI servlet
 * opens no transaction, so a provider that loads the entity and maps it
 * itself does so with no session and answers 500
 * ({@code LazyInitializationException}). Loading and mapping therefore happen
 * together here, inside one read-only transaction — the same rule
 * {@code PatientEverythingService} and {@code FhirBulkExportRunner} follow.
 *
 * <p>The mapper sees the whole collection, exactly as it would with the
 * association initialised any other way: this class changes when the
 * registrations are loaded, not which of them are emitted.
 *
 * <p>Tenancy is unchanged and stays where it was: {@code findById} is the
 * tenant-filtered finder of {@code TenantAwareJpaRepository}, the search is
 * the {@code tenantContext}-filtered query, and the provider still consults
 * {@code FhirTenantBoundary} before an {@code _id} lookup.
 */
@Service
public class PatientFhirReadService {

    private final PatientRepository patientRepository;
    private final PatientFhirMapper patientMapper;

    public PatientFhirReadService(PatientRepository patientRepository, PatientFhirMapper patientMapper) {
        this.patientRepository = patientRepository;
        this.patientMapper = patientMapper;
    }

    /**
     * The parameters of {@code PatientRepository.searchPatientsExtended},
     * already shaped by the provider (LIKE patterns, ISO date, the bound
     * hospital); {@code null} means "not constrained".
     */
    public record SearchCriteria(
        String mrn,
        String namePattern,
        String dob,
        String phonePattern,
        String emailPattern,
        UUID hospitalId,
        Boolean active
    ) {}

    /** The patient as a FHIR resource, or empty when the tenant-filtered finder has no such row. */
    @Transactional(readOnly = true)
    public Optional<org.hl7.fhir.r4.model.Patient> read(UUID patientId) {
        return patientRepository.findById(patientId).map(patientMapper::toFhir);
    }

    /** One page of matches, each mapped to a FHIR resource. */
    @Transactional(readOnly = true)
    public List<org.hl7.fhir.r4.model.Patient> search(SearchCriteria criteria, Pageable page) {
        return patientRepository.searchPatientsExtended(
                criteria.mrn(),
                criteria.namePattern(),
                criteria.dob(),
                criteria.phonePattern(),
                criteria.emailPattern(),
                criteria.hospitalId(),
                criteria.active(),
                page)
            .stream()
            .map(patientMapper::toFhir)
            .toList();
    }
}
