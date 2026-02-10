package com.example.hms.service.empi;

import com.example.hms.enums.empi.EmpiAliasType;
import com.example.hms.enums.empi.EmpiIdentityStatus;
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
public class EmpiServiceImpl implements EmpiService {

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
    private static final String MSG_LINK_MISSING_PATIENT = "empi.link.missingPatient";
    private static final String MSG_LINK_ALIAS_INCOMPLETE = "empi.link.aliasIncomplete";
    private static final String MSG_ALIAS_INVALID = "empi.alias.invalid";
    private static final String MSG_ALIAS_ORPHANED = "empi.alias.orphaned";
    private static final String MSG_LOOKUP_INVALID_EMPI = "empi.lookup.invalidEmpi";

    private final EmpiMasterIdentityRepository masterIdentityRepository;
    private final EmpiIdentityAliasRepository aliasRepository;
    private final EmpiMergeEventRepository mergeEventRepository;
    private final EmpiMapper empiMapper;
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
        return masterIdentityRepository.findByPatientId(patientId)
            .map(empiMapper::toIdentityDto);
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
        EmpiMasterIdentity primary = masterIdentityRepository.findById(primaryIdentityId)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_IDENTITY_NOT_FOUND, primaryIdentityId));

        EmpiMasterIdentity secondary = masterIdentityRepository.findById(request.getSecondaryIdentityId())
            .orElseThrow(() -> new ResourceNotFoundException(MSG_IDENTITY_NOT_FOUND, request.getSecondaryIdentityId()));

        if (primary.getId().equals(secondary.getId())) {
            throw new BusinessException(MessageUtil.resolve(MSG_MERGE_SAME_IDENTITY));
        }
        if (secondary.getStatus() == EmpiIdentityStatus.MERGED) {
            throw new BusinessException(MessageUtil.resolve(MSG_MERGE_ALREADY_MERGED, secondary.getEmpiNumber()));
        }

        HospitalContext context = HospitalContextHolder.getContextOrEmpty();
        EmpiMergeEvent mergeEvent = buildMergeEvent(request, primary, secondary, context);

        secondary.setStatus(EmpiIdentityStatus.MERGED);
        secondary.setResolutionState(EmpiResolutionState.CONFIRMED);
        secondary.setActive(false);
        secondary.setUpdatedBy(context.getPrincipalUserId());
        primary.setUpdatedBy(context.getPrincipalUserId());

        mergeEventRepository.save(mergeEvent);
        masterIdentityRepository.save(secondary);
        masterIdentityRepository.save(primary);

        publishEvent(buildMergeEventPayload(primary, secondary, mergeEvent));
        return empiMapper.toMergeEventDto(mergeEvent);
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
        try {
            template.send(kafkaProperties.getEmpiIdentityTopic(), payload.getEmpiNumber(), payload);
        } catch (Exception ex) {
            log.warn("Failed to publish EMPI event {}", payload, ex);
        }
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
