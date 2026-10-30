package com.example.hms.mapper;

import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.payload.dto.medication.MedicationCatalogItemRequestDTO;
import com.example.hms.payload.dto.medication.MedicationCatalogItemResponseDTO;
import org.springframework.stereotype.Component;

@Component
public class MedicationCatalogItemMapper {

    public MedicationCatalogItemResponseDTO toResponseDTO(MedicationCatalogItem entity) {
        if (entity == null) return null;
        return MedicationCatalogItemResponseDTO.builder()
                .id(entity.getId())
                .nameFr(entity.getNameFr())
                .genericName(entity.getGenericName())
                .brandName(entity.getBrandName())
                .atcCode(entity.getAtcCode())
                .form(entity.getForm())
                .strength(entity.getStrength())
                .strengthUnit(entity.getStrengthUnit())
                .rxnormCode(entity.getRxnormCode())
                .tallManName(entity.getTallManName())
                .route(entity.getRoute())
                .category(entity.getCategory())
                .essentialList(entity.isEssentialList())
                .controlled(entity.isControlled())
                .pediatricMaxDoseMgPerKg(entity.getPediatricMaxDoseMgPerKg())
                .pediatricMaxDailyMgPerKg(entity.getPediatricMaxDailyMgPerKg())
                .pediatricMaxSingleDoseMg(entity.getPediatricMaxSingleDoseMg())
                .pediatricMaxDailyMg(entity.getPediatricMaxDailyMg())
                .pediatricManualReviewUnderMonths(entity.getPediatricManualReviewUnderMonths())
                .pediatricWeightBands(toBandDtos(entity.getPediatricWeightBands()))
                .pediatricDoseSource(entity.getPediatricDoseSource())
                .pediatricDoseStatus(entity.getPediatricDoseStatus())
                .pediatricDoseReviewedBy(entity.getPediatricDoseReviewedBy())
                .pediatricDoseReviewedAt(entity.getPediatricDoseReviewedAt())
                .active(entity.isActive())
                .description(entity.getDescription())
                .hospitalId(entity.getHospital() != null ? entity.getHospital().getId() : null)
                .hospitalName(entity.getHospital() != null ? entity.getHospital().getName() : null)
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    public MedicationCatalogItem toEntity(MedicationCatalogItemRequestDTO dto) {
        if (dto == null) return null;
        return MedicationCatalogItem.builder()
                .nameFr(dto.getNameFr())
                .genericName(dto.getGenericName())
                .brandName(dto.getBrandName())
                .atcCode(dto.getAtcCode())
                .form(dto.getForm())
                .strength(dto.getStrength())
                .strengthUnit(dto.getStrengthUnit())
                .rxnormCode(dto.getRxnormCode())
                .tallManName(dto.getTallManName())
                .route(dto.getRoute())
                .category(dto.getCategory())
                .essentialList(dto.isEssentialList())
                .controlled(dto.isControlled())
                .pediatricMaxDoseMgPerKg(dto.getPediatricMaxDoseMgPerKg())
                .active(dto.isActive())
                .description(dto.getDescription())
                .build();
    }

    /** Round 18 (QA D935): the bands as the API shows them. */
    public static java.util.List<com.example.hms.payload.dto.medication.PediatricWeightBandDTO> toBandDtos(
            java.util.List<com.example.hms.model.medication.PediatricWeightBand> bands) {
        if (bands == null) {
            return java.util.List.of();
        }
        return bands.stream()
            .filter(java.util.Objects::nonNull)
            .map(b -> com.example.hms.payload.dto.medication.PediatricWeightBandDTO.builder()
                .minWeightKg(b.getMinWeightKg())
                .maxWeightKg(b.getMaxWeightKg())
                .dosePerAdministration(b.getDosePerAdministration())
                .doseUnit(b.getDoseUnit())
                .maxDosesPerDay(b.getMaxDosesPerDay())
                .build())
            .toList();
    }

    /** Round 18 (QA D935): the bands as stored, in weight order. */
    public static java.util.List<com.example.hms.model.medication.PediatricWeightBand> toBands(
            java.util.List<com.example.hms.payload.dto.medication.PediatricWeightBandDTO> dtos) {
        java.util.List<com.example.hms.model.medication.PediatricWeightBand> out = new java.util.ArrayList<>();
        if (dtos == null) {
            return out;
        }
        for (com.example.hms.payload.dto.medication.PediatricWeightBandDTO d : dtos) {
            if (d == null) {
                continue;
            }
            out.add(com.example.hms.model.medication.PediatricWeightBand.builder()
                .minWeightKg(d.getMinWeightKg())
                .maxWeightKg(d.getMaxWeightKg())
                .dosePerAdministration(d.getDosePerAdministration())
                .doseUnit(d.getDoseUnit() == null ? null : d.getDoseUnit().trim())
                .maxDosesPerDay(d.getMaxDosesPerDay())
                .build());
        }
        out.sort(java.util.Comparator.comparing(com.example.hms.model.medication.PediatricWeightBand::getMinWeightKg,
            java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())));
        return out;
    }
}
