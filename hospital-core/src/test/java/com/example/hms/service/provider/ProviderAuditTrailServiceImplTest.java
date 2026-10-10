package com.example.hms.service.provider;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.FacilityType;
import com.example.hms.model.Hospital;
import com.example.hms.payload.dto.provider.ProviderAuditEntryDTO;
import com.example.hms.payload.dto.provider.ProviderAuditPageDTO;
import com.example.hms.repository.AuditEventLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The provider facility audit trail (plan §3.1): its admin only, the seat
 * checked before the paging parameters are read, and the rows of the
 * caller's own facility.
 */
@ExtendWith(MockitoExtension.class)
class ProviderAuditTrailServiceImplTest {

    @Mock private ProviderSeatResolver seatResolver;
    @Mock private AuditEventLogRepository auditEventLogRepository;

    private ProviderAuditTrailServiceImpl service;
    private Hospital pharmacy;

    @BeforeEach
    void setUp() {
        service = new ProviderAuditTrailServiceImpl(seatResolver, auditEventLogRepository);
        pharmacy = Hospital.builder().name("Pharmacie").code("PH-1").facilityType(FacilityType.PHARMACY).build();
        pharmacy.setId(UUID.randomUUID());
    }

    @Test
    @DisplayName("no admin seat: empty whatever the parameters, and the trail is never read")
    void noAdminSeatAnswersEmptyBeforeTheParameters() {
        when(seatResolver.currentAdmin()).thenReturn(Optional.empty());

        assertThat(service.trail(null, null)).isEmpty();
        assertThat(service.trail("not-a-number", "-4")).isEmpty();
        verifyNoInteractions(auditEventLogRepository);
    }

    @Test
    @DisplayName("the admin reads its own facility's page, defaults first page of 20")
    void adminReadsOwnFacility() {
        admin();
        ProviderAuditEntryDTO row = new ProviderAuditEntryDTO(UUID.randomUUID(), LocalDateTime.now(),
            AuditEventType.DATA_UPDATE, AuditStatus.SUCCESS, UUID.randomUUID(), "padmin", "PROVIDER_ADMIN",
            "PROVIDER_FACILITY", pharmacy.getId().toString());
        when(auditEventLogRepository.findProviderFacilityTrail(eq(pharmacy.getId()), any()))
            .thenReturn(new PageImpl<>(List.of(row), PageRequest.of(0, 20), 41));

        Optional<ProviderAuditPageDTO> page = service.trail(null, " ");

        assertThat(page).isPresent();
        assertThat(page.get().getEntries()).containsExactly(row);
        assertThat(page.get().getPage()).isZero();
        assertThat(page.get().getSize()).isEqualTo(20);
        assertThat(page.get().getTotalElements()).isEqualTo(41);
        assertThat(page.get().isHasMore()).isTrue();
    }

    @Test
    @DisplayName("a size above the cap is capped at 100")
    void sizeIsCapped() {
        admin();
        when(auditEventLogRepository.findProviderFacilityTrail(eq(pharmacy.getId()), any()))
            .thenReturn(new PageImpl<>(List.of()));

        ProviderAuditPageDTO page = service.trail("2", "5000").orElseThrow();

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(auditEventLogRepository).findProviderFacilityTrail(eq(pharmacy.getId()), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(ProviderAuditTrailService.MAX_PAGE_SIZE);
        assertThat(page.getSize()).isEqualTo(ProviderAuditTrailService.MAX_PAGE_SIZE);
    }

    @Test
    @DisplayName("from the admin, a malformed page or size is a 400 (IllegalArgumentException)")
    void malformedParametersFromTheAdmin() {
        admin();

        assertThatThrownBy(() -> service.trail("abc", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.trail("-1", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.trail(null, "0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.trail(null, "1.5")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(auditEventLogRepository);
    }

    private void admin() {
        when(seatResolver.currentAdmin()).thenReturn(Optional.of(new ProviderSeat(pharmacy, UUID.randomUUID(), true)));
    }
}
