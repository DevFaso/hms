package com.example.hms.service.empi;

import com.example.hms.enums.empi.EmpiAliasType;
import com.example.hms.enums.empi.EmpiIdentityStatus;
import com.example.hms.enums.empi.EmpiMergeType;
import com.example.hms.enums.empi.EmpiResolutionState;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.EmpiMapper;
import com.example.hms.model.empi.EmpiIdentityAlias;
import com.example.hms.model.empi.EmpiMasterIdentity;
import com.example.hms.model.empi.EmpiMergeEvent;
import com.example.hms.payload.dto.empi.EmpiAliasRequestDTO;
import com.example.hms.payload.dto.empi.EmpiIdentityAliasDTO;
import com.example.hms.payload.dto.empi.EmpiIdentityLinkRequestDTO;
import com.example.hms.payload.dto.empi.EmpiIdentityResponseDTO;
import com.example.hms.payload.dto.empi.EmpiMergeEventResponseDTO;
import com.example.hms.payload.dto.empi.EmpiMergeRequestDTO;
import com.example.hms.payload.event.EmpiEventPayload;
import com.example.hms.repository.empi.EmpiIdentityAliasRepository;
import com.example.hms.repository.empi.EmpiMasterIdentityRepository;
import com.example.hms.repository.empi.EmpiMergeEventRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.TransactionCallbacks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmpiServiceImpl implements EmpiService, EmpiAuthorisedMergePort {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String EVENT_IDENTITY_LINKED = "IDENTITY_LINKED";
    private static final String EVENT_ALIAS_CREATED = "IDENTITY_ALIAS_CREATED";
    private static final String EVENT_IDENTITY_MERGED = "IDENTITIES_MERGED";

    private static final String MSG_IDENTITY_NOT_FOUND = "empi.identity.notFound";
    private static final String MSG_IDENTITY_NOT_FOUND_BY_NUMBER = "empi.identity.notFoundByNumber";
    private static final String MSG_ALIAS_EXISTS = "empi.alias.exists";
    private static final String MSG_ALIAS_NOT_FOUND = "empi.alias.notFound";
    private static final String MSG_MERGE_SAME_IDENTITY = "empi.merge.sameIdentity";
    private static final String MSG_MERGE_ALREADY_MERGED = "empi.merge.alreadyMerged";
    private static final String MSG_MERGE_CROSS_TENANT = "empi.merge.crossTenant";
    private static final String MSG_MERGE_IDENTITY_NOT_FOUND = "empi.merge.identityNotFound";
    private static final String MSG_MERGE_SAME_PATIENT = "empi.merge.samePatient";
    private static final String MSG_LINK_MISSING_PATIENT = "empi.link.missingPatient";
    private static final String MSG_LINK_ALIAS_INCOMPLETE = "empi.link.aliasIncomplete";
    private static final String MSG_ALIAS_INVALID = "empi.alias.invalid";
    private static final String MSG_ALIAS_ORPHANED = "empi.alias.orphaned";
    private static final String MSG_LOOKUP_INVALID_EMPI = "empi.lookup.invalidEmpi";

    private final EmpiMasterIdentityRepository masterIdentityRepository;
    private final EmpiIdentityAliasRepository aliasRepository;
    private final EmpiMergeEventRepository mergeEventRepository;
    private final com.example.hms.repository.PatientRepository patientRepository;
    private final com.example.hms.service.AuditEventLogService auditEventLogService;
    private final EmpiMapper empiMapper;
    private final com.example.hms.utility.RoleValidator roleValidator;
    private final com.example.hms.repository.PatientHospitalRegistrationRepository registrationRepository;
    private final com.example.hms.config.KafkaProperties kafkaProperties;
    private final ObjectProvider<KafkaTemplate<String, EmpiEventPayload>> empiKafkaTemplateProvider;

    @Override
    @Transactional
    public EmpiIdentityResponseDTO linkIdentity(EmpiIdentityLinkRequestDTO request) {
        validateLinkRequest(request);
        HospitalContext context = HospitalContextHolder.getContextOrEmpty();

        EmpiMasterIdentity identity = locateExistingIdentity(request)
            .map(existing -> ensurePatientOwnership(existing, request))
            .orElseGet(() -> initializeIdentity(request, context));

        boolean updated = updateIdentityFromRequest(identity, request, context);
        boolean aliasAdded = maybeAttachAlias(identity, request, context);

        if (!updated && !aliasAdded && identity.getId() != null) {
            // No-op: identity already satisfied request
            return empiMapper.toIdentityDto(identity);
        }

        EmpiMasterIdentity saved = masterIdentityRepository.save(identity);
        publishEvent(buildIdentityEventPayload(saved, aliasAdded ? EVENT_ALIAS_CREATED : EVENT_IDENTITY_LINKED));
        return empiMapper.toIdentityDto(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public EmpiIdentityResponseDTO getIdentity(UUID identityId) {
        EmpiMasterIdentity identity = masterIdentityRepository.findById(identityId)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_IDENTITY_NOT_FOUND, identityId));
        return empiMapper.toIdentityDto(identity);
    }

    @Override
    @Transactional(readOnly = true)
    public EmpiIdentityResponseDTO getIdentityByEmpiNumber(String empiNumber) {
        EmpiMasterIdentity identity = masterIdentityRepository.findByEmpiNumberIgnoreCase(normalizeEmpiNumber(empiNumber))
            .orElseThrow(() -> new ResourceNotFoundException(MSG_IDENTITY_NOT_FOUND_BY_NUMBER, empiNumber));
        return empiMapper.toIdentityDto(identity);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<EmpiIdentityResponseDTO> findIdentityByPatientId(UUID patientId) {
        // ── Tenant isolation: an identity row carries the patient's EMPI number
        // and cross-facility affiliation, so another hospital's row must read as
        // absent rather than as data. EmpiMasterIdentityRepository is an unscoped
        // JpaRepository, so nothing below this filters by tenant. ──
        CallerScope scope = callerScope();
        return masterIdentityRepository.findByPatientId(patientId)
            .filter(scope::sees)
            .map(empiMapper::toIdentityDto);
    }

    /**
     * The caller's reach over master identities: one pinned hospital, or the
     * whole index for a VERIFIED super-admin in global view.
     *
     * <p>A scoped caller sees only identities stamped with their own hospital —
     * and NOT unstamped ones: a legacy row with a null {@code hospitalId}
     * belongs to nobody in particular, and handing it to whichever tenant asks
     * first is the same disclosure by a different route. The verified
     * super-admin remains able to reconcile those.
     *
     * <p>A null {@code hospitalId} WITHOUT {@code verifiedGlobalView} sees
     * nothing: every identity reads as absent, so each caller path refuses it
     * with the answer it already gives for a miss.
     */
    private record CallerScope(UUID hospitalId, boolean verifiedGlobalView) {
        boolean sees(EmpiMasterIdentity identity) {
            if (verifiedGlobalView) {
                return true;
            }
            return hospitalId != null && hospitalId.equals(identity.getHospitalId());
        }
    }

    /**
     * {@code requireActiveHospitalId()}, without the road that lets an
     * unverified principal read a null scope as "unscoped".
     *
     * <p>{@code requireActiveHospitalId()} returns null two ways: step 1, a real
     * super-admin in global view ({@code HospitalContext.isSuperAdmin()}, which
     * is what {@code isSuperAdminFromJwtClaim()} reads), and step 4, a safety
     * net keyed on the AUTHORITIES collection, which the RoleValidator javadoc
     * warns can be inflated. Only the verified flag makes a null scope global
     * view — the stance of #746, #750 and #751.
     */
    private CallerScope callerScope() {
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        boolean verifiedGlobalView = activeHospitalId == null && roleValidator.isSuperAdminFromJwtClaim();
        return new CallerScope(activeHospitalId, verifiedGlobalView);
    }

    /**
     * The identity, only when the caller may act on it; otherwise empty, the
     * same empty a nonexistent id gives.
     *
     * <p>The merge endpoints are {@code HOSPITAL_ADMIN}-or-better, but
     * HOSPITAL_ADMIN is a PER-HOSPITAL role — so "is an admin" was never the same
     * question as "is an admin HERE". {@code findById} is scoped by
     * {@code TenantScopeSpecification} to every hospital the caller is PERMITTED
     * at, which is wider than the active one, so the visibility check is still
     * needed on top of it.
     */
    private Optional<EmpiMasterIdentity> findVisibleIdentity(UUID identityId, CallerScope scope) {
        return masterIdentityRepository.findById(identityId)
            .filter(scope::sees);
    }

    /**
     * Refuse a merge against a patient not registered at the caller's hospital.
     *
     * <p>Needed in addition to {@link #findVisibleIdentity} because
     * {@code mergePatients} PROVISIONS a master identity for any patient that
     * lacks one — a write against another tenant's patient that happened before
     * any identity-level guard could run.
     *
     * <p>Registration, not {@code Patient.hospitalId}, is what lets a hospital
     * START a merge: a patient may be registered at several hospitals. But only
     * the patient's home hospital can FINISH one today:
     * {@code ensureIdentityForPatient} stamps a provisioned identity with
     * {@code Patient.hospitalId}, and {@code mergeIdentities} admits only
     * identities stamped with the caller's hospital, so any other hospital the
     * patient is registered at gets the identity-not-found refusal there.
     * Which hospital owns a master identity is an open design question.
     *
     * <p>Any registration counts, active or not: the commonest duplicate is a
     * discharged patient who returns and is registered again, and the
     * hospital that discharged them must be able to reconcile the two.
     *
     * <p>A null scope skips the check only for a verified super-admin in global
     * view; an unverified one gets the refusal a foreign patient gets.
     */
    private void requirePatientInTenant(UUID patientId, CallerScope scope) {
        if (scope.verifiedGlobalView()) {
            return;
        }
        if (scope.hospitalId() == null
            || !registrationRepository.existsByPatientIdAndHospitalId(patientId, scope.hospitalId())) {
            throw new org.springframework.security.access.AccessDeniedException(
                MessageUtil.resolve(MSG_MERGE_CROSS_TENANT));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<EmpiIdentityResponseDTO> findIdentityByAlias(EmpiAliasType aliasType, String aliasValue) {
        if (aliasType == null || !StringUtils.hasText(aliasValue)) {
            return Optional.empty();
        }
        return aliasRepository.findByAliasTypeAndAliasValueIgnoreCase(aliasType, aliasValue.trim())
            .map(EmpiIdentityAlias::getMasterIdentity)
            .map(empiMapper::toIdentityDto);
    }

    @Override
    @Transactional
    public EmpiIdentityAliasDTO addAlias(UUID identityId, EmpiAliasRequestDTO request) {
        validateAliasRequest(request);
        EmpiMasterIdentity identity = masterIdentityRepository.findById(identityId)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_IDENTITY_NOT_FOUND, identityId));

        if (aliasRepository.existsByAliasTypeAndAliasValueIgnoreCase(request.getAliasType(), request.getAliasValue())) {
            throw new BusinessException(MessageUtil.resolve(MSG_ALIAS_EXISTS, request.getAliasValue()));
        }

        EmpiIdentityAlias alias = empiMapper.createAliasFromRequest(request);
        HospitalContext context = HospitalContextHolder.getContextOrEmpty();
        alias.setCreatedBy(context.getPrincipalUserId());
        identity.addAlias(alias);

        EmpiMasterIdentity saved = masterIdentityRepository.save(identity);
        publishEvent(buildIdentityEventPayload(saved, EVENT_ALIAS_CREATED));
        return empiMapper.toAliasDto(alias);
    }

    @Override
    @Transactional
    public void removeAlias(UUID identityId, UUID aliasId) {
        EmpiMasterIdentity identity = masterIdentityRepository.findById(identityId)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_IDENTITY_NOT_FOUND, identityId));

        boolean removed = identity.getAliases().removeIf(alias -> aliasId.equals(alias.getId()));
        if (!removed) {
            throw new ResourceNotFoundException(MSG_ALIAS_NOT_FOUND, aliasId);
        }
        masterIdentityRepository.save(identity);
    }

    @Override
    @Transactional
    public EmpiMergeEventResponseDTO mergeIdentities(UUID primaryIdentityId, EmpiMergeRequestDTO request) {
        return mergeIdentities(primaryIdentityId, request, callerScope());
    }

    private EmpiMergeEventResponseDTO mergeIdentities(UUID primaryIdentityId, EmpiMergeRequestDTO request,
                                                      CallerScope scope) {
        // ── Tenant isolation, with no oracle. BOTH sides must belong to the
        // caller: the identity-to-identity check further down only proves the
        // two agree with each other, and two hospital-B identities agree
        // perfectly. And an identity the caller may not see answers EXACTLY like
        // one that does not exist — same status, same body, naming neither id —
        // or a hospital-A admin pairs their own identity with candidate UUIDs
        // and reads off which exist at other hospitals (partial ownership is not
        // partial permission; the HL7 A40 rule, #738). Both lookups run before
        // either is judged, so owning one side costs what owning neither does.
        // An unverified null scope sees nothing, so it lands here too. ──
        return mergeVisibleIdentities(
            findVisibleIdentity(primaryIdentityId, scope),
            findVisibleIdentity(request.getSecondaryIdentityId(), scope),
            request);
    }

    /**
     * The merge proper, given both sides already filtered through the caller's
     * {@link CallerScope}: an empty side is one the caller may not see, or one
     * that does not exist, and the two answer identically.
     */
    private EmpiMergeEventResponseDTO mergeVisibleIdentities(Optional<EmpiMasterIdentity> primaryLookup,
                                                             Optional<EmpiMasterIdentity> secondaryLookup,
                                                             EmpiMergeRequestDTO request) {
        if (primaryLookup.isEmpty() || secondaryLookup.isEmpty()) {
            throw new ResourceNotFoundException(MSG_MERGE_IDENTITY_NOT_FOUND);
        }
        EmpiMasterIdentity primary = primaryLookup.get();
        EmpiMasterIdentity secondary = secondaryLookup.get();

        if (primary.getId().equals(secondary.getId())) {
            throw new BusinessException(MessageUtil.resolve(MSG_MERGE_SAME_IDENTITY));
        }
        if (secondary.getStatus() == EmpiIdentityStatus.MERGED) {
            throw alreadyMerged(secondary);
        }
        // ── Tenant isolation (empi-identity skill: v0 merges are intra-tenant).
        // Identities with no hospital stamp (legacy/system rows) are exempt. ──
        if (primary.getHospitalId() != null && secondary.getHospitalId() != null
            && !primary.getHospitalId().equals(secondary.getHospitalId())) {
            throw new BusinessException(MessageUtil.resolve(MSG_MERGE_CROSS_TENANT));
        }

        // ── The transition itself, decided by the database. The status read
        // above is a fast path, not a guarantee: two merges of the same pair
        // running at once both read ACTIVE. Whichever claims the row second
        // gets 0 and the same refusal as a merge that arrived after the
        // first had committed — so one merge event, one IDENTITIES_MERGED
        // event and one PATIENT_MERGE audit row, however many raced. ──
        if (masterIdentityRepository.claimForMerge(secondary.getId(), EmpiIdentityStatus.MERGED) == 0) {
            throw alreadyMerged(secondary);
        }

        HospitalContext context = HospitalContextHolder.getContextOrEmpty();
        EmpiMergeEvent mergeEvent = buildMergeEvent(request, primary, secondary, context);

        secondary.setStatus(EmpiIdentityStatus.MERGED);
        secondary.setResolutionState(EmpiResolutionState.CONFIRMED);
        secondary.setActive(false);
        secondary.setUpdatedBy(context.getPrincipalUserId());
        primary.setUpdatedBy(context.getPrincipalUserId());

        // Skill merge-step 2 (previously unimplemented): reassign the
        // secondary's aliases to the surviving identity so post-merge
        // lookups (e.g. MLLP by MRN) resolve to the primary instead of the
        // merged-away row. Duplicates on (type, value) are deactivated.
        reassignAliases(primary, secondary, context);

        mergeEventRepository.save(mergeEvent);
        masterIdentityRepository.save(secondary);
        masterIdentityRepository.save(primary);
        // Flush HERE, inside the call: a constraint or lock failure then
        // surfaces as this method's exception, where the caller can still
        // answer it as a refusal. Left to the caller's commit, it would
        // arrive after the caller had already decided the merge succeeded.
        masterIdentityRepository.flush();

        publishEvent(buildMergeEventPayload(primary, secondary, mergeEvent));
        // After commit, like the Kafka event: a SUCCESS row written now, in
        // the audit service's own REQUIRES_NEW transaction, would survive this
        // transaction rolling back and record a merge that never happened.
        UUID primaryId = primary.getId();
        String primaryEmpiNumber = primary.getEmpiNumber();
        String secondaryEmpiNumber = secondary.getEmpiNumber();
        EmpiMergeType mergeType = mergeEvent.getMergeType();
        TransactionCallbacks.afterCommit(() ->
            emitMergeAudit(primaryId, primaryEmpiNumber, secondaryEmpiNumber, mergeType));
        return empiMapper.toMergeEventDto(mergeEvent);
    }

    @Override
    @Transactional
    public EmpiMergeEventResponseDTO mergePatients(UUID primaryPatientId, UUID secondaryPatientId,
                                                   com.example.hms.enums.empi.EmpiMergeType mergeType, String notes) {
        requireTwoDistinctPatients(primaryPatientId, secondaryPatientId);
        return mergePatientsInScope(primaryPatientId, secondaryPatientId, mergeType, notes, callerScope());
    }

    /**
     * See {@link EmpiAuthorisedMergePort}: the inbound
     * HL7 A40 path, which has no request context to resolve a scope from and
     * has already settled which hospital it acts at.
     *
     * <p>The scope built here is the one a caller PINNED to that hospital gets
     * — never {@code verifiedGlobalView} — so every rule below still applies:
     * both patients registered there, both identities stamped with it.
     */
    @Override
    @Transactional
    public EmpiMergeEventResponseDTO mergePatientsAtAuthorisedHospital(UUID actingHospitalId,
                                                                       UUID primaryPatientId, UUID secondaryPatientId,
                                                                       com.example.hms.enums.empi.EmpiMergeType mergeType,
                                                                       String notes) {
        if (actingHospitalId == null) {
            // A programming error, not a refusal: a null here would otherwise
            // read as "no scope", which sees nothing, and fail as a not-found
            // that hides the bug.
            throw new IllegalArgumentException("actingHospitalId is required");
        }
        requireTwoDistinctPatients(primaryPatientId, secondaryPatientId);
        return mergePatientsInScope(primaryPatientId, secondaryPatientId, mergeType, notes,
            new CallerScope(actingHospitalId, false));
    }

    private static void requireTwoDistinctPatients(UUID primaryPatientId, UUID secondaryPatientId) {
        if (primaryPatientId == null || secondaryPatientId == null) {
            throw new BusinessException(MessageUtil.resolve(MSG_LINK_MISSING_PATIENT));
        }
        if (primaryPatientId.equals(secondaryPatientId)) {
            throw new BusinessException(MessageUtil.resolve(MSG_MERGE_SAME_PATIENT));
        }
    }

    private EmpiMergeEventResponseDTO mergePatientsInScope(UUID primaryPatientId, UUID secondaryPatientId,
                                                           com.example.hms.enums.empi.EmpiMergeType mergeType,
                                                           String notes, CallerScope scope) {
        // ── Tenant isolation BEFORE provisioning: ensureIdentityForPatient
        // creates a master identity (and emits IDENTITY_LINKED) for any patient
        // that lacks one. Deferring the check to mergeIdentities would leave that
        // write already done against another tenant's patient. ──
        requirePatientInTenant(primaryPatientId, scope);
        requirePatientInTenant(secondaryPatientId, scope);

        EmpiMasterIdentity primary = ensureIdentityForPatient(primaryPatientId);
        EmpiMasterIdentity secondary = ensureIdentityForPatient(secondaryPatientId);

        EmpiMergeRequestDTO request = new EmpiMergeRequestDTO();
        request.setSecondaryIdentityId(secondary.getId());
        request.setMergeType(mergeType != null ? mergeType : com.example.hms.enums.empi.EmpiMergeType.MANUAL);
        request.setNotes(notes);
        // The identities just loaded by patient id, judged by the same scope,
        // rather than read again by identity id. A re-read goes through
        // TenantAwareJpaRepository.findById, whose TenantScopeSpecification
        // answers empty on a thread with no HospitalContext — so on the MLLP
        // worker every merge would fail as identity-not-found. The rule this
        // merge is held to is CallerScope.sees (stamped with THE one hospital
        // the scope names, or a verified global view), which is narrower than
        // the specification's any-permitted-hospital-or-organisation filter.
        return mergeVisibleIdentities(
            Optional.of(primary).filter(scope::sees),
            Optional.of(secondary).filter(scope::sees),
            request);
    }

    /**
     * Find or provision the master identity for a patient — the explicit
     * admin provisioning flow the empi-identity skill permits (never the
     * unknown-alias auto-create path). Reuses {@link #linkIdentity} so the
     * IDENTITY_LINKED event and hospital stamping behave identically.
     */
    private EmpiMasterIdentity ensureIdentityForPatient(UUID patientId) {
        Optional<EmpiMasterIdentity> existing = masterIdentityRepository.findByPatientId(patientId);
        if (existing.isPresent()) {
            return existing.get();
        }
        com.example.hms.model.Patient patient = patientRepository.findByIdUnscoped(patientId)
            .orElseThrow(() -> new ResourceNotFoundException("patient.notFound", patientId));
        EmpiIdentityLinkRequestDTO link = new EmpiIdentityLinkRequestDTO();
        link.setPatientId(patient.getId());
        link.setHospitalId(patient.getHospitalId());
        link.setOrganizationId(patient.getOrganizationId());
        link.setSourceSystem("HMS_MERGE_PROVISION");
        linkIdentity(link);
        return masterIdentityRepository.findByPatientId(patientId)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_IDENTITY_NOT_FOUND, patientId));
    }

    /** Move active aliases to the primary; deactivate (type, value) duplicates. */
    private void reassignAliases(EmpiMasterIdentity primary, EmpiMasterIdentity secondary, HospitalContext context) {
        java.util.List<EmpiIdentityAlias> primaryAliases = aliasRepository.findByMasterIdentity_Id(primary.getId());
        for (EmpiIdentityAlias alias : aliasRepository.findByMasterIdentity_Id(secondary.getId())) {
            boolean duplicate = primaryAliases.stream().anyMatch(existing ->
                existing.getAliasType() == alias.getAliasType()
                    && existing.getAliasValue() != null
                    && existing.getAliasValue().equalsIgnoreCase(alias.getAliasValue()));
            if (duplicate) {
                alias.setActive(false);
            } else {
                alias.setMasterIdentity(primary);
            }
            alias.setUpdatedBy(context.getPrincipalUserId());
            aliasRepository.save(alias);
        }
    }

    private static BusinessException alreadyMerged(EmpiMasterIdentity secondary) {
        return new BusinessException(MessageUtil.resolve(MSG_MERGE_ALREADY_MERGED, secondary.getEmpiNumber()));
    }

    /**
     * Skill merge-step 5: PATIENT_MERGE audit trail — best-effort, never rolls
     * back the merge. Runs after commit, so it takes values, not entities.
     */
    private void emitMergeAudit(UUID primaryId, String primaryEmpiNumber, String secondaryEmpiNumber,
                                EmpiMergeType mergeType) {
        try {
            auditEventLogService.logEvent(com.example.hms.payload.dto.AuditEventRequestDTO.builder()
                .eventType(com.example.hms.enums.AuditEventType.PATIENT_MERGE)
                .status(com.example.hms.enums.AuditStatus.SUCCESS)
                .eventDescription("EMPI merge: " + secondaryEmpiNumber
                    + " merged into " + primaryEmpiNumber
                    + " (" + mergeType + ")")
                .entityType("EmpiMasterIdentity")
                .resourceId(primaryId != null ? primaryId.toString() : null)
                .build());
        } catch (RuntimeException ex) {
            log.warn("Failed to emit PATIENT_MERGE audit event: {}", ex.getMessage());
        }
    }

    private void validateLinkRequest(EmpiIdentityLinkRequestDTO request) {
        if (request == null) {
            throw new BusinessException("EMPI link request is required");
        }
        if (request.getPatientId() == null) {
            throw new BusinessException(MessageUtil.resolve(MSG_LINK_MISSING_PATIENT));
        }
        boolean hasAliasType = request.getAliasType() != null;
        boolean hasAliasValue = StringUtils.hasText(request.getAliasValue());
        if (hasAliasType != hasAliasValue) {
            throw new BusinessException(MessageUtil.resolve(MSG_LINK_ALIAS_INCOMPLETE));
        }
    }

    private void validateAliasRequest(EmpiAliasRequestDTO request) {
        if (request == null) {
            throw new BusinessException("Alias request is required");
        }
        if (request.getAliasType() == null || !StringUtils.hasText(request.getAliasValue())) {
            throw new BusinessException(MessageUtil.resolve(MSG_ALIAS_INVALID));
        }
    }

    private EmpiMasterIdentity ensurePatientOwnership(EmpiMasterIdentity identity,
                                                      EmpiIdentityLinkRequestDTO request) {
        if (identity == null) {
            return null;
        }
        UUID existingPatientId = identity.getPatientId();
        UUID requestPatientId = request.getPatientId();
        if (existingPatientId != null && requestPatientId != null && !existingPatientId.equals(requestPatientId)) {
            String aliasValue = StringUtils.hasText(request.getAliasValue())
                ? request.getAliasValue().trim()
                : identity.getEmpiNumber();
            throw new BusinessException(MessageUtil.resolve(MSG_ALIAS_EXISTS, aliasValue));
        }
        return identity;
    }

    private Optional<EmpiMasterIdentity> locateExistingIdentity(EmpiIdentityLinkRequestDTO request) {
        Optional<EmpiMasterIdentity> byPatient = masterIdentityRepository.findByPatientId(request.getPatientId());
        if (byPatient.isPresent()) {
            return byPatient;
        }
        if (request.getAliasType() == null || !StringUtils.hasText(request.getAliasValue())) {
            return Optional.empty();
        }
        return aliasRepository.findByAliasTypeAndAliasValueIgnoreCase(request.getAliasType(), request.getAliasValue().trim())
            .map(alias -> {
                EmpiMasterIdentity master = alias.getMasterIdentity();
                if (master == null) {
                    throw new BusinessException(MessageUtil.resolve(MSG_ALIAS_ORPHANED));
                }
                return master;
            });
    }

    private EmpiMasterIdentity initializeIdentity(EmpiIdentityLinkRequestDTO request, HospitalContext context) {
        EmpiMasterIdentity identity = empiMapper.initializeIdentity(request);
        if (identity == null) {
            // MapStruct maps null source -> null result, so this means the
            // request was null: a programming error upstream, not a client
            // error. Fail with something diagnosable instead of an opaque NPE
            // on the next line.
            throw new IllegalStateException(
                "EMPI identity could not be initialised — no link request was supplied.");
        }
        identity.setEmpiNumber(generateEmpiNumber());
        identity.setCreatedBy(context.getPrincipalUserId());
        identity.setUpdatedBy(context.getPrincipalUserId());
        return identity;
    }

    private boolean updateIdentityFromRequest(EmpiMasterIdentity identity,
                                              EmpiIdentityLinkRequestDTO request,
                                              HospitalContext context) {
        UUID previousPatientId = identity.getPatientId();
        empiMapper.updateIdentityFromLinkRequest(request, identity);
        identity.setUpdatedBy(context.getPrincipalUserId());
        return previousPatientId == null || !previousPatientId.equals(identity.getPatientId());
    }

    private boolean maybeAttachAlias(EmpiMasterIdentity identity,
                                     EmpiIdentityLinkRequestDTO request,
                                     HospitalContext context) {
        if (request.getAliasType() == null || !StringUtils.hasText(request.getAliasValue())) {
            return false;
        }

        String normalizedValue = request.getAliasValue().trim();
        boolean aliasExists = identity.getAliases().stream()
            .anyMatch(existing -> existing.getAliasType() == request.getAliasType()
                && normalizedValue.equalsIgnoreCase(existing.getAliasValue()));
        if (aliasExists) {
            return false;
        }

        if (aliasRepository.existsByAliasTypeAndAliasValueIgnoreCase(request.getAliasType(), normalizedValue)) {
            throw new BusinessException(MessageUtil.resolve(MSG_ALIAS_EXISTS, normalizedValue));
        }

        EmpiAliasRequestDTO aliasDto = new EmpiAliasRequestDTO();
        aliasDto.setAliasType(request.getAliasType());
        aliasDto.setAliasValue(normalizedValue);
        aliasDto.setSourceSystem(request.getAliasSourceSystem());

        EmpiIdentityAlias alias = empiMapper.createAliasFromRequest(aliasDto);
        alias.setCreatedBy(context.getPrincipalUserId());
        identity.addAlias(alias);
        return true;
    }

    private EmpiMergeEvent buildMergeEvent(EmpiMergeRequestDTO request,
                                           EmpiMasterIdentity primary,
                                           EmpiMasterIdentity secondary,
                                           HospitalContext context) {
        UUID organizationId = Optional.ofNullable(primary.getOrganizationId())
            .or(() -> Optional.ofNullable(secondary.getOrganizationId()))
            .orElse(context.getActiveOrganizationId());
        UUID hospitalId = Optional.ofNullable(primary.getHospitalId())
            .or(() -> Optional.ofNullable(secondary.getHospitalId()))
            .orElse(context.getActiveHospitalId());
        UUID departmentId = Optional.ofNullable(primary.getDepartmentId())
            .or(() -> Optional.ofNullable(secondary.getDepartmentId()))
            .orElseGet(() -> context.getPermittedDepartmentIds().stream().min(Comparator.naturalOrder()).orElse(null));

        return EmpiMergeEvent.builder()
            .primaryIdentity(primary)
            .secondaryIdentity(secondary)
            .organizationId(organizationId)
            .hospitalId(hospitalId)
            .departmentId(departmentId)
            .mergeType(request.getMergeType())
            .resolution(request.getResolution())
            .notes(request.getNotes())
            .undoToken(UUID.randomUUID().toString())
            .mergedBy(context.getPrincipalUserId())
            .mergedAt(OffsetDateTime.now())
            .build();
    }

    /**
     * Send an EMPI event once the surrounding transaction COMMITS.
     *
     * <p>Every caller runs inside a transaction — and {@code mergePatients}
     * publishes {@code IDENTITY_LINKED} while provisioning, before
     * {@code mergeIdentities} can still refuse, and a caller may run it inside
     * its own wider transaction that rolls back afterwards. A send made inline
     * would leave consumers holding an event for a change a rollback erased.
     * Deferring here, the one place every event passes, keeps every present
     * and future caller on the same timing. The send itself stays
     * asynchronous; with no transaction active it runs inline, as before (see
     * {@link TransactionCallbacks}).
     */
    private void publishEvent(EmpiEventPayload payload) {
        if (payload == null) {
            return;
        }
        if (!kafkaProperties.isEnabled()) {
            log.debug("Kafka disabled, skipping EMPI event: {}", payload);
            return;
        }
        KafkaTemplate<String, EmpiEventPayload> template = empiKafkaTemplateProvider.getIfAvailable();
        if (template == null) {
            log.debug("No Kafka template available for EMPI events, skipping publish");
            return;
        }
        String topic = kafkaProperties.getEmpiIdentityTopic();
        TransactionCallbacks.afterCommit(() -> {
            try {
                template.send(topic, payload.getEmpiNumber(), payload);
            } catch (RuntimeException ex) {
                log.warn("Failed to publish EMPI event {}", payload, ex);
            }
        });
    }

    private EmpiEventPayload buildIdentityEventPayload(EmpiMasterIdentity identity, String eventType) {
        if (identity == null) {
            return null;
        }
        HospitalContext context = HospitalContextHolder.getContextOrEmpty();
        return EmpiEventPayload.builder()
            .eventType(eventType)
            .empiNumber(identity.getEmpiNumber())
            .masterIdentityId(identity.getId())
            .patientId(identity.getPatientId())
            .occurredAt(OffsetDateTime.now())
            .organizationId(Optional.ofNullable(identity.getOrganizationId()).orElse(context.getActiveOrganizationId()))
            .hospitalId(Optional.ofNullable(identity.getHospitalId()).orElse(context.getActiveHospitalId()))
            .departmentId(Optional.ofNullable(identity.getDepartmentId()).orElseGet(() -> context.getPermittedDepartmentIds().stream().findFirst().orElse(null)))
            .build();
    }

    private EmpiEventPayload buildMergeEventPayload(EmpiMasterIdentity primary,
                                                    EmpiMasterIdentity secondary,
                                                    EmpiMergeEvent mergeEvent) {
        return EmpiEventPayload.builder()
            .eventType(EVENT_IDENTITY_MERGED)
            .empiNumber(primary.getEmpiNumber())
            .primaryEmpiNumber(primary.getEmpiNumber())
            .secondaryEmpiNumber(secondary.getEmpiNumber())
            .masterIdentityId(primary.getId())
            .patientId(primary.getPatientId())
            .occurredAt(mergeEvent.getMergedAt())
            .organizationId(mergeEvent.getOrganizationId())
            .hospitalId(mergeEvent.getHospitalId())
            .departmentId(mergeEvent.getDepartmentId())
            .build();
    }

    private String generateEmpiNumber() {
        for (int attempt = 0; attempt < 25; attempt++) {
            String candidate = String.format("EMP-%06d", RANDOM.nextInt(1_000_000));
            if (!masterIdentityRepository.existsByEmpiNumberIgnoreCase(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Unable to issue unique EMPI number after multiple attempts");
    }

    private String normalizeEmpiNumber(String empiNumber) {
        if (!StringUtils.hasText(empiNumber)) {
            throw new BusinessException(MessageUtil.resolve(MSG_LOOKUP_INVALID_EMPI));
        }
        return empiNumber.trim();
    }
}
