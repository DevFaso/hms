package com.example.hms.service;

import com.example.hms.config.KafkaProperties;
import com.example.hms.enums.empi.EmpiAliasType;
import com.example.hms.enums.empi.EmpiIdentityStatus;
import com.example.hms.enums.empi.EmpiMergeType;
import com.example.hms.exception.BusinessException;
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
import org.springframework.kafka.core.KafkaTemplate;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
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
    private MessageSource messageSource;

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
            new EmpiMapper(),
            kafkaProperties,
            kafkaTemplateProvider
        );

        HospitalContextHolder.setContext(HospitalContext.builder()
            .principalUserId(UUID.randomUUID())
            .activeHospitalId(UUID.randomUUID())
            .activeOrganizationId(UUID.randomUUID())
            .build());
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
        KafkaTemplate<String, EmpiEventPayload> kafkaTemplate = Mockito.mock(KafkaTemplate.class);
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
        KafkaTemplate<String, EmpiEventPayload> kafkaTemplate = Mockito.mock(KafkaTemplate.class);
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
}
