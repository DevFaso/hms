package com.example.hms.service;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.platform.GlobalSetting;
import com.example.hms.payload.dto.globalsetting.GlobalSettingResponseDTO;
import com.example.hms.repository.platform.GlobalSettingRepository;
import com.example.hms.service.impl.GlobalSettingServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GlobalSettingServiceImplTest {

    @Mock private GlobalSettingRepository repository;
    @InjectMocks private GlobalSettingServiceImpl service;

    private GlobalSetting buildSetting(UUID id, String key, String value) {
        GlobalSetting s = GlobalSetting.builder()
                .settingKey(key)
                .settingValue(value)
                .category("general")
                .description("Test setting")
                .updatedBy("admin")
                .build();
        s.setId(id);
        s.setCreatedAt(LocalDateTime.now());
        s.setUpdatedAt(LocalDateTime.now());
        return s;
    }

    // ── listAll ──────────────────────────────────────────────────

    @Test
    void listAll_success() {
        GlobalSetting s1 = buildSetting(UUID.randomUUID(), "app.name", "HMS");
        GlobalSetting s2 = buildSetting(UUID.randomUUID(), "app.version", "1.0");
        when(repository.findAllByOrderBySettingKeyAsc()).thenReturn(List.of(s1, s2));

        List<GlobalSettingResponseDTO> result = service.listAll();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getSettingKey()).isEqualTo("app.name");
        assertThat(result.get(1).getSettingKey()).isEqualTo("app.version");
    }

    @Test
    void listAll_empty() {
        when(repository.findAllByOrderBySettingKeyAsc()).thenReturn(Collections.emptyList());

        List<GlobalSettingResponseDTO> result = service.listAll();

        assertThat(result).isEmpty();
    }

    // ── listByCategory ───────────────────────────────────────────

    @Test
    void listByCategory_success() {
        GlobalSetting s = buildSetting(UUID.randomUUID(), "smtp.host", "mail.example.com");
        s.setCategory("email");
        when(repository.findByCategoryIgnoreCaseOrderBySettingKeyAsc("email"))
                .thenReturn(List.of(s));

        List<GlobalSettingResponseDTO> result = service.listByCategory("email");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getCategory()).isEqualTo("email");
    }

    // ── getByKey ─────────────────────────────────────────────────

    @Test
    void getByKey_success() {
        GlobalSetting s = buildSetting(UUID.randomUUID(), "app.name", "HMS");
        when(repository.findBySettingKeyIgnoreCase("app.name")).thenReturn(Optional.of(s));

        GlobalSettingResponseDTO dto = service.getByKey("app.name");

        assertThat(dto.getSettingKey()).isEqualTo("app.name");
        assertThat(dto.getSettingValue()).isEqualTo("HMS");
    }

    @Test
    void getByKey_trims_input() {
        GlobalSetting s = buildSetting(UUID.randomUUID(), "app.name", "HMS");
        when(repository.findBySettingKeyIgnoreCase("app.name")).thenReturn(Optional.of(s));

        GlobalSettingResponseDTO dto = service.getByKey("  app.name  ");

        assertThat(dto.getSettingKey()).isEqualTo("app.name");
    }

    @Test
    void getByKey_notFound_throwsResourceNotFoundException() {
        when(repository.findBySettingKeyIgnoreCase("missing.key")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getByKey("missing.key"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── upsert (create) ──────────────────────────────────────────

    @Test
    void upsert_createsNewSetting() {
        when(repository.findBySettingKeyIgnoreCase("new.key")).thenReturn(Optional.empty());
        when(repository.save(any(GlobalSetting.class))).thenAnswer(inv -> {
            GlobalSetting entity = inv.getArgument(0);
            entity.setId(UUID.randomUUID());
            entity.setCreatedAt(LocalDateTime.now());
            entity.setUpdatedAt(LocalDateTime.now());
            return entity;
        });

        GlobalSettingResponseDTO dto = service.upsert("new.key", "value1",
                "general", "A new setting", "admin");

        assertThat(dto.getSettingKey()).isEqualTo("new.key");
        assertThat(dto.getSettingValue()).isEqualTo("value1");

        ArgumentCaptor<GlobalSetting> captor = ArgumentCaptor.forClass(GlobalSetting.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getSettingKey()).isEqualTo("new.key");
        assertThat(captor.getValue().getUpdatedBy()).isEqualTo("admin");
    }

    @Test
    void upsert_updatesExistingSetting() {
        GlobalSetting existing = buildSetting(UUID.randomUUID(), "existing.key", "old-value");
        when(repository.findBySettingKeyIgnoreCase("existing.key"))
                .thenReturn(Optional.of(existing));
        when(repository.save(any(GlobalSetting.class))).thenAnswer(inv -> inv.getArgument(0));

        GlobalSettingResponseDTO dto = service.upsert("existing.key", "new-value",
                "updated-cat", "Updated desc", "admin2");

        assertThat(dto.getSettingValue()).isEqualTo("new-value");
        assertThat(dto.getUpdatedBy()).isEqualTo("admin2");
    }

    @Test
    void upsert_sanitizesCategory_truncates() {
        when(repository.findBySettingKeyIgnoreCase("k")).thenReturn(Optional.empty());
        when(repository.save(any(GlobalSetting.class))).thenAnswer(inv -> {
            GlobalSetting entity = inv.getArgument(0);
            entity.setId(UUID.randomUUID());
            entity.setCreatedAt(LocalDateTime.now());
            entity.setUpdatedAt(LocalDateTime.now());
            return entity;
        });

        String longCategory = "a".repeat(100);
        service.upsert("k", "v", longCategory, "desc", "admin");

        ArgumentCaptor<GlobalSetting> captor = ArgumentCaptor.forClass(GlobalSetting.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getCategory()).hasSize(60);
    }

    @Test
    void upsert_sanitizesDescription_truncates() {
        when(repository.findBySettingKeyIgnoreCase("k")).thenReturn(Optional.empty());
        when(repository.save(any(GlobalSetting.class))).thenAnswer(inv -> {
            GlobalSetting entity = inv.getArgument(0);
            entity.setId(UUID.randomUUID());
            entity.setCreatedAt(LocalDateTime.now());
            entity.setUpdatedAt(LocalDateTime.now());
            return entity;
        });

        String longDesc = "d".repeat(300);
        service.upsert("k", "v", "cat", longDesc, "admin");

        ArgumentCaptor<GlobalSetting> captor = ArgumentCaptor.forClass(GlobalSetting.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getDescription()).hasSize(255);
    }

    @Test
    void upsert_blankCategory_setToNull() {
        when(repository.findBySettingKeyIgnoreCase("k")).thenReturn(Optional.empty());
        when(repository.save(any(GlobalSetting.class))).thenAnswer(inv -> {
            GlobalSetting entity = inv.getArgument(0);
            entity.setId(UUID.randomUUID());
            entity.setCreatedAt(LocalDateTime.now());
            entity.setUpdatedAt(LocalDateTime.now());
            return entity;
        });

        service.upsert("k", "v", "  ", null, "admin");

        ArgumentCaptor<GlobalSetting> captor = ArgumentCaptor.forClass(GlobalSetting.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getCategory()).isNull();
        assertThat(captor.getValue().getDescription()).isNull();
    }

    @Test
    void upsert_blankKey_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> service.upsert("  ", "v", "cat", "desc", "admin"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── delete ───────────────────────────────────────────────────

    @Test
    void delete_existing_deletesSuccessfully() {
        UUID id = UUID.randomUUID();
        GlobalSetting s = buildSetting(id, "to.delete", "val");
        when(repository.findById(id)).thenReturn(Optional.of(s));

        service.delete(id, "admin");

        verify(repository).delete(s);
    }

    @Test
    void delete_nonExisting_noOp() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        service.delete(id, "admin");

        verify(repository, never()).delete(any());
    }

    // ── DTO mapping ──────────────────────────────────────────────

    @Test
    void toDto_mapsAllFields() {
        UUID id = UUID.randomUUID();
        GlobalSetting s = buildSetting(id, "key1", "val1");

        when(repository.findBySettingKeyIgnoreCase("key1")).thenReturn(Optional.of(s));

        GlobalSettingResponseDTO dto = service.getByKey("key1");

        assertThat(dto.getId()).isEqualTo(id);
        assertThat(dto.getSettingKey()).isEqualTo("key1");
        assertThat(dto.getSettingValue()).isEqualTo("val1");
        assertThat(dto.getCategory()).isEqualTo("general");
        assertThat(dto.getDescription()).isEqualTo("Test setting");
        assertThat(dto.getUpdatedBy()).isEqualTo("admin");
        assertThat(dto.getCreatedAt()).isNotNull();
        assertThat(dto.getUpdatedAt()).isNotNull();
    }
}
