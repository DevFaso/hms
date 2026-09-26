package com.example.hms.service;

import com.example.hms.config.KafkaProperties;
import com.example.hms.enums.empi.EmpiAliasType;
import com.example.hms.enums.empi.EmpiIdentityStatus;
import com.example.hms.enums.empi.EmpiMergeType;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.GlobalExceptionHandler;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.EmpiMapper;
import com.example.hms.model.empi.EmpiIdentityAlias;
import com.example.hms.model.empi.EmpiMasterIdentity;
import com.example.hms.model.empi.EmpiMergeEvent;
import com.example.hms.payload.dto.empi.EmpiAliasRequestDTO;
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
import com.example.hms.service.empi.EmpiServiceImpl;
import com.example.hms.utility.MessageUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.MessageSource;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.WebRequest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmpiServiceImplTest {

    private static final String EMPI_TOPIC = "empi-identity-topic";
    private static final String CHAT_TOPIC = "chat-topic";
    private static final String PATIENT_TOPIC = "patient-topic";
    private static final String PLATFORM_TOPIC = "platform-topic";
    private static final String NATIONAL_ALIAS = "NATIONAL-1";
    private static final String ORPHAN_ALIAS = "MRN-ORPHAN";
    private static final String DUPLICATE_ID_ALIAS = "ID-123";

    @Mock
    private EmpiMasterIdentityRepository masterIdentityRepository;

    @Mock
    private EmpiIdentityAliasRepository aliasRepository;

    @Mock
    private EmpiMergeEventRepository mergeEventRepository;

    @Mock
    private ObjectProvider<KafkaTemplate<String, EmpiEventPayload>> kafkaTemplateProvider;

    @Mock
    private KafkaTemplate<String, EmpiEventPayload> kafkaTemplate;

    @Mock
    private MessageSource messageSource;

    @Mock
    private com.example.hms.repository.PatientRepository patientRepository;

    @Mock
    private com.example.hms.service.AuditEventLogService auditEventLogService;

    @Mock
    private com.example.hms.utility.RoleValidator roleValidator;

    @Mock
    private com.example.hms.repository.PatientHospitalRegistrationRepository registrationRepository;

    private EmpiServiceImpl empiService;

    private KafkaProperties kafkaProperties;

    @BeforeEach
    void setUp() {
        kafkaProperties = new KafkaProperties();
        kafkaProperties.setEnabled(false);
        kafkaProperties.setEmpiIdentityTopic(EMPI_TOPIC);
        kafkaProperties.setChatTopic(CHAT_TOPIC);
        kafkaProperties.setPatientMovementTopic(PATIENT_TOPIC);
        kafkaProperties.setPlatformRegistryTopic(PLATFORM_TOPIC);
        lenient().when(kafkaTemplateProvider.getIfAvailable()).thenReturn(null);
        when(messageSource.getMessage(anyString(), any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        MessageUtil.setMessageSource(messageSource);

        empiService = new EmpiServiceImpl(
            masterIdentityRepository,
            aliasRepository,
            mergeEventRepository,
            patientRepository,
            auditEventLogService,
            new EmpiMapper(),
            roleValidator,
            registrationRepository,
            kafkaProperties,
            kafkaTemplateProvider
        );

        HospitalContextHolder.setContext(HospitalContext.builder()
            .principalUserId(UUID.randomUUID())
            .activeHospitalId(UUID.randomUUID())
            .activeOrganizationId(UUID.randomUUID())
            .build());

        // The mocked requireActiveHospitalId() answers null unless a test pins
        // a hospital, so the default caller is a VERIFIED super-admin in global
        // view — the only caller a null scope may stand for. The unverified
        // (authorities-only) caller stubs this false explicitly.
        lenient().when(roleValidator.isSuperAdminFromJwtClaim()).thenReturn(true);
    }

    @AfterEach
    void clearContext() {
        HospitalContextHolder.clear();
    }

    @Test
    void linkIdentity_createsNewIdentityWithAlias() {
        UUID patientId = UUID.randomUUID();
        EmpiIdentityLinkRequestDTO request = new EmpiIdentityLinkRequestDTO();
        request.setPatientId(patientId);
        request.setAliasType(EmpiAliasType.MRN);
        request.setAliasValue("mrn-123");
        request.setAliasSourceSystem("ehr");

        when(masterIdentityRepository.findByPatientId(patientId)).thenReturn(Optional.empty());
        when(aliasRepository.findByAliasTypeAndAliasValueIgnoreCase(any(), any())).thenReturn(Optional.empty());
        when(aliasRepository.existsByAliasTypeAndAliasValueIgnoreCase(any(), any())).thenReturn(false);
        when(masterIdentityRepository.existsByEmpiNumberIgnoreCase(anyString())).thenReturn(false);
        when(masterIdentityRepository.save(Mockito.any(EmpiMasterIdentity.class)))
            .thenAnswer(invocation -> {
                EmpiMasterIdentity identity = invocation.getArgument(0);
                if (identity.getId() == null) {
                    identity.setId(UUID.randomUUID());
                }
                identity.getAliases().forEach(alias -> {
                    if (alias.getId() == null) {
                        alias.setId(UUID.randomUUID());
                    }
                });
                return identity;
            });

        EmpiIdentityResponseDTO response = empiService.linkIdentity(request);

        assertThat(response.getPatientId()).isEqualTo(patientId);
        assertThat(response.getAliases()).hasSize(1);
        assertThat(response.getAliases().get(0).getAliasType()).isEqualTo(EmpiAliasType.MRN);
    }


    @Test
    void linkIdentity_withExistingAliasOwnedByAnotherIdentityThrows() {
        UUID patientId = UUID.randomUUID();
        EmpiIdentityLinkRequestDTO request = new EmpiIdentityLinkRequestDTO();
        request.setPatientId(patientId);
        request.setAliasType(EmpiAliasType.NATIONAL_ID);
        request.setAliasValue(NATIONAL_ALIAS);

        EmpiMasterIdentity differentIdentity = EmpiMasterIdentity.builder()
            .empiNumber("EMP-999999")
            .status(EmpiIdentityStatus.ACTIVE)
            .build();
        differentIdentity.setId(UUID.randomUUID());
        differentIdentity.setPatientId(UUID.randomUUID());

        EmpiIdentityAlias alias = EmpiIdentityAlias.builder()
            .aliasType(EmpiAliasType.NATIONAL_ID)
            .aliasValue(NATIONAL_ALIAS)
            .masterIdentity(differentIdentity)
            .build();

        when(masterIdentityRepository.findByPatientId(patientId)).thenReturn(Optional.empty());
        when(aliasRepository.findByAliasTypeAndAliasValueIgnoreCase(EmpiAliasType.NATIONAL_ID, NATIONAL_ALIAS))
            .thenReturn(Optional.of(alias));

        assertThatThrownBy(() -> empiService.linkIdentity(request))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void linkIdentity_withOrphanedAliasThrowsBusinessException() {
        UUID patientId = UUID.randomUUID();
        EmpiIdentityLinkRequestDTO request = new EmpiIdentityLinkRequestDTO();
        request.setPatientId(patientId);
        request.setAliasType(EmpiAliasType.MRN);
        request.setAliasValue(ORPHAN_ALIAS);

        EmpiIdentityAlias orphanAlias = EmpiIdentityAlias.builder()
            .aliasType(EmpiAliasType.MRN)
            .aliasValue(ORPHAN_ALIAS)
            .masterIdentity(null)
            .build();

        when(masterIdentityRepository.findByPatientId(patientId)).thenReturn(Optional.empty());
        when(aliasRepository.findByAliasTypeAndAliasValueIgnoreCase(EmpiAliasType.MRN, ORPHAN_ALIAS))
            .thenReturn(Optional.of(orphanAlias));

        assertThatThrownBy(() -> empiService.linkIdentity(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("empi.alias.orphaned");
    }

    @Test
    void linkIdentity_retriesEmpiNumberGenerationUntilUnique() {
        UUID patientId = UUID.randomUUID();
        EmpiIdentityLinkRequestDTO request = new EmpiIdentityLinkRequestDTO();
        request.setPatientId(patientId);

        when(masterIdentityRepository.findByPatientId(patientId)).thenReturn(Optional.empty());
        when(aliasRepository.findByAliasTypeAndAliasValueIgnoreCase(any(), any())).thenReturn(Optional.empty());
        when(aliasRepository.existsByAliasTypeAndAliasValueIgnoreCase(any(), any())).thenReturn(false);
        when(masterIdentityRepository.existsByEmpiNumberIgnoreCase(anyString())).thenReturn(true, false);
        when(masterIdentityRepository.save(Mockito.any(EmpiMasterIdentity.class)))
            .thenAnswer(invocation -> {
                EmpiMasterIdentity identity = invocation.getArgument(0);
                identity.setId(UUID.randomUUID());
                return identity;
            });

        EmpiIdentityResponseDTO response = empiService.linkIdentity(request);

        assertThat(response.getEmpiNumber()).startsWith("EMP-");
        Mockito.verify(masterIdentityRepository, Mockito.atLeast(2)).existsByEmpiNumberIgnoreCase(anyString());
    }

    @Test
    void linkIdentity_whenUnableToGenerateUniqueEmpiNumberThrows() {
        UUID patientId = UUID.randomUUID();
        EmpiIdentityLinkRequestDTO request = new EmpiIdentityLinkRequestDTO();
        request.setPatientId(patientId);

        when(masterIdentityRepository.findByPatientId(patientId)).thenReturn(Optional.empty());
        when(aliasRepository.findByAliasTypeAndAliasValueIgnoreCase(any(), any())).thenReturn(Optional.empty());
        when(aliasRepository.existsByAliasTypeAndAliasValueIgnoreCase(any(), any())).thenReturn(false);
        when(masterIdentityRepository.existsByEmpiNumberIgnoreCase(anyString())).thenReturn(true);

        assertThatThrownBy(() -> empiService.linkIdentity(request))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Unable to issue unique EMPI number");
    }

    @Test
    void findIdentityByAlias_returnsDtoWhenAliasPresent() {
        EmpiMasterIdentity identity = EmpiMasterIdentity.builder()
            .empiNumber("EMP-777777")
            .patientId(UUID.randomUUID())
            .build();
        identity.setId(UUID.randomUUID());

        EmpiIdentityAlias alias = EmpiIdentityAlias.builder()
            .aliasType(EmpiAliasType.MRN)
            .aliasValue("mrn-777")
            .masterIdentity(identity)
            .build();

        when(aliasRepository.findByAliasTypeAndAliasValueIgnoreCase(EmpiAliasType.MRN, "mrn-777"))
            .thenReturn(Optional.of(alias));

        Optional<EmpiIdentityResponseDTO> result = empiService.findIdentityByAlias(EmpiAliasType.MRN, "  mrn-777  ");

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(identity.getId());
    }

    @Test
    void mergeIdentities_marksSecondaryAsMergedAndRecordsEvent() {
        UUID primaryId = UUID.randomUUID();
        UUID secondaryId = UUID.randomUUID();

        EmpiMasterIdentity primary = EmpiMasterIdentity.builder()
            .empiNumber("EMP-000001")
            .status(EmpiIdentityStatus.ACTIVE)
            .build();
        primary.setId(primaryId);

        EmpiMasterIdentity secondary = EmpiMasterIdentity.builder()
            .empiNumber("EMP-000002")
            .status(EmpiIdentityStatus.ACTIVE)
            .build();
        secondary.setId(secondaryId);

        when(masterIdentityRepository.findById(primaryId)).thenReturn(Optional.of(primary));
        when(masterIdentityRepository.findById(secondaryId)).thenReturn(Optional.of(secondary));
        when(mergeEventRepository.save(any(EmpiMergeEvent.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(masterIdentityRepository.save(any(EmpiMasterIdentity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        EmpiMergeRequestDTO request = new EmpiMergeRequestDTO();
        request.setSecondaryIdentityId(secondaryId);
        request.setMergeType(EmpiMergeType.MANUAL);
        request.setResolution("CONFIRMED");

        EmpiMergeEventResponseDTO response = empiService.mergeIdentities(primaryId, request);

        assertThat(response.getPrimaryIdentityId()).isEqualTo(primaryId);
        assertThat(response.getSecondaryIdentityId()).isEqualTo(secondaryId);
        assertThat(secondary.getStatus()).isEqualTo(EmpiIdentityStatus.MERGED);
        assertThat(secondary.isActive()).isFalse();

        ArgumentCaptor<EmpiMergeEvent> mergeCaptor = ArgumentCaptor.forClass(EmpiMergeEvent.class);
        Mockito.verify(mergeEventRepository).save(mergeCaptor.capture());
        assertThat(mergeCaptor.getValue().getMergeType()).isEqualTo(EmpiMergeType.MANUAL);
        // Skill merge-step 5 (P1 #8): PATIENT_MERGE audit trail
        Mockito.verify(auditEventLogService).logEvent(any());
    }

    /* ── P1 #8: merge hardening ─────────────────────────────────────────── */

    private EmpiMasterIdentity activeIdentity(String empiNumber, UUID hospitalId) {
        EmpiMasterIdentity identity = EmpiMasterIdentity.builder()
            .empiNumber(empiNumber)
            .status(EmpiIdentityStatus.ACTIVE)
            .hospitalId(hospitalId)
            .build();
        identity.setId(UUID.randomUUID());
        return identity;
    }

    @Test
    void mergeIdentities_reassignsAliasesAndDeactivatesDuplicates() {
        EmpiMasterIdentity primary = activeIdentity("EMP-A", null);
        EmpiMasterIdentity secondary = activeIdentity("EMP-B", null);
        when(masterIdentityRepository.findById(primary.getId())).thenReturn(Optional.of(primary));
        when(masterIdentityRepository.findById(secondary.getId())).thenReturn(Optional.of(secondary));
        when(mergeEventRepository.save(any(EmpiMergeEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(masterIdentityRepository.save(any(EmpiMasterIdentity.class))).thenAnswer(inv -> inv.getArgument(0));

        EmpiIdentityAlias primaryMrn = EmpiIdentityAlias.builder()
            .aliasType(EmpiAliasType.MRN).aliasValue("MRN-1").masterIdentity(primary).active(true).build();
        EmpiIdentityAlias duplicateMrn = EmpiIdentityAlias.builder()
            .aliasType(EmpiAliasType.MRN).aliasValue("mrn-1").masterIdentity(secondary).active(true).build();
        EmpiIdentityAlias movableNationalId = EmpiIdentityAlias.builder()
            .aliasType(EmpiAliasType.NATIONAL_ID).aliasValue("BF-42").masterIdentity(secondary).active(true).build();

        when(aliasRepository.findByMasterIdentity_Id(primary.getId())).thenReturn(List.of(primaryMrn));
        when(aliasRepository.findByMasterIdentity_Id(secondary.getId()))
            .thenReturn(List.of(duplicateMrn, movableNationalId));

        EmpiMergeRequestDTO request = new EmpiMergeRequestDTO();
        request.setSecondaryIdentityId(secondary.getId());
        request.setMergeType(EmpiMergeType.MANUAL);

        empiService.mergeIdentities(primary.getId(), request);

        // The case-insensitive duplicate is deactivated; the unique alias moves.
        assertThat(duplicateMrn.isActive()).isFalse();
        assertThat(movableNationalId.getMasterIdentity()).isSameAs(primary);
        Mockito.verify(aliasRepository).save(duplicateMrn);
        Mockito.verify(aliasRepository).save(movableNationalId);
    }

    @Test
    void mergeIdentities_rejectsCrossTenantMerge() {
        EmpiMasterIdentity primary = activeIdentity("EMP-A", UUID.randomUUID());
        EmpiMasterIdentity secondary = activeIdentity("EMP-B", UUID.randomUUID());
        when(masterIdentityRepository.findById(primary.getId())).thenReturn(Optional.of(primary));
        when(masterIdentityRepository.findById(secondary.getId())).thenReturn(Optional.of(secondary));

        EmpiMergeRequestDTO request = new EmpiMergeRequestDTO();
        request.setSecondaryIdentityId(secondary.getId());
        request.setMergeType(EmpiMergeType.MANUAL);

        UUID primaryId = primary.getId();
        assertThatThrownBy(() -> empiService.mergeIdentities(primaryId, request))
            .isInstanceOf(BusinessException.class);
        Mockito.verify(mergeEventRepository, Mockito.never()).save(any());
    }

    @Test
    void mergePatients_provisionsMissingIdentitiesThenMerges() {
        UUID primaryPatientId = UUID.randomUUID();
        UUID secondaryPatientId = UUID.randomUUID();

        // Primary already has an identity; secondary needs provisioning.
        EmpiMasterIdentity primary = activeIdentity("EMP-A", null);
        primary.setPatientId(primaryPatientId);
        EmpiMasterIdentity provisioned = activeIdentity("EMP-B", null);
        provisioned.setPatientId(secondaryPatientId);

        when(masterIdentityRepository.findByPatientId(primaryPatientId)).thenReturn(Optional.of(primary));
        // First lookup: absent (triggers provisioning); after linkIdentity: present.
        when(masterIdentityRepository.findByPatientId(secondaryPatientId))
            .thenReturn(Optional.empty(), Optional.of(provisioned));

        com.example.hms.model.Patient duplicatePatient = new com.example.hms.model.Patient();
        duplicatePatient.setId(secondaryPatientId);
        when(patientRepository.findByIdUnscoped(secondaryPatientId)).thenReturn(Optional.of(duplicatePatient));

        when(masterIdentityRepository.findById(primary.getId())).thenReturn(Optional.of(primary));
        when(masterIdentityRepository.findById(provisioned.getId())).thenReturn(Optional.of(provisioned));
        when(masterIdentityRepository.save(any(EmpiMasterIdentity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(mergeEventRepository.save(any(EmpiMergeEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(masterIdentityRepository.existsByEmpiNumberIgnoreCase(any())).thenReturn(false);

        EmpiMergeEventResponseDTO response = empiService.mergePatients(
            primaryPatientId, secondaryPatientId, EmpiMergeType.MANUAL, "duplicate registration");

        assertThat(response).isNotNull();
        assertThat(provisioned.getStatus()).isEqualTo(EmpiIdentityStatus.MERGED);
        assertThat(provisioned.isActive()).isFalse();
    }

    @Test
    void mergePatients_rejectsSelfMerge() {
        UUID patientId = UUID.randomUUID();
        assertThatThrownBy(() ->
            empiService.mergePatients(patientId, patientId, EmpiMergeType.MANUAL, null))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void linkIdentity_whenIdentityAlreadySatisfiesRequestSkipsSave() {
        UUID patientId = UUID.randomUUID();
        EmpiMasterIdentity identity = EmpiMasterIdentity.builder()
            .empiNumber("EMP-111111")
            .patientId(patientId)
            .build();
        identity.setId(UUID.randomUUID());

        EmpiIdentityAlias alias = EmpiIdentityAlias.builder()
            .aliasType(EmpiAliasType.MRN)
            .aliasValue("MRN-001")
            .build();
        identity.addAlias(alias);

        EmpiIdentityLinkRequestDTO request = new EmpiIdentityLinkRequestDTO();
        request.setPatientId(patientId);
        request.setAliasType(EmpiAliasType.MRN);
        request.setAliasValue("MRN-001");

        when(masterIdentityRepository.findByPatientId(patientId)).thenReturn(Optional.of(identity));

        EmpiIdentityResponseDTO response = empiService.linkIdentity(request);

        assertThat(response.getId()).isEqualTo(identity.getId());
        Mockito.verify(masterIdentityRepository, Mockito.never()).save(any(EmpiMasterIdentity.class));
    }

    @Test
    void findIdentityByAlias_withInvalidArgumentsReturnsEmpty() {
        assertThat(empiService.findIdentityByAlias(null, "value")).isEmpty();
        assertThat(empiService.findIdentityByAlias(EmpiAliasType.MRN, "  ")).isEmpty();
        Mockito.verifyNoInteractions(aliasRepository);
    }

    @Test
    void addAlias_whenAliasAlreadyExistsThrows() {
        UUID identityId = UUID.randomUUID();
        EmpiMasterIdentity identity = EmpiMasterIdentity.builder()
            .empiNumber("EMP-222222")
            .patientId(UUID.randomUUID())
            .build();
        identity.setId(identityId);

        EmpiIdentityAlias existingAlias = EmpiIdentityAlias.builder()
            .aliasType(EmpiAliasType.NATIONAL_ID)
            .aliasValue(DUPLICATE_ID_ALIAS)
            .build();
        identity.addAlias(existingAlias);

        EmpiAliasRequestDTO request = new EmpiAliasRequestDTO();
        request.setAliasType(EmpiAliasType.NATIONAL_ID);
        request.setAliasValue(DUPLICATE_ID_ALIAS);

        when(masterIdentityRepository.findById(identityId)).thenReturn(Optional.of(identity));
        when(aliasRepository.existsByAliasTypeAndAliasValueIgnoreCase(EmpiAliasType.NATIONAL_ID, DUPLICATE_ID_ALIAS))
            .thenReturn(true);

        assertThatThrownBy(() -> empiService.addAlias(identityId, request))
            .isInstanceOf(BusinessException.class);
        Mockito.verify(masterIdentityRepository, Mockito.never()).save(any(EmpiMasterIdentity.class));
    }

    @Test
    void removeAlias_removesAliasAndPersistsIdentity() {
        UUID identityId = UUID.randomUUID();
        UUID aliasId = UUID.randomUUID();

        EmpiIdentityAlias alias = EmpiIdentityAlias.builder()
            .aliasType(EmpiAliasType.MRN)
            .aliasValue("MRN-REMOVE")
            .build();
        alias.setId(aliasId);

        EmpiMasterIdentity identity = EmpiMasterIdentity.builder()
            .empiNumber("EMP-333333")
            .patientId(UUID.randomUUID())
            .build();
        identity.setId(identityId);
        identity.addAlias(alias);

        when(masterIdentityRepository.findById(identityId)).thenReturn(Optional.of(identity));

        empiService.removeAlias(identityId, aliasId);

        assertThat(identity.getAliases()).isEmpty();
        Mockito.verify(masterIdentityRepository).save(identity);
    }

    @Test
    void removeAlias_whenAliasMissingThrowsNotFound() {
        UUID identityId = UUID.randomUUID();
        EmpiMasterIdentity identity = EmpiMasterIdentity.builder()
            .empiNumber("EMP-444444")
            .patientId(UUID.randomUUID())
            .build();
        identity.setId(identityId);

        when(masterIdentityRepository.findById(identityId)).thenReturn(Optional.of(identity));

    UUID missingAliasId = UUID.randomUUID();

    assertThatThrownBy(() -> empiService.removeAlias(identityId, missingAliasId))
        .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getIdentityByEmpiNumber_withBlankInputThrowsBusinessException() {
        assertThatThrownBy(() -> empiService.getIdentityByEmpiNumber("  "))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("empi.lookup.invalidEmpi");
    }

    @Test
    void addAlias_publishesKafkaEventWhenEnabled() {
        kafkaProperties.setEnabled(true);
        when(kafkaTemplateProvider.getIfAvailable()).thenReturn(kafkaTemplate);

        when(kafkaTemplate.send(anyString(), anyString(), any(EmpiEventPayload.class))).thenReturn(null);

        UUID identityId = UUID.randomUUID();
        EmpiMasterIdentity identity = EmpiMasterIdentity.builder()
            .empiNumber("EMP-555555")
            .patientId(UUID.randomUUID())
            .build();
        identity.setId(identityId);

        when(masterIdentityRepository.findById(identityId)).thenReturn(Optional.of(identity));
        when(aliasRepository.existsByAliasTypeAndAliasValueIgnoreCase(any(), any())).thenReturn(false);
        when(masterIdentityRepository.save(any(EmpiMasterIdentity.class)))
            .thenAnswer(invocation -> {
                EmpiMasterIdentity saved = invocation.getArgument(0);
                saved.getAliases().forEach(alias -> {
                    if (alias.getId() == null) {
                        alias.setId(UUID.randomUUID());
                    }
                });
                return saved;
            });

        EmpiAliasRequestDTO request = new EmpiAliasRequestDTO();
        request.setAliasType(EmpiAliasType.MRN);
        request.setAliasValue("MRN-KAFKA");

        empiService.addAlias(identityId, request);

    Mockito.verify(kafkaTemplate).send(eq(EMPI_TOPIC), anyString(), any(EmpiEventPayload.class));
    }

    @Test
    void addAlias_logsWarningWhenKafkaSendFails() {
        kafkaProperties.setEnabled(true);
        when(kafkaTemplateProvider.getIfAvailable()).thenReturn(kafkaTemplate);
        when(kafkaTemplate.send(anyString(), anyString(), any(EmpiEventPayload.class)))
            .thenThrow(new IllegalStateException("send failure"));

        UUID identityId = UUID.randomUUID();
        EmpiMasterIdentity identity = EmpiMasterIdentity.builder()
            .empiNumber("EMP-666666")
            .patientId(UUID.randomUUID())
            .build();
        identity.setId(identityId);

        when(masterIdentityRepository.findById(identityId)).thenReturn(Optional.of(identity));
        when(aliasRepository.existsByAliasTypeAndAliasValueIgnoreCase(any(), any())).thenReturn(false);
        when(masterIdentityRepository.save(any(EmpiMasterIdentity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        EmpiAliasRequestDTO request = new EmpiAliasRequestDTO();
    request.setAliasType(EmpiAliasType.PASSPORT);
        request.setAliasValue("999-00-0000");

        empiService.addAlias(identityId, request);

    Mockito.verify(kafkaTemplate).send(eq(EMPI_TOPIC), anyString(), any(EmpiEventPayload.class));
    }

    @Test
    void addAlias_whenKafkaTemplateUnavailableSkipsPublish() {
        kafkaProperties.setEnabled(true);
        when(kafkaTemplateProvider.getIfAvailable()).thenReturn(null);

        UUID identityId = UUID.randomUUID();
        EmpiMasterIdentity identity = EmpiMasterIdentity.builder()
            .empiNumber("EMP-777888")
            .patientId(UUID.randomUUID())
            .build();
        identity.setId(identityId);

        when(masterIdentityRepository.findById(identityId)).thenReturn(Optional.of(identity));
        when(aliasRepository.existsByAliasTypeAndAliasValueIgnoreCase(any(), any())).thenReturn(false);
        when(masterIdentityRepository.save(any(EmpiMasterIdentity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        EmpiAliasRequestDTO request = new EmpiAliasRequestDTO();
        request.setAliasType(EmpiAliasType.MRN);
        request.setAliasValue("MRN-NOTEMPLATE");

    empiService.addAlias(identityId, request);

    Mockito.verify(masterIdentityRepository).save(any(EmpiMasterIdentity.class));
    Mockito.verify(kafkaTemplateProvider).getIfAvailable();
    }

    // ── Caller-vs-tenant isolation ─────────────────────────────────────────
    // The merge endpoints are HOSPITAL_ADMIN-or-better, but HOSPITAL_ADMIN is a
    // PER-HOSPITAL role — "is an admin" was never the same question as "is an
    // admin HERE". The pre-existing cross-tenant check only compared the two
    // identities to EACH OTHER, which two identities at the same foreign
    // hospital satisfy perfectly.

    @Test
    void mergeIdentities_rejectsWhenBothIdentitiesBelongToAnotherHospital() {
        UUID callerHospital = UUID.randomUUID();
        UUID foreignHospital = UUID.randomUUID();
        when(roleValidator.requireActiveHospitalId()).thenReturn(callerHospital);

        // Both at the SAME foreign hospital: the identity-to-identity guard is
        // satisfied, so only a caller check can stop this.
        EmpiMasterIdentity primary = activeIdentity("EMP-FA", foreignHospital);
        EmpiMasterIdentity secondary = activeIdentity("EMP-FB", foreignHospital);
        when(masterIdentityRepository.findById(primary.getId())).thenReturn(Optional.of(primary));
        when(masterIdentityRepository.findById(secondary.getId())).thenReturn(Optional.of(secondary));

        EmpiMergeRequestDTO request = new EmpiMergeRequestDTO();
        request.setSecondaryIdentityId(secondary.getId());
        request.setMergeType(EmpiMergeType.MANUAL);

        UUID primaryId = primary.getId();
        assertThatThrownBy(() -> empiService.mergeIdentities(primaryId, request))
            .isInstanceOf(ResourceNotFoundException.class);
        Mockito.verify(mergeEventRepository, Mockito.never()).save(any());
    }

    @Test
    void mergeIdentities_rejectsUnstampedIdentityForAScopedCaller() {
        UUID callerHospital = UUID.randomUUID();
        when(roleValidator.requireActiveHospitalId()).thenReturn(callerHospital);

        // A legacy row with no hospital stamp belongs to nobody in particular;
        // handing it to whichever tenant asks first is disclosure by another
        // route. Super-admin still reconciles these.
        EmpiMasterIdentity primary = activeIdentity("EMP-LEGACY", null);
        EmpiMasterIdentity secondary = activeIdentity("EMP-MINE", callerHospital);
        when(masterIdentityRepository.findById(primary.getId())).thenReturn(Optional.of(primary));
        when(masterIdentityRepository.findById(secondary.getId())).thenReturn(Optional.of(secondary));

        EmpiMergeRequestDTO request = new EmpiMergeRequestDTO();
        request.setSecondaryIdentityId(secondary.getId());
        request.setMergeType(EmpiMergeType.MANUAL);

        UUID primaryId = primary.getId();
        assertThatThrownBy(() -> empiService.mergeIdentities(primaryId, request))
            .isInstanceOf(ResourceNotFoundException.class);
        Mockito.verify(mergeEventRepository, Mockito.never()).save(any());
    }

    @Test
    void mergeIdentities_allowsSuperAdminAcrossUnstampedRows() {
        // Null active hospital + the verified claim = super-admin, unscoped.
        when(roleValidator.requireActiveHospitalId()).thenReturn(null);
        when(roleValidator.isSuperAdminFromJwtClaim()).thenReturn(true);

        EmpiMasterIdentity primary = activeIdentity("EMP-SA", null);
        EmpiMasterIdentity secondary = activeIdentity("EMP-SB", null);
        when(masterIdentityRepository.findById(primary.getId())).thenReturn(Optional.of(primary));
        when(masterIdentityRepository.findById(secondary.getId())).thenReturn(Optional.of(secondary));
        when(mergeEventRepository.save(any(EmpiMergeEvent.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        EmpiMergeRequestDTO request = new EmpiMergeRequestDTO();
        request.setSecondaryIdentityId(secondary.getId());
        request.setMergeType(EmpiMergeType.MANUAL);

        empiService.mergeIdentities(primary.getId(), request);

        Mockito.verify(mergeEventRepository).save(any(EmpiMergeEvent.class));
    }

    @Test
    void findIdentityByPatientId_hidesAnotherHospitalsIdentity() {
        UUID patientId = UUID.randomUUID();
        when(roleValidator.requireActiveHospitalId()).thenReturn(UUID.randomUUID());

        EmpiMasterIdentity foreign = activeIdentity("EMP-FOREIGN", UUID.randomUUID());
        foreign.setPatientId(patientId);
        when(masterIdentityRepository.findByPatientId(patientId)).thenReturn(Optional.of(foreign));

        // Reads as absent, not as data: the row carries the EMPI number and the
        // patient's cross-facility affiliation.
        assertThat(empiService.findIdentityByPatientId(patientId)).isEmpty();
    }

    @Test
    void findIdentityByPatientId_returnsOwnHospitalIdentity() {
        UUID patientId = UUID.randomUUID();
        UUID callerHospital = UUID.randomUUID();
        when(roleValidator.requireActiveHospitalId()).thenReturn(callerHospital);

        EmpiMasterIdentity mine = activeIdentity("EMP-MINE", callerHospital);
        mine.setPatientId(patientId);
        when(masterIdentityRepository.findByPatientId(patientId)).thenReturn(Optional.of(mine));

        assertThat(empiService.findIdentityByPatientId(patientId)).isPresent();
    }

    @Test
    void mergePatients_rejectsPatientNotRegisteredAtCallerHospitalBeforeProvisioning() {
        UUID callerHospital = UUID.randomUUID();
        UUID primaryPatientId = UUID.randomUUID();
        UUID secondaryPatientId = UUID.randomUUID();
        when(roleValidator.requireActiveHospitalId()).thenReturn(callerHospital);
        when(registrationRepository.existsByPatientIdAndHospitalId(primaryPatientId, callerHospital))
            .thenReturn(false);

        assertThatThrownBy(() -> empiService.mergePatients(
                primaryPatientId, secondaryPatientId, EmpiMergeType.MANUAL, null))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);

        // The guard must run BEFORE ensureIdentityForPatient, which provisions a
        // master identity and emits IDENTITY_LINKED — a write against another
        // tenant's patient that no later check could undo.
        Mockito.verify(masterIdentityRepository, Mockito.never()).save(any());
        Mockito.verify(mergeEventRepository, Mockito.never()).save(any());
    }

    /* No oracle on the merge: another tenant's identity answers like a
       missing one, and owning one side answers like owning neither. */

    @Test
    void mergeIdentities_foreignCandidateIsByteIdenticalToAMissingCandidate() {
        renderMessagesWithArguments();
        UUID callerHospital = UUID.randomUUID();
        when(roleValidator.requireActiveHospitalId()).thenReturn(callerHospital);
        EmpiMasterIdentity mine = activeIdentity("EMP-MINE", callerHospital);
        UUID candidateId = UUID.randomUUID();
        when(masterIdentityRepository.findById(mine.getId())).thenReturn(Optional.of(mine));

        // World 1: the candidate is a real identity at another hospital.
        EmpiMasterIdentity foreign = activeIdentity("EMP-THEIRS", UUID.randomUUID());
        foreign.setId(candidateId);
        when(masterIdentityRepository.findById(candidateId)).thenReturn(Optional.of(foreign));
        Throwable elsewhere = mergeRefusal(mine.getId(), candidateId);

        // World 2: the candidate exists nowhere.
        when(masterIdentityRepository.findById(candidateId)).thenReturn(Optional.empty());
        Throwable nowhere = mergeRefusal(mine.getId(), candidateId);

        assertIdenticalRefusal(elsewhere, nowhere);
        assertThat(nowhere).isExactlyInstanceOf(ResourceNotFoundException.class);
        Mockito.verify(mergeEventRepository, Mockito.never()).save(any());
        Mockito.verify(masterIdentityRepository, Mockito.never()).save(any());
    }

    @Test
    void mergeIdentities_owningOneSideAnswersExactlyLikeOwningNeither() {
        renderMessagesWithArguments();
        UUID callerHospital = UUID.randomUUID();
        UUID foreignHospital = UUID.randomUUID();
        when(roleValidator.requireActiveHospitalId()).thenReturn(callerHospital);
        EmpiMasterIdentity mine = activeIdentity("EMP-MINE", callerHospital);
        EmpiMasterIdentity theirsA = activeIdentity("EMP-TA", foreignHospital);
        EmpiMasterIdentity theirsB = activeIdentity("EMP-TB", foreignHospital);
        UUID ghostA = UUID.randomUUID();
        UUID ghostB = UUID.randomUUID();
        for (EmpiMasterIdentity identity : List.of(mine, theirsA, theirsB)) {
            when(masterIdentityRepository.findById(identity.getId())).thenReturn(Optional.of(identity));
        }
        when(masterIdentityRepository.findById(ghostA)).thenReturn(Optional.empty());
        when(masterIdentityRepository.findById(ghostB)).thenReturn(Optional.empty());

        Throwable neitherExists = mergeRefusal(ghostA, ghostB);

        assertIdenticalRefusal(mergeRefusal(mine.getId(), theirsA.getId()), neitherExists);
        assertIdenticalRefusal(mergeRefusal(theirsA.getId(), mine.getId()), neitherExists);
        assertIdenticalRefusal(mergeRefusal(theirsA.getId(), theirsB.getId()), neitherExists);
        assertIdenticalRefusal(mergeRefusal(mine.getId(), ghostA), neitherExists);
        assertIdenticalRefusal(mergeRefusal(ghostA, mine.getId()), neitherExists);
        Mockito.verify(mergeEventRepository, Mockito.never()).save(any());
    }

    @Test
    void mergeIdentities_looksUpBothSidesBeforeJudgingEither() {
        // A missing primary must not skip the secondary lookup: if it did, the
        // cost of a refusal would depend on which side failed, and a caller
        // could time the difference.
        UUID callerHospital = UUID.randomUUID();
        when(roleValidator.requireActiveHospitalId()).thenReturn(callerHospital);
        UUID missingPrimary = UUID.randomUUID();
        EmpiMasterIdentity mine = activeIdentity("EMP-MINE", callerHospital);
        when(masterIdentityRepository.findById(missingPrimary)).thenReturn(Optional.empty());
        when(masterIdentityRepository.findById(mine.getId())).thenReturn(Optional.of(mine));

        assertThat(mergeRefusal(missingPrimary, mine.getId())).isInstanceOf(ResourceNotFoundException.class);

        Mockito.verify(masterIdentityRepository).findById(missingPrimary);
        Mockito.verify(masterIdentityRepository).findById(mine.getId());
    }

    @Test
    void mergeIdentities_mergesTwoOfTheCallersOwnIdentities() {
        UUID callerHospital = UUID.randomUUID();
        when(roleValidator.requireActiveHospitalId()).thenReturn(callerHospital);
        EmpiMasterIdentity primary = activeIdentity("EMP-P", callerHospital);
        EmpiMasterIdentity secondary = activeIdentity("EMP-S", callerHospital);
        when(masterIdentityRepository.findById(primary.getId())).thenReturn(Optional.of(primary));
        when(masterIdentityRepository.findById(secondary.getId())).thenReturn(Optional.of(secondary));
        when(mergeEventRepository.save(any(EmpiMergeEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(masterIdentityRepository.save(any(EmpiMasterIdentity.class))).thenAnswer(inv -> inv.getArgument(0));

        EmpiMergeRequestDTO request = new EmpiMergeRequestDTO();
        request.setSecondaryIdentityId(secondary.getId());
        request.setMergeType(EmpiMergeType.MANUAL);

        EmpiMergeEventResponseDTO response = empiService.mergeIdentities(primary.getId(), request);

        assertThat(response.getPrimaryIdentityId()).isEqualTo(primary.getId());
        assertThat(secondary.getStatus()).isEqualTo(EmpiIdentityStatus.MERGED);
        Mockito.verify(mergeEventRepository).save(any(EmpiMergeEvent.class));
    }

    /* ── A null scope is global view ONLY for a verified super-admin ──────
       requireActiveHospitalId() also returns null for a principal whose
       AUTHORITIES say SUPER_ADMIN but whose JWT claim does not (its step-4
       safety net). That caller is refused on every path exactly like a miss. */

    private void authoritiesOnlySuperAdmin() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(null);
        when(roleValidator.isSuperAdminFromJwtClaim()).thenReturn(false);
    }

    @Test
    void findIdentityByPatientId_authoritiesOnlySuperAdminReadsAnExistingIdentityAsAbsent() {
        UUID patientId = UUID.randomUUID();
        authoritiesOnlySuperAdmin();
        EmpiMasterIdentity existing = activeIdentity("EMP-ANY", UUID.randomUUID());
        existing.setPatientId(patientId);
        when(masterIdentityRepository.findByPatientId(patientId)).thenReturn(Optional.of(existing));
        Optional<EmpiIdentityResponseDTO> refused = empiService.findIdentityByPatientId(patientId);

        // The same answer as a patient with no identity at all.
        when(masterIdentityRepository.findByPatientId(patientId)).thenReturn(Optional.empty());
        assertThat(refused).isEmpty().isEqualTo(empiService.findIdentityByPatientId(patientId));
    }

    @Test
    void findIdentityByPatientId_verifiedSuperAdminKeepsGlobalView() {
        UUID patientId = UUID.randomUUID();
        when(roleValidator.requireActiveHospitalId()).thenReturn(null);
        when(roleValidator.isSuperAdminFromJwtClaim()).thenReturn(true);
        EmpiMasterIdentity elsewhere = activeIdentity("EMP-ELSEWHERE", UUID.randomUUID());
        elsewhere.setPatientId(patientId);
        when(masterIdentityRepository.findByPatientId(patientId)).thenReturn(Optional.of(elsewhere));

        assertThat(empiService.findIdentityByPatientId(patientId)).isPresent();
    }

    @Test
    void mergeIdentities_authoritiesOnlySuperAdminIsRefusedExactlyLikeAMiss() {
        renderMessagesWithArguments();
        authoritiesOnlySuperAdmin();
        EmpiMasterIdentity primary = activeIdentity("EMP-P", UUID.randomUUID());
        EmpiMasterIdentity secondary = activeIdentity("EMP-S", primary.getHospitalId());
        when(masterIdentityRepository.findById(primary.getId())).thenReturn(Optional.of(primary));
        when(masterIdentityRepository.findById(secondary.getId())).thenReturn(Optional.of(secondary));
        UUID ghostA = UUID.randomUUID();
        UUID ghostB = UUID.randomUUID();
        when(masterIdentityRepository.findById(ghostA)).thenReturn(Optional.empty());
        when(masterIdentityRepository.findById(ghostB)).thenReturn(Optional.empty());

        Throwable refused = mergeRefusal(primary.getId(), secondary.getId());

        assertIdenticalRefusal(refused, mergeRefusal(ghostA, ghostB));
        assertThat(refused).isExactlyInstanceOf(ResourceNotFoundException.class);
        assertThat(secondary.getStatus()).isNotEqualTo(EmpiIdentityStatus.MERGED);
        Mockito.verify(mergeEventRepository, Mockito.never()).save(any());
        Mockito.verify(masterIdentityRepository, Mockito.never()).save(any());
    }

    @Test
    void mergePatients_authoritiesOnlySuperAdminIsRefusedLikeAForeignPatientBeforeProvisioning() {
        renderMessagesWithArguments();
        UUID primaryPatientId = UUID.randomUUID();
        UUID secondaryPatientId = UUID.randomUUID();

        // Reference answer: a pinned caller naming a patient not registered there.
        UUID callerHospital = UUID.randomUUID();
        when(roleValidator.requireActiveHospitalId()).thenReturn(callerHospital);
        Throwable foreign = catchThrowable(() -> empiService.mergePatients(
            primaryPatientId, secondaryPatientId, EmpiMergeType.MANUAL, null));

        authoritiesOnlySuperAdmin();
        Throwable unverified = catchThrowable(() -> empiService.mergePatients(
            primaryPatientId, secondaryPatientId, EmpiMergeType.MANUAL, null));

        assertIdenticalRefusal(unverified, foreign);
        assertThat(unverified).isExactlyInstanceOf(AccessDeniedException.class);
        // Refused before ensureIdentityForPatient could provision anything.
        Mockito.verify(patientRepository, Mockito.never()).findByIdUnscoped(any());
        Mockito.verify(masterIdentityRepository, Mockito.never()).save(any());
        Mockito.verify(mergeEventRepository, Mockito.never()).save(any());
    }

    /* ── EMPI events leave only after the transaction commits ──────────────
       Driven through a real AbstractPlatformTransactionManager and
       TransactionTemplate: Spring's own begin / commit / rollback and
       synchronization lifecycle, with no resource behind it and no Spring
       context (so no new context shape for the capped CI heap). What is under
       test is exactly that lifecycle — whether the send is registered for
       afterCommit or made inline — which does not depend on a DataSource. */

    /** A transaction manager with Spring's full synchronization lifecycle and no resource. */
    private static final class ResourcelessTransactionManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            // nothing to open
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            // nothing to flush
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            // nothing to undo
        }
    }

    private final TransactionTemplate transaction = new TransactionTemplate(new ResourcelessTransactionManager());

    /**
     * A pinned caller at {@code callerHospital} with both patients
     * registered there; the primary's identity is stamped {@code primaryStamp};
     * the secondary has none, so mergePatients provisions it (IDENTITY_LINKED).
     * Returns the two patient ids.
     */
    private UUID[] kafkaMergeFixture(UUID callerHospital, UUID primaryStamp) {
        kafkaProperties.setEnabled(true);
        when(kafkaTemplateProvider.getIfAvailable()).thenReturn(kafkaTemplate);
        when(roleValidator.requireActiveHospitalId()).thenReturn(callerHospital);
        when(registrationRepository.existsByPatientIdAndHospitalId(any(), eq(callerHospital))).thenReturn(true);

        Map<UUID, EmpiMasterIdentity> store = new LinkedHashMap<>();
        when(masterIdentityRepository.save(any(EmpiMasterIdentity.class))).thenAnswer(inv -> {
            EmpiMasterIdentity saved = inv.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(UUID.randomUUID());
            }
            store.put(saved.getId(), saved);
            return saved;
        });
        when(masterIdentityRepository.findById(any(UUID.class)))
            .thenAnswer(inv -> Optional.ofNullable(store.get(inv.<UUID>getArgument(0))));
        when(masterIdentityRepository.findByPatientId(any(UUID.class))).thenAnswer(inv -> store.values().stream()
            .filter(identity -> inv.getArgument(0).equals(identity.getPatientId()))
            .findFirst());
        when(masterIdentityRepository.existsByEmpiNumberIgnoreCase(any())).thenReturn(false);
        when(mergeEventRepository.save(any(EmpiMergeEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        UUID primaryPatientId = UUID.randomUUID();
        UUID secondaryPatientId = UUID.randomUUID();
        EmpiMasterIdentity primary = activeIdentity("EMP-PRIMARY", primaryStamp);
        primary.setPatientId(primaryPatientId);
        store.put(primary.getId(), primary);

        com.example.hms.model.Patient duplicate = new com.example.hms.model.Patient();
        duplicate.setId(secondaryPatientId);
        duplicate.setHospitalId(callerHospital);
        when(patientRepository.findByIdUnscoped(secondaryPatientId)).thenReturn(Optional.of(duplicate));
        return new UUID[] {primaryPatientId, secondaryPatientId};
    }

    @Test
    void mergePatients_refusedAfterProvisioningRollsBackAndSendsNothing() {
        UUID callerHospital = UUID.randomUUID();
        // Registered here, but the primary's identity is stamped elsewhere, so
        // mergeIdentities refuses AFTER the secondary was provisioned and its
        // IDENTITY_LINKED event raised.
        UUID[] patients = kafkaMergeFixture(callerHospital, UUID.randomUUID());

        Throwable refused = catchThrowable(() -> transaction.executeWithoutResult(status ->
            empiService.mergePatients(patients[0], patients[1], EmpiMergeType.MANUAL, null)));

        assertThat(refused).isExactlyInstanceOf(ResourceNotFoundException.class);
        Mockito.verify(patientRepository).findByIdUnscoped(patients[1]); // provisioning did run
        Mockito.verify(kafkaTemplate, Mockito.never()).send(anyString(), anyString(), any(EmpiEventPayload.class));
    }

    @Test
    void mergePatients_outerTransactionRollingBackAfterASuccessfulMergeSendsNothing() {
        // mergePatients joins the caller's REQUIRED transaction. The merge
        // succeeds; the caller's own work then fails, the whole transaction
        // rolls back, and the merge never happened.
        UUID callerHospital = UUID.randomUUID();
        UUID[] patients = kafkaMergeFixture(callerHospital, callerHospital);

        Throwable outerFailure = catchThrowable(() -> transaction.executeWithoutResult(status -> {
            empiService.mergePatients(patients[0], patients[1], EmpiMergeType.MANUAL, null);
            throw new IllegalStateException("outer work failed after the merge");
        }));

        assertThat(outerFailure).hasMessage("outer work failed after the merge");
        Mockito.verify(kafkaTemplate, Mockito.never()).send(anyString(), anyString(), any(EmpiEventPayload.class));
    }

    @Test
    void mergePatients_sendsItsEventsOnlyOnceTheTransactionCommits() {
        UUID callerHospital = UUID.randomUUID();
        UUID[] patients = kafkaMergeFixture(callerHospital, callerHospital);

        transaction.executeWithoutResult(status -> {
            empiService.mergePatients(patients[0], patients[1], EmpiMergeType.MANUAL, null);
            // Merge done, transaction still open: nothing may have left yet.
            Mockito.verify(kafkaTemplate, Mockito.never())
                .send(anyString(), anyString(), any(EmpiEventPayload.class));
        });

        ArgumentCaptor<EmpiEventPayload> sent = ArgumentCaptor.forClass(EmpiEventPayload.class);
        Mockito.verify(kafkaTemplate, Mockito.times(2)).send(eq(EMPI_TOPIC), anyString(), sent.capture());
        assertThat(sent.getAllValues()).extracting(EmpiEventPayload::getEventType)
            .containsExactly("IDENTITY_LINKED", "IDENTITIES_MERGED");
    }

    /**
     * The shared stub answers every message with its bare key and drops the
     * arguments, which would make a refusal that names the failing id look
     * identical to one that names nothing. The real bundles format {0}; so
     * must the oracle tests.
     */
    private void renderMessagesWithArguments() {
        when(messageSource.getMessage(anyString(), any(), any())).thenAnswer(invocation -> {
            Object[] args = invocation.getArgument(1);
            return invocation.getArgument(0) + " " + java.util.Arrays.toString(args);
        });
    }

    private Throwable mergeRefusal(UUID primaryId, UUID secondaryId) {
        EmpiMergeRequestDTO request = new EmpiMergeRequestDTO();
        request.setSecondaryIdentityId(secondaryId);
        request.setMergeType(EmpiMergeType.MANUAL);
        return catchThrowable(() -> empiService.mergeIdentities(primaryId, request));
    }

    /**
     * Same exception type and message, and the same HTTP answer once the real
     * {@code GlobalExceptionHandler} renders it (status, error, message, path);
     * only the timestamp may differ.
     */
    private static void assertIdenticalRefusal(Throwable first, Throwable second) {
        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(first).isExactlyInstanceOf(second.getClass());
        assertThat(first.getMessage()).isEqualTo(second.getMessage());
        assertThat(rendered(first)).isEqualTo(rendered(second));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> rendered(Throwable refusal) {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        WebRequest request = Mockito.mock(WebRequest.class);
        when(request.getDescription(false)).thenReturn("uri=/api/empi/identities/merge");
        ResponseEntity<Object> response;
        if (refusal instanceof ResourceNotFoundException notFound) {
            response = handler.handleResourceNotFoundException(notFound, request);
        } else if (refusal instanceof AccessDeniedException denied) {
            response = handler.handleAccessDenied(denied, request);
        } else if (refusal instanceof BusinessException business) {
            response = handler.handleBusinessException(business, request);
        } else {
            throw new AssertionError("unexpected refusal type " + refusal.getClass(), refusal);
        }
        Map<String, Object> body = new LinkedHashMap<>((Map<String, Object>) response.getBody());
        body.remove("timestamp");
        body.put("httpStatus", response.getStatusCode().value());
        return body;
    }
}
