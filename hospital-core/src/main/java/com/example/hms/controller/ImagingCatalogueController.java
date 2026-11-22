package com.example.hms.controller;

import com.example.hms.payload.dto.imaging.ImagingCatalogueExamDTO;
import com.example.hms.payload.dto.imaging.ImagingCatalogueExamUpdateRequestDTO;
import com.example.hms.security.audit.WriteAudited;
import com.example.hms.service.imaging.ImagingCatalogueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * QA D3450 / D3254 (r70, V361): the acting hospital's imaging catalogue, at {@code /imaging/catalogue}.
 *
 * <p>The gate lets a role in; what the caller is at the acting hospital is asked again by
 * {@link ImagingCatalogueService} (QA D2915: a token names a role wherever it is held). Read by whoever reads the
 * exams to order one or to decide about one (the ordering clinicians, the radiologist, the hospital admin); an
 * exam's pregnancy flag and body region are set by a hospital admin or a radiologist of the hospital, in a role
 * of their own ({@code ClinicalActor.holdsOwnRole}), never by the platform's super-admin alone.
 */
@RestController
@RequestMapping("/imaging/catalogue")
@Tag(name = "Imaging catalogue", description = "The exams an imaging order is picked from")
@RequiredArgsConstructor
public class ImagingCatalogueController {

    private final ImagingCatalogueService catalogueService;

    @GetMapping
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','DOCTOR','NURSE','MIDWIFE','RADIOLOGIST')")
    @Operation(summary = "The imaging exams of the acting hospital, by name")
    public List<ImagingCatalogueExamDTO> list() {
        return catalogueService.list();
    }

    @PutMapping("/{itemId}")
    @PreAuthorize("T(com.example.hms.security.ClinicalActor).holdsOwnRole(authentication, 'HOSPITAL_ADMIN','RADIOLOGIST')")
    @WriteAudited(skip = true, reason = "service emits CONFIGURATION_CHANGED with the item id and the new flag")
    @Operation(summary = "Set whether an exam may reveal a pregnancy, and its body region")
    public ImagingCatalogueExamDTO update(@PathVariable UUID itemId,
                                          @Valid @RequestBody ImagingCatalogueExamUpdateRequestDTO request) {
        return catalogueService.update(itemId, request);
    }
}
