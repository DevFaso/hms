package com.example.hms.service.orderset;

import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.repository.MedicationCatalogItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** QA D1396 (round 33): the quantity of an order-set item, against this hospital's catalogue. */
class OrderSetItemQuantityRulesTest {

    private static final UUID HOSPITAL = UUID.randomUUID();
    private static final UUID MORPHINE_ID = UUID.randomUUID();

    private final MedicationCatalogItemRepository catalog = mock(MedicationCatalogItemRepository.class);
    private final OrderSetItemQuantityRules rules = new OrderSetItemQuantityRules(catalog);

    @BeforeEach
    void catalogue() {
        MedicationCatalogItem morphine = new MedicationCatalogItem();
        morphine.setId(MORPHINE_ID);
        morphine.setNameFr("Morphine");
        morphine.setCode("MOR-1");
        morphine.setControlled(true);
        MedicationCatalogItem paracetamol = new MedicationCatalogItem();
        paracetamol.setId(UUID.randomUUID());
        paracetamol.setNameFr("Paracetamol");
        paracetamol.setControlled(false);
        when(catalog.findByHospital_IdAndActiveTrue(eq(HOSPITAL), any()))
            .thenReturn(new PageImpl<>(List.of(morphine, paracetamol)));
    }

    private void assertRefused(Map<String, Object> item, String field) {
        List<Map<String, Object>> items = List.of(Map.of("orderType", "LAB", "orderName", "Lactate"), item);
        assertThatThrownBy(() -> rules.requireValid(items, HOSPITAL))
            .isInstanceOfSatisfying(FieldBusinessException.class, ex -> assertThat(ex.getField()).isEqualTo(field));
    }

    @Test
    void aControlledDrugIsRecognisedByNameCodeAndPick() {
        String field = "orderItems[1].quantity";
        assertRefused(Map.of("orderType", "MEDICATION", "medicationName", "Morphine"), field);
        assertRefused(Map.of("orderType", "medication", "medicationName", "morphine 2 mg"), field);
        assertRefused(Map.of("orderType", "MEDICATION", "medicationName", "X", "medicationCode", "mor-1"), field);
        assertRefused(Map.of("orderType", "MEDICATION", "medicationName", "X",
            "medicationCatalogItemId", MORPHINE_ID.toString()), field);
    }

    @Test
    void aControlledDrugWithItsQuantityPasses() {
        rules.requireValid(List.of(Map.of("orderType", "MEDICATION", "medicationName", "Morphine", "quantity", 6)),
            HOSPITAL);
        rules.requireValid(List.of(Map.of("orderType", "MEDICATION", "medicationName", "Morphine", "quantity", "6.5",
            "quantityUnit", "ampoules")), HOSPITAL);
    }

    @Test
    void aWrittenQuantityMustBeOneTheOrderCanCarry() {
        String field = "orderItems[1].quantity";
        for (Object bad : List.of(0, -1, "six", 100001, "1.234")) {
            assertRefused(Map.of("orderType", "MEDICATION", "medicationName", "Paracetamol", "quantity", bad), field);
        }
    }

    @Test
    void anythingElseIsNotAsked() {
        rules.requireValid(null, HOSPITAL);
        rules.requireValid(List.of(), HOSPITAL);
        rules.requireValid(List.of(Map.of("orderType", "MEDICATION", "medicationName", "Paracetamol"),
            Map.of("orderType", "MEDICATION", "medicationName", "Unknown drug", "quantity", " "),
            Map.of("orderType", "LAB", "orderName", "Morphine level")), HOSPITAL);
        rules.requireValid(List.of(Map.of("orderType", "LAB", "orderName", "Lactate")), UUID.randomUUID());
        verify(catalog, never()).findByHospital_IdAndActiveTrue(eq(HOSPITAL), eq(null));
    }

    @Test
    void theQuantityIsReadWhateverJsonGaveIt() {
        assertThat(OrderSetItemQuantityRules.quantityOf(Map.of("quantity", 6))).isEqualByComparingTo(BigDecimal.valueOf(6));
        assertThat(OrderSetItemQuantityRules.quantityOf(Map.of("quantity", 6.5d))).isEqualByComparingTo("6.5");
        assertThat(OrderSetItemQuantityRules.quantityOf(Map.of("quantity", " 12 "))).isEqualByComparingTo("12");
        assertThat(OrderSetItemQuantityRules.quantityOf(Map.of())).isNull();
        assertThat(OrderSetItemQuantityRules.quantityOf(Map.of("quantity", true))).isNull();
    }
}
