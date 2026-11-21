package com.example.hms.service;

import com.example.hms.enums.LabTestDefinitionApprovalStatus;
import com.example.hms.enums.SensitivityCategory;
import com.example.hms.mapper.LabTestDefinitionMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.LabTestReferenceRange;
import com.example.hms.model.Role;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.LabTestDefinitionRequestDTO;
import com.example.hms.payload.dto.LabTestDefinitionResponseDTO;
import com.example.hms.payload.dto.LabTestReferenceRangeDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.LabTestDefinitionRepository;
import com.example.hms.repository.RoleRepository;
import com.example.hms.repository.TenantScopedDataJpaTest;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.security.tenant.ActingScopeResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * r66 D2902: reclassifying an ACTIVE lab test, against a real persistence context.
 *
 * <p>The lab director's editor answered 500 « erreur inattendue » when the sensitive category of a live
 * test was changed, yet the change WAS saved and no audit row was written. {@code update} ran with no
 * transaction (open-in-view is off): {@code repository.save} committed on its own, and the audit that
 * follows read the definition's hospital, a lazy proxy whose session had just closed. The mock-based
 * service tests could not see it: a mocked {@code Hospital} is never a proxy.
 *
 * <p>So this class runs as the request does, with NO transaction around the call
 * ({@code NOT_SUPPORTED}), and asks for the three things the director is owed: a clean answer, the new
 * category on the row, and the audit of the change (written after the commit, with the hospital's name).
 *
 * <p>The service is built here, behind the same annotation-driven transaction advice Spring puts around
 * the bean ({@link TransactionInterceptor} reading {@code @Transactional}), rather than imported into the
 * slice: an {@code @Import} or a mock bean would give this class a context, and an
 * {@code EntityManagerFactory}, of its own (see {@link TenantScopedDataJpaTest}).
 */
@TenantScopedDataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LabDefinitionSensitivityChangePersistenceTest {

    @Autowired private LabTestDefinitionRepository definitionRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Autowired private com.example.hms.repository.LabOrderRepository labOrderRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    private final AuditEventLogService auditEventLogService = mock(AuditEventLogService.class);
    private LabTestDefinitionService service;

    private final String tag = UUID.randomUUID().toString().substring(0, 8);

    private Hospital hospital;
    private Role role;
    private User director;
    private UserRoleHospitalAssignment assignment;
    private LabTestDefinition definition;

    /** The service as the application wires it: its {@code @Transactional} methods run in a transaction. */
    private LabTestDefinitionService transactionalService() {
        ProxyFactory factory = new ProxyFactory(new LabTestDefinitionServiceImpl(definitionRepository,
            new LabTestDefinitionMapper(), assignmentRepository, userRepository, hospitalRepository,
            mock(ActingScopeResolver.class), auditEventLogService, labOrderRepository));
        factory.addAdvice(new TransactionInterceptor((TransactionManager) transactionManager,
            new AnnotationTransactionAttributeSource()));
        return (LabTestDefinitionService) factory.getProxy();
    }

    @BeforeEach
    void seed() {
        service = transactionalService();
        HospitalContextHolder.setContext(HospitalContext.builder().superAdmin(true).build());
        hospital = hospitalRepository.save(Hospital.builder().name("Clinique R66 " + tag).code("R66" + tag).build());
        role = roleRepository.save(Role.builder()
            .name("Lab director " + tag).code("ROLE_LAB_DIRECTOR").description("Lab director").build());
        director = userRepository.save(User.builder()
            .username("director-" + tag).passwordHash("hashed-password")
            .email("director-" + tag + "@r66.test").firstName("Awa").lastName("Directrice")
            .phoneNumber("+22670" + Math.abs(tag.hashCode() % 1_000_000)).isActive(true).isDeleted(false).build());
        assignment = assignmentRepository.save(UserRoleHospitalAssignment.builder()
            .assignmentCode("ASSIGN-" + tag).description("Lab director")
            .user(director).hospital(hospital).role(role)
            .startDate(LocalDate.now()).assignedAt(LocalDateTime.now()).active(true).build());
        definition = definitionRepository.save(LabTestDefinition.builder()
            .testCode("ACHBC" + tag.toUpperCase()).name("Anticorps anti-HBc").unit("UI/L").sampleType("Sérum")
            .hospital(hospital)
            .approvalStatus(LabTestDefinitionApprovalStatus.ACTIVE).active(true)
            .approvedAt(LocalDateTime.now())
            .sensitivityCategory(SensitivityCategory.VIRAL_HEPATITIS)
            .referenceRanges(new ArrayList<>(List.of(
                LabTestReferenceRange.builder().minValue(0.0).maxValue(1.0).unit("UI/L").build())))
            .build());
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(director.getUsername(), "n/a", List.of()));
    }

    @AfterEach
    void clean() {
        SecurityContextHolder.clearContext();
        definitionRepository.deleteById(definition.getId());
        assignmentRepository.deleteById(assignment.getId());
        userRepository.deleteById(director.getId());
        roleRepository.deleteById(role.getId());
        hospitalRepository.deleteById(hospital.getId());
        HospitalContextHolder.clear();
    }

    /** What the editor sends for a live test: every field as it stands, plus the new category. */
    private LabTestDefinitionRequestDTO editorBody() {
        LabTestDefinitionRequestDTO body = new LabTestDefinitionRequestDTO();
        body.setTestCode(definition.getTestCode());
        body.setName(definition.getName());
        body.setUnit(definition.getUnit());
        body.setSampleType(definition.getSampleType());
        body.setActive(true);
        LabTestReferenceRangeDTO range = new LabTestReferenceRangeDTO();
        range.setMinValue(0.0);
        range.setMaxValue(1.0);
        range.setUnit("UI/L");
        body.setReferenceRanges(new ArrayList<>(List.of(range)));
        return body;
    }

    @Test
    void changingTheCategoryOfAnActiveTest_answersCleanly_savesIt_andAuditsIt() {
        LabTestDefinitionRequestDTO body = editorBody();
        body.setSensitivityCategory(SensitivityCategory.HIV);

        LabTestDefinitionResponseDTO answer = service.update(definition.getId(), body);

        assertThat(answer.getSensitivityCategory()).isEqualTo(SensitivityCategory.HIV);
        assertThat(definitionRepository.findById(definition.getId()).orElseThrow().getSensitivityCategory())
            .isEqualTo(SensitivityCategory.HIV);
        ArgumentCaptor<AuditEventRequestDTO> audit = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditEventLogService).logEvent(audit.capture());
        assertThat(audit.getValue().getEventDescription())
            .isEqualTo("Sensitivity category changed from VIRAL_HEPATITIS to HIV");
        assertThat(audit.getValue().getHospitalId()).isEqualTo(hospital.getId());
        assertThat(audit.getValue().getHospitalName()).isEqualTo(hospital.getName());
        assertThat(audit.getValue().getUserId()).isEqualTo(director.getId());
    }

    @Test
    void clearingTheCategoryOfAnActiveTest_answersCleanly_savesIt_andAuditsIt() {
        LabTestDefinitionRequestDTO body = editorBody();
        body.setClearSensitivityCategory(true);

        LabTestDefinitionResponseDTO answer = service.update(definition.getId(), body);

        assertThat(answer.getSensitivityCategory()).isNull();
        assertThat(definitionRepository.findById(definition.getId()).orElseThrow().getSensitivityCategory()).isNull();
        ArgumentCaptor<AuditEventRequestDTO> audit = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditEventLogService).logEvent(audit.capture());
        assertThat(audit.getValue().getEventDescription())
            .isEqualTo("Sensitivity category changed from VIRAL_HEPATITIS to null");
    }

    @Test
    void anEditThatLeavesTheCategoryAlone_writesNoSensitivityAudit() {
        LabTestDefinitionRequestDTO body = editorBody();
        body.setName("Anticorps anti-HBc totaux");

        LabTestDefinitionResponseDTO answer = service.update(definition.getId(), body);

        assertThat(answer.getSensitivityCategory()).isEqualTo(SensitivityCategory.VIRAL_HEPATITIS);
        assertThat(answer.getName()).isEqualTo("Anticorps anti-HBc totaux");
        verify(auditEventLogService, never()).logEvent(org.mockito.ArgumentMatchers.any());
    }

    /**
     * The class inside this service: every entry point that writes an audit after the commit reads the
     * rows it names inside a transaction. A new one that forgets it is this defect again.
     */
    @Test
    void everyEntryPointThatAuditsAfterCommit_isTransactional() throws NoSuchMethodException {
        for (java.lang.reflect.Method entryPoint : List.of(
                LabTestDefinitionServiceImpl.class.getMethod("update", UUID.class, LabTestDefinitionRequestDTO.class),
                LabTestDefinitionServiceImpl.class.getMethod("createNewVersion", UUID.class),
                LabTestDefinitionServiceImpl.class.getMethod("applyRecommendedSensitivity", List.class),
                LabTestDefinitionServiceImpl.class.getMethod("processApprovalAction", UUID.class,
                    com.example.hms.payload.dto.LabTestDefinitionApprovalRequestDTO.class))) {
            assertThat(entryPoint.isAnnotationPresent(Transactional.class))
                .as("%s audits after the commit and must run in a transaction", entryPoint.getName())
                .isTrue();
        }
    }

    @Test
    void aRefusedEditOfALockedField_changesNothing_andAuditsNothing() {
        // The unit is locked on a live test (QA D705). The category in the same body must not be saved
        // on its own, nor audited, when the edit as a whole is refused.
        LabTestDefinitionRequestDTO body = editorBody();
        body.setUnit("mUI/mL");
        body.setSensitivityCategory(SensitivityCategory.HIV);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.update(definition.getId(), body))
            .isInstanceOf(IllegalStateException.class);

        assertThat(definitionRepository.findById(definition.getId()).orElseThrow().getSensitivityCategory())
            .isEqualTo(SensitivityCategory.VIRAL_HEPATITIS);
        verify(auditEventLogService, never()).logEvent(org.mockito.ArgumentMatchers.any());
    }
}
