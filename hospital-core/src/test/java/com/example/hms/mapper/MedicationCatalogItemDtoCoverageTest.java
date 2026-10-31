package com.example.hms.mapper;

import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.payload.dto.medication.MedicationCatalogItemRequestDTO;
import com.example.hms.payload.dto.medication.MedicationCatalogItemResponseDTO;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D935 guard: every catalogue column a clinical rule reads can be written
 * and read through the catalogue API. pediatric_max_dose_mg_per_kg was on the
 * entity (V63) and read by PediatricDoseRule, but on no DTO, so no hospital
 * could ever enter a ceiling and the hard check never fired. A new column
 * fails here until it is on both DTOs or listed below with its reason.
 */
class MedicationCatalogItemDtoCoverageTest {

    /** Entity fields deliberately not on the DTOs, with the reason. */
    private static final Map<String, String> NOT_EXPOSED = Map.of(
        "hospital", "carried as hospitalId / hospitalName",
        "code", "the formulary code set by imports and HL7 (D654); a UI-created item has none");

    private static Set<String> fields(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields())
            .filter(f -> !Modifier.isStatic(f.getModifiers()))
            .map(Field::getName)
            .collect(Collectors.toSet());
    }

    @Test
    void everyCatalogColumnIsOnTheRequestAndTheResponse() {
        Set<String> entity = fields(MedicationCatalogItem.class);
        entity.removeAll(NOT_EXPOSED.keySet());
        assertThat(fields(MedicationCatalogItemRequestDTO.class)).containsAll(entity);
        assertThat(fields(MedicationCatalogItemResponseDTO.class)).containsAll(entity);
    }

    @Test
    void thePaediatricCeilingRoundTripsThroughTheMapper() {
        MedicationCatalogItemMapper mapper = new MedicationCatalogItemMapper();
        MedicationCatalogItem item = mapper.toEntity(MedicationCatalogItemRequestDTO.builder()
            .nameFr("Paracétamol sirop").genericName("paracetamol")
            .pediatricMaxDoseMgPerKg(new BigDecimal("15.000")).build());
        assertThat(item.getPediatricMaxDoseMgPerKg()).isEqualByComparingTo("15");
        assertThat(mapper.toResponseDTO(item).getPediatricMaxDoseMgPerKg()).isEqualByComparingTo("15");
    }
}
