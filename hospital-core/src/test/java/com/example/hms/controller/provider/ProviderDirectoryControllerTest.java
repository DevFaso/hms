package com.example.hms.controller.provider;

import com.example.hms.payload.dto.provider.ProviderDirectoryEntryDTO;
import com.example.hms.service.provider.ProviderDirectoryService;
import com.example.hms.service.provider.ProviderOrganisationsFlag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Provider plan AC-14 (ProviderFlagOffTest's directory leg): with
 * {@code provider.organisations.enabled} off the directory is empty, and the
 * flag is the handler's FIRST check, so nothing about the request (an
 * invalid type, the caller's scope) is looked at.
 */
@ExtendWith(MockitoExtension.class)
class ProviderDirectoryControllerTest {

    @Mock private ProviderOrganisationsFlag organisationsFlag;
    @Mock private ProviderDirectoryService directoryService;
    @InjectMocks private ProviderDirectoryController controller;

    @Test
    @DisplayName("flag off: an empty list, and the service is never asked, whatever the parameters")
    void flagOffIsEmpty() {
        when(organisationsFlag.isEnabled()).thenReturn(false);

        assertThat(controller.search("NOT-A-TYPE", "x").getBody()).isEmpty();
        assertThat(controller.search(null, null).getBody()).isEmpty();
        verifyNoInteractions(directoryService);
    }

    @Test
    @DisplayName("flag on: the service answers")
    void flagOnDelegates() {
        when(organisationsFlag.isEnabled()).thenReturn(true);
        ProviderDirectoryEntryDTO entry = ProviderDirectoryEntryDTO.builder().id(UUID.randomUUID()).build();
        when(directoryService.search("PHARMACY", "centre")).thenReturn(List.of(entry));

        assertThat(controller.search("PHARMACY", "centre").getBody()).containsExactly(entry);
        verify(directoryService).search("PHARMACY", "centre");
    }
}
