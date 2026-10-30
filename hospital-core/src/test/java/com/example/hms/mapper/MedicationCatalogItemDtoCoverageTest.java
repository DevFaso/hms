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

    /**
     * Round 18 (QA D935): columns the server stamps from the caller, readable
     * on the response but written by no request.
     */
    private static final Map<String, String> SERVER_SET = Map.of(
        "pediatricDoseReviewedBy", "the pharmacist who set or approved the limits (the caller)",
        "pediatricDoseReviewedAt", "when they did (the server clock)");

    /**
     * Round 18 (QA D935): a column is writable through the general catalogue
     * request OR the pharmacy's paediatric-dosing request
     * (PUT /medication-catalog/{id}/pediatric-dosing), and readable on the
     * response. Changed from "on the general request": the paediatric limits
     * moved to the pharmacy's own audited write path.
     */
    @Test
    void everyCatalogColumnIsOnTheRequestAndTheResponse() {
        Set<String> entity = fields(MedicationCatalogItem.class);
        entity.removeAll(NOT_EXPOSED.keySet());
        assertThat(fields(MedicationCatalogItemResponseDTO.class)).containsAll(entity);
        Set<String> writable = new java.util.HashSet<>(fields(MedicationCatalogItemRequestDTO.class));
        writable.addAll(fields(com.example.hms.payload.dto.medication.PediatricDosingRequestDTO.class));
        entity.removeAll(SERVER_SET.keySet());
        assertThat(writable).containsAll(entity);
    }

    /**
     * Round 18 (QA D935): every paediatric limit the dose rule reads is one the
     * pharmacy can write: the round-17 QA found the pharmacist could edit none.
     */
    @Test
    void everyPaediatricColumnIsOnThePharmacyDosingRequest() {
        Set<String> paediatric = fields(MedicationCatalogItem.class).stream()
            .filter(f -> f.startsWith("pediatric"))
            .filter(f -> !SERVER_SET.containsKey(f))
            .collect(Collectors.toSet());
        assertThat(paediatric).hasSizeGreaterThanOrEqualTo(8);
        assertThat(fields(com.example.hms.payload.dto.medication.PediatricDosingRequestDTO.class))
            .containsAll(paediatric);
    }

    /**
     * Round 18 (changed): the general create no longer carries the paediatric
     * ceiling (the pharmacy's audited dosing endpoint is its only writer); the
     * response still shows what the item holds.
     */
    @Test
    void thePaediatricCeilingIsReadOnTheResponseAndWrittenOnlyByThePharmacy() {
        MedicationCatalogItemMapper mapper = new MedicationCatalogItemMapper();
        assertThat(fields(MedicationCatalogItemRequestDTO.class)).doesNotContain("pediatricMaxDoseMgPerKg");
        MedicationCatalogItem item = mapper.toEntity(MedicationCatalogItemRequestDTO.builder()
            .nameFr("Paracétamol sirop").genericName("paracetamol").build());
        assertThat(item.getPediatricMaxDoseMgPerKg()).isNull();
        item.setPediatricMaxDoseMgPerKg(new BigDecimal("15.000"));
        assertThat(mapper.toResponseDTO(item).getPediatricMaxDoseMgPerKg()).isEqualByComparingTo("15");
    }
}
