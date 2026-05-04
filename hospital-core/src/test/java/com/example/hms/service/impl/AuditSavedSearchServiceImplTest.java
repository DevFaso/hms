package com.example.hms.service.impl;

import com.example.hms.exception.BusinessRuleException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.exception.UnauthorizedException;
import com.example.hms.model.platform.AuditSavedSearch;
import com.example.hms.payload.dto.superadmin.AuditSavedSearchRequestDTO;
import com.example.hms.payload.dto.superadmin.AuditSavedSearchResponseDTO;
import com.example.hms.repository.platform.AuditSavedSearchRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditSavedSearchServiceImplTest {

    private static final String OWNER = "super.admin";
    private static final String OTHER = "other.admin";

    @Mock private AuditSavedSearchRepository repository;
    @InjectMocks private AuditSavedSearchServiceImpl service;

    @BeforeEach
    void setUp() {
        HospitalContextHolder.setContext(HospitalContext.builder()
            .principalUserId(UUID.randomUUID())
            .principalUsername(OWNER)
            .superAdmin(true)
            .permittedOrganizationIds(Set.of())
            .build());
    }

    @AfterEach
    void tearDown() {
        HospitalContextHolder.clear();
    }

    private AuditSavedSearch row(String owner, String name, boolean shared) {
        AuditSavedSearch s = AuditSavedSearch.builder()
            .ownerUsername(owner)
            .name(name)
            .filterJson("{\"foo\":\"bar\"}")
            .shared(shared)
            .build();
        s.setId(UUID.randomUUID());
        return s;
    }

    private AuditSavedSearchRequestDTO request(String name, String filterJson, boolean shared) {
        return AuditSavedSearchRequestDTO.builder()
            .name(name).filterJson(filterJson).shared(shared).build();
    }

    @Test
    void listVisibleReturnsOwnedAndSharedRows() {
        AuditSavedSearch mine = row(OWNER, "my-failures", false);
        AuditSavedSearch sharedByOther = row(OTHER, "logout-spikes", true);
        when(repository.findOwnedAndShared(OWNER)).thenReturn(List.of(mine, sharedByOther));

        List<AuditSavedSearchResponseDTO> result = service.listVisible();

        assertThat(result).extracting(AuditSavedSearchResponseDTO::getName)
            .containsExactly("my-failures", "logout-spikes");
        assertThat(result).extracting(AuditSavedSearchResponseDTO::getOwnerUsername)
            .containsExactly(OWNER, OTHER);
    }

    @Test
    void createPersistsNewRowForCaller() {
        when(repository.findByOwnerUsernameAndName(OWNER, "test")).thenReturn(Optional.empty());
        when(repository.save(any(AuditSavedSearch.class))).thenAnswer(inv -> {
            AuditSavedSearch e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });

        AuditSavedSearchResponseDTO result = service.create(request("test", "{}", true));

        assertThat(result.getOwnerUsername()).isEqualTo(OWNER);
        assertThat(result.getName()).isEqualTo("test");
        assertThat(result.isShared()).isTrue();
    }

    @Test
    void createRejectsDuplicateNameForSameOwner() {
        when(repository.findByOwnerUsernameAndName(OWNER, "dup"))
            .thenReturn(Optional.of(row(OWNER, "dup", false)));
        AuditSavedSearchRequestDTO dup = request("dup", "{}", false);

        assertThatThrownBy(() -> service.create(dup))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("already exists");
        verify(repository, never()).save(any());
    }

    @Test
    void createRejectsBlankName() {
        AuditSavedSearchRequestDTO req = request("   ", "{}", false);
        assertThatThrownBy(() -> service.create(req))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("name is required");
    }

    @Test
    void createRejectsBlankFilterJson() {
        AuditSavedSearchRequestDTO req = request("name", "", false);
        assertThatThrownBy(() -> service.create(req))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("filterJson is required");
    }

    @Test
    void updateRejectsNonOwnerEvenForSharedRow() {
        AuditSavedSearch foreign = row(OTHER, "shared-search", true);
        UUID id = foreign.getId();
        AuditSavedSearchRequestDTO renamed = request("renamed", "{}", true);
        when(repository.findById(id)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.update(id, renamed))
            .isInstanceOf(UnauthorizedException.class)
            .hasMessageContaining("Only the owner");
        verify(repository, never()).save(any());
    }

    @Test
    void updateAllowsOwnerToToggleShared() {
        AuditSavedSearch mine = row(OWNER, "my-search", false);
        when(repository.findById(mine.getId())).thenReturn(Optional.of(mine));
        when(repository.save(any(AuditSavedSearch.class))).thenAnswer(inv -> inv.getArgument(0));

        AuditSavedSearchResponseDTO result = service.update(
            mine.getId(), request("my-search", "{\"new\":true}", true));

        assertThat(result.isShared()).isTrue();
        assertThat(result.getFilterJson()).contains("\"new\":true");
    }

    @Test
    void updateRejectsRenameThatCollidesWithExistingOwnedRow() {
        UUID rowId = UUID.randomUUID();
        AuditSavedSearch mine = row(OWNER, "current", false);
        mine.setId(rowId);
        AuditSavedSearch other = row(OWNER, "taken", false);
        AuditSavedSearchRequestDTO taken = request("taken", "{}", false);

        when(repository.findById(rowId)).thenReturn(Optional.of(mine));
        when(repository.findByOwnerUsernameAndName(OWNER, "taken")).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.update(rowId, taken))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("already exists");
    }

    @Test
    void deleteRequiresOwnership() {
        AuditSavedSearch foreign = row(OTHER, "shared", true);
        UUID id = foreign.getId();
        when(repository.findById(id)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.delete(id))
            .isInstanceOf(UnauthorizedException.class);
        verify(repository, never()).delete(any(AuditSavedSearch.class));
    }

    @Test
    void deleteRemovesOwnedRow() {
        AuditSavedSearch mine = row(OWNER, "my-search", false);
        when(repository.findById(mine.getId())).thenReturn(Optional.of(mine));

        service.delete(mine.getId());

        verify(repository).delete(mine);
    }

    @Test
    void deleteThrowsResourceNotFoundForUnknownId() {
        UUID unknown = UUID.randomUUID();
        when(repository.findById(unknown)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.delete(unknown))
            .isInstanceOf(ResourceNotFoundException.class);
    }
}
