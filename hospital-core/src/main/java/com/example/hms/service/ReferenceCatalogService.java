package com.example.hms.service;

import com.example.hms.payload.dto.reference.CatalogImportResponseDTO;
import com.example.hms.payload.dto.reference.CreateReferenceCatalogRequestDTO;
import com.example.hms.payload.dto.reference.ReferenceCatalogResponseDTO;
import com.example.hms.payload.dto.reference.SchedulePublishRequestDTO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

public interface ReferenceCatalogService {

    List<ReferenceCatalogResponseDTO> listCatalogs();

    ReferenceCatalogResponseDTO createCatalog(CreateReferenceCatalogRequestDTO requestDTO);

    CatalogImportResponseDTO importCatalog(UUID catalogId, MultipartFile file);

    ReferenceCatalogResponseDTO schedulePublish(UUID catalogId, SchedulePublishRequestDTO requestDTO);
}
