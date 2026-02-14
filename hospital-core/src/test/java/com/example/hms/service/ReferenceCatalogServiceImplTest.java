package com.example.hms.service;

import com.example.hms.enums.ReferenceCatalogStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.reference.ReferenceCatalog;
import com.example.hms.model.reference.ReferenceCatalogEntry;
import com.example.hms.payload.dto.reference.CatalogImportResponseDTO;
import com.example.hms.payload.dto.reference.CreateReferenceCatalogRequestDTO;
import com.example.hms.payload.dto.reference.ReferenceCatalogResponseDTO;
import com.example.hms.payload.dto.reference.SchedulePublishRequestDTO;
import com.example.hms.repository.reference.ReferenceCatalogEntryRepository;
import com.example.hms.repository.reference.ReferenceCatalogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReferenceCatalogServiceImplTest {

    @Mock private ReferenceCatalogRepository catalogRepository;
    @Mock private ReferenceCatalogEntryRepository entryRepository;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks private ReferenceCatalogServiceImpl service;

    private UUID catalogId;
    private ReferenceCatalog catalog;

    @BeforeEach
    void setUp() {
        catalogId = UUID.randomUUID();

        catalog = ReferenceCatalog.builder()
                .code("DEPT_TYPES")
                .name("Department Types")
                .description("List of department types")
                .status(ReferenceCatalogStatus.DRAFT)
                .entryCount(0)
                .build();
        catalog.setId(catalogId);
    }

    // ---- listCatalogs ----

    @Test
    void listCatalogs_success() {
        when(catalogRepository.findAll(any(Sort.class))).thenReturn(List.of(catalog));

        List<ReferenceCatalogResponseDTO> result = service.listCatalogs();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getCode()).isEqualTo("DEPT_TYPES");
    }

    @Test
    void listCatalogs_empty() {
        when(catalogRepository.findAll(any(Sort.class))).thenReturn(List.of());

        List<ReferenceCatalogResponseDTO> result = service.listCatalogs();

        assertThat(result).isEmpty();
    }

    // ---- createCatalog ----

    @Test
    void createCatalog_success() {
        CreateReferenceCatalogRequestDTO req = new CreateReferenceCatalogRequestDTO();
        req.setCode("new_catalog");
        req.setName("New Catalog");
        req.setDescription("A new catalog");

        when(catalogRepository.findByCodeIgnoreCase("NEW_CATALOG")).thenReturn(Optional.empty());
        when(catalogRepository.save(any(ReferenceCatalog.class))).thenAnswer(inv -> {
            ReferenceCatalog saved = inv.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });

        ReferenceCatalogResponseDTO result = service.createCatalog(req);

        assertThat(result).isNotNull();
        assertThat(result.getCode()).isEqualTo("NEW_CATALOG");
        assertThat(result.getStatus()).isEqualTo(ReferenceCatalogStatus.DRAFT);
    }

    @Test
    void createCatalog_duplicateCode_throws() {
        CreateReferenceCatalogRequestDTO req = new CreateReferenceCatalogRequestDTO();
        req.setCode("DEPT_TYPES");
        req.setName("Department Types");

        when(catalogRepository.findByCodeIgnoreCase("DEPT_TYPES")).thenReturn(Optional.of(catalog));

        assertThatThrownBy(() -> service.createCatalog(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void createCatalog_blankCode_throws() {
        CreateReferenceCatalogRequestDTO req = new CreateReferenceCatalogRequestDTO();
        req.setCode("   ");
        req.setName("Some Name");

        assertThatThrownBy(() -> service.createCatalog(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("cannot be blank");
    }

    @Test
    void createCatalog_nullDescription_success() {
        CreateReferenceCatalogRequestDTO req = new CreateReferenceCatalogRequestDTO();
        req.setCode("TEST_CAT");
        req.setName("Test");
        req.setDescription(null);

        when(catalogRepository.findByCodeIgnoreCase("TEST_CAT")).thenReturn(Optional.empty());
        when(catalogRepository.save(any(ReferenceCatalog.class))).thenAnswer(inv -> {
            ReferenceCatalog saved = inv.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });

        ReferenceCatalogResponseDTO result = service.createCatalog(req);
        assertThat(result).isNotNull();
        assertThat(result.getDescription()).isNull();
    }

    // ---- importCatalog ----

    @Test
    void importCatalog_success() {
        String csv = "code,label,description,active\nABC,Abc Label,Some desc,true\nDEF,Def Label,,false\n";
        MockMultipartFile file = new MockMultipartFile("file", "catalog.csv",
                "text/csv", csv.getBytes(StandardCharsets.UTF_8));

        when(catalogRepository.findById(catalogId)).thenReturn(Optional.of(catalog));
        when(entryRepository.findByCatalogIdAndCodeIgnoreCase(eq(catalogId), anyString()))
                .thenReturn(Optional.empty());
        when(entryRepository.save(any(ReferenceCatalogEntry.class))).thenAnswer(inv -> inv.getArgument(0));
        when(entryRepository.countByCatalogId(catalogId)).thenReturn(2L);
        when(catalogRepository.save(any(ReferenceCatalog.class))).thenAnswer(inv -> inv.getArgument(0));

        CatalogImportResponseDTO result = service.importCatalog(catalogId, file);

        assertThat(result).isNotNull();
        assertThat(result.getProcessed()).isEqualTo(2);
        assertThat(result.getCreated()).isEqualTo(2);
        assertThat(result.getUpdated()).isZero();
        assertThat(result.getSkipped()).isZero();
    }

    @Test
    void importCatalog_updateExisting() {
        String csv = "code,label,description,active\nABC,Updated Label,New desc,true\n";
        MockMultipartFile file = new MockMultipartFile("file", "catalog.csv",
                "text/csv", csv.getBytes(StandardCharsets.UTF_8));

        ReferenceCatalogEntry existing = ReferenceCatalogEntry.builder()
                .catalog(catalog)
                .code("ABC")
                .label("Old Label")
                .description("Old desc")
                .active(true)
                .build();
        existing.setId(UUID.randomUUID());

        when(catalogRepository.findById(catalogId)).thenReturn(Optional.of(catalog));
        when(entryRepository.findByCatalogIdAndCodeIgnoreCase(catalogId, "ABC"))
                .thenReturn(Optional.of(existing));
        when(entryRepository.save(any(ReferenceCatalogEntry.class))).thenAnswer(inv -> inv.getArgument(0));
        when(entryRepository.countByCatalogId(catalogId)).thenReturn(1L);
        when(catalogRepository.save(any(ReferenceCatalog.class))).thenAnswer(inv -> inv.getArgument(0));

        CatalogImportResponseDTO result = service.importCatalog(catalogId, file);

        assertThat(result.getUpdated()).isEqualTo(1);
        assertThat(result.getCreated()).isZero();
    }

    @Test
    void importCatalog_skipEmptyCode() {
        String csv = "code,label,description,active\n,Empty,,true\nABC,Valid,desc,true\n";
        MockMultipartFile file = new MockMultipartFile("file", "catalog.csv",
                "text/csv", csv.getBytes(StandardCharsets.UTF_8));

        when(catalogRepository.findById(catalogId)).thenReturn(Optional.of(catalog));
        when(entryRepository.findByCatalogIdAndCodeIgnoreCase(eq(catalogId), anyString()))
                .thenReturn(Optional.empty());
        when(entryRepository.save(any(ReferenceCatalogEntry.class))).thenAnswer(inv -> inv.getArgument(0));
        when(entryRepository.countByCatalogId(catalogId)).thenReturn(1L);
        when(catalogRepository.save(any(ReferenceCatalog.class))).thenAnswer(inv -> inv.getArgument(0));

        CatalogImportResponseDTO result = service.importCatalog(catalogId, file);

        assertThat(result.getSkipped()).isEqualTo(1);
        assertThat(result.getCreated()).isEqualTo(1);
    }

    @Test
    void importCatalog_nullFile_throws() {
        assertThatThrownBy(() -> service.importCatalog(catalogId, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("CSV file is required");
    }

    @Test
    void importCatalog_emptyFile_throws() {
        MockMultipartFile file = new MockMultipartFile("file", "empty.csv",
                "text/csv", new byte[0]);

        assertThatThrownBy(() -> service.importCatalog(catalogId, file))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("CSV file is required");
    }

    @Test
    void importCatalog_catalogNotFound_throws() {
        MockMultipartFile file = new MockMultipartFile("file", "catalog.csv",
                "text/csv", "code,label\nABC,Test\n".getBytes(StandardCharsets.UTF_8));

        when(catalogRepository.findById(catalogId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.importCatalog(catalogId, file))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Catalog not found");
    }

    @Test
    void importCatalog_noChanges_skips() {
        String csv = "code,label,description,active\nABC,Same Label,Same desc,true\n";
        MockMultipartFile file = new MockMultipartFile("file", "catalog.csv",
                "text/csv", csv.getBytes(StandardCharsets.UTF_8));

        ReferenceCatalogEntry existing = ReferenceCatalogEntry.builder()
                .catalog(catalog)
                .code("ABC")
                .label("Same Label")
                .description("Same desc")
                .active(true)
                .build();
        existing.setId(UUID.randomUUID());

        when(catalogRepository.findById(catalogId)).thenReturn(Optional.of(catalog));
        when(entryRepository.findByCatalogIdAndCodeIgnoreCase(catalogId, "ABC"))
                .thenReturn(Optional.of(existing));
        when(entryRepository.countByCatalogId(catalogId)).thenReturn(1L);
        when(catalogRepository.save(any(ReferenceCatalog.class))).thenAnswer(inv -> inv.getArgument(0));

        CatalogImportResponseDTO result = service.importCatalog(catalogId, file);

        assertThat(result.getSkipped()).isEqualTo(1);
        assertThat(result.getUpdated()).isZero();
    }

    @Test
    void importCatalog_withMetadata() {
        String csv = "code,label,description,metadata,active\nABC,Label,Desc,\"{\"\"key\"\":\"\"val\"\"}\",true\n";
        MockMultipartFile file = new MockMultipartFile("file", "catalog.csv",
                "text/csv", csv.getBytes(StandardCharsets.UTF_8));

        when(catalogRepository.findById(catalogId)).thenReturn(Optional.of(catalog));
        when(entryRepository.findByCatalogIdAndCodeIgnoreCase(eq(catalogId), anyString()))
                .thenReturn(Optional.empty());
        when(entryRepository.save(any(ReferenceCatalogEntry.class))).thenAnswer(inv -> inv.getArgument(0));
        when(entryRepository.countByCatalogId(catalogId)).thenReturn(1L);
        when(catalogRepository.save(any(ReferenceCatalog.class))).thenAnswer(inv -> inv.getArgument(0));

        CatalogImportResponseDTO result = service.importCatalog(catalogId, file);
        assertThat(result.getCreated()).isEqualTo(1);
    }

    @Test
    void importCatalog_noLabelUsesCodeAsLabel() {
        String csv = "code,description,active\nmy_test_code,Some desc,true\n";
        MockMultipartFile file = new MockMultipartFile("file", "catalog.csv",
                "text/csv", csv.getBytes(StandardCharsets.UTF_8));

        when(catalogRepository.findById(catalogId)).thenReturn(Optional.of(catalog));
        when(entryRepository.findByCatalogIdAndCodeIgnoreCase(eq(catalogId), anyString()))
                .thenReturn(Optional.empty());
        when(entryRepository.save(any(ReferenceCatalogEntry.class))).thenAnswer(inv -> inv.getArgument(0));
        when(entryRepository.countByCatalogId(catalogId)).thenReturn(1L);
        when(catalogRepository.save(any(ReferenceCatalog.class))).thenAnswer(inv -> inv.getArgument(0));

        CatalogImportResponseDTO result = service.importCatalog(catalogId, file);
        assertThat(result.getCreated()).isEqualTo(1);
    }

    // ---- schedulePublish ----

    @Test
    void schedulePublish_futureDate_schedulesPublish() {
        SchedulePublishRequestDTO req = new SchedulePublishRequestDTO();
        req.setPublishAt(LocalDateTime.now().plusDays(7));

        when(catalogRepository.findById(catalogId)).thenReturn(Optional.of(catalog));
        when(catalogRepository.save(any(ReferenceCatalog.class))).thenAnswer(inv -> inv.getArgument(0));

        ReferenceCatalogResponseDTO result = service.schedulePublish(catalogId, req);

        assertThat(result.getStatus()).isEqualTo(ReferenceCatalogStatus.SCHEDULED);
        assertThat(result.getScheduledPublishAt()).isNotNull();
    }

    @Test
    void schedulePublish_pastDate_immediatelyPublishes() {
        SchedulePublishRequestDTO req = new SchedulePublishRequestDTO();
        req.setPublishAt(LocalDateTime.now().minusDays(1));

        when(catalogRepository.findById(catalogId)).thenReturn(Optional.of(catalog));
        when(catalogRepository.save(any(ReferenceCatalog.class))).thenAnswer(inv -> inv.getArgument(0));

        ReferenceCatalogResponseDTO result = service.schedulePublish(catalogId, req);

        assertThat(result.getStatus()).isEqualTo(ReferenceCatalogStatus.ACTIVE);
        assertThat(result.getPublishedAt()).isNotNull();
    }

    @Test
    void schedulePublish_catalogNotFound_throws() {
        SchedulePublishRequestDTO req = new SchedulePublishRequestDTO();
        req.setPublishAt(LocalDateTime.now().plusDays(1));

        when(catalogRepository.findById(catalogId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.schedulePublish(catalogId, req))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Catalog not found");
    }

    // ---- code normalization edge cases ----

    @Test
    void createCatalog_specialCharsInCode_normalized() {
        CreateReferenceCatalogRequestDTO req = new CreateReferenceCatalogRequestDTO();
        req.setCode("my--special...code");
        req.setName("Special");

        when(catalogRepository.findByCodeIgnoreCase("MY_SPECIAL_CODE")).thenReturn(Optional.empty());
        when(catalogRepository.save(any(ReferenceCatalog.class))).thenAnswer(inv -> {
            ReferenceCatalog saved = inv.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });

        ReferenceCatalogResponseDTO result = service.createCatalog(req);
        assertThat(result.getCode()).isEqualTo("MY_SPECIAL_CODE");
    }
}
