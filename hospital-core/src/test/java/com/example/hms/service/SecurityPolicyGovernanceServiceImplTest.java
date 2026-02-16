package com.example.hms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hms.enums.SecurityPolicyApprovalStatus;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.security.SecurityPolicyApproval;
import com.example.hms.model.security.SecurityPolicyBaseline;
import com.example.hms.payload.dto.superadmin.SecurityPolicyBaselineRequestDTO;
import com.example.hms.repository.SecurityPolicyApprovalRepository;
import com.example.hms.repository.SecurityPolicyBaselineRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SecurityPolicyGovernanceServiceImplTest {

    @Mock
    private SecurityPolicyBaselineRepository baselineRepository;

    @Mock
    private SecurityPolicyApprovalRepository approvalRepository;

    @Mock
    private ObjectMapper objectMapper;

    private SecurityPolicyGovernanceServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SecurityPolicyGovernanceServiceImpl(baselineRepository, approvalRepository, objectMapper);
    }

    @Test
    void createBaselinePersistsEntityAndReturnsResponse() {
        when(baselineRepository.count()).thenReturn(0L);
        when(baselineRepository.save(any(SecurityPolicyBaseline.class))).thenAnswer(invocation -> {
            SecurityPolicyBaseline baseline = invocation.getArgument(0);
            baseline.setId(UUID.randomUUID());
            baseline.setCreatedAt(LocalDateTime.now());
            baseline.setUpdatedAt(LocalDateTime.now());
            return baseline;
        });

        SecurityPolicyBaselineRequestDTO request = new SecurityPolicyBaselineRequestDTO();
        request.setTitle("Test baseline");
        request.setEnforcementLevel("GLOBAL");
        request.setPolicyCount(3);
        request.setSummary("Summary");
        request.setControlObjectivesJson("{}");
        request.setCreatedBy("admin@system");

        var response = service.createBaseline(request);

        assertThat(response.getId()).isNotNull();
        assertThat(response.getBaselineVersion()).startsWith("baseline-");
        assertThat(response.getTitle()).isEqualTo("Test baseline");
        assertThat(response.getPolicyCount()).isEqualTo(3);
        assertThat(response.getCreatedBy()).isEqualTo("admin@system");

        ArgumentCaptor<SecurityPolicyBaseline> captor = ArgumentCaptor.forClass(SecurityPolicyBaseline.class);
        verify(baselineRepository).save(captor.capture());
        assertThat(captor.getValue().getTitle()).isEqualTo("Test baseline");
    }

    @Test
    void listPendingApprovalsReturnsRepositoryValues() {
        SecurityPolicyApproval approval = SecurityPolicyApproval.builder()
            .policyName("Data retention")
            .status(SecurityPolicyApprovalStatus.PENDING)
            .requestedBy("alice@example.com")
            .requiredBy(LocalDateTime.now().plusDays(2))
            .build();
        approval.setId(UUID.randomUUID());

        when(approvalRepository.findByStatusOrderByRequiredByAsc(SecurityPolicyApprovalStatus.PENDING))
            .thenReturn(List.of(approval));

        var approvals = service.listPendingApprovals();

        assertThat(approvals).hasSize(1);
        assertThat(approvals.get(0).getPolicyName()).isEqualTo("Data retention");
        verify(approvalRepository).findByStatusOrderByRequiredByAsc(SecurityPolicyApprovalStatus.PENDING);
    }

    @Test
    void exportLatestBaselineSerializesPayload() throws JsonProcessingException {
        SecurityPolicyBaseline baseline = SecurityPolicyBaseline.builder()
            .baselineVersion("baseline-2025")
            .title("Global security baseline")
            .summary("Summary")
            .enforcementLevel("GLOBAL")
            .policyCount(4)
            .controlObjectivesJson("{}")
            .createdBy("tester")
            .build();
        baseline.setId(UUID.randomUUID());
        baseline.setCreatedAt(LocalDateTime.of(2025, 1, 10, 10, 0));
        baseline.setUpdatedAt(LocalDateTime.of(2025, 1, 10, 10, 0));

        when(baselineRepository.findFirstByOrderByCreatedAtDesc()).thenReturn(Optional.of(baseline));
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"baseline\":true}");

        var export = service.exportLatestBaseline();

        assertThat(export.getBaselineVersion()).isEqualTo("baseline-2025");
        assertThat(export.getFileName()).contains("baseline-2025");
        assertThat(export.getContentType()).isEqualTo("application/json");
        assertThat(export.getBase64Content()).isEqualTo("eyJiYXNlbGluZSI6dHJ1ZX0=");
        assertThat(export.getGeneratedAt()).isAfterOrEqualTo(OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(5));

        verify(objectMapper).writeValueAsString(any());
    }

    @Test
    void exportLatestBaselineThrowsWhenMissing() {
        when(baselineRepository.findFirstByOrderByCreatedAtDesc()).thenReturn(Optional.empty());

        assertThatThrownBy(service::exportLatestBaseline)
            .isInstanceOf(ResourceNotFoundException.class);
    }
}
