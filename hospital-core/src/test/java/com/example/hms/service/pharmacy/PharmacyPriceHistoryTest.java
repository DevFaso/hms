package com.example.hms.service.pharmacy;

import com.example.hms.model.Hospital;
import com.example.hms.model.pharmacy.InventoryItem;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.model.pharmacy.PharmacyPriceChange;
import com.example.hms.model.pharmacy.StockTransaction;
import com.example.hms.repository.pharmacy.PharmacyPriceChangeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Review of #1239: the price history of a pharmacy item, and what a return gives back across a pack conversion. */
@ExtendWith(MockitoExtension.class)
@DisplayName("Pharmacy price history and refund amounts (review of #1239)")
class PharmacyPriceHistoryTest {

    @Mock private PharmacyPriceChangeRepository repository;

    private PharmacyPriceHistory history;
    private InventoryItem item;
    private final UUID hospitalId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private static final LocalDateTime NOON = LocalDateTime.of(2026, 11, 20, 12, 0);

    @BeforeEach
    void setUp() {
        history = new PharmacyPriceHistory(repository);
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        Pharmacy pharmacy = Pharmacy.builder().hospital(hospital).name("Pharmacie").build();
        pharmacy.setId(UUID.randomUUID());
        item = InventoryItem.builder().pharmacy(pharmacy).unitPrice(new BigDecimal("300")).build();
        item.setId(UUID.randomUUID());
    }

    private PharmacyPriceChange change(String oldPrice, String newPrice) {
        return PharmacyPriceChange.builder().inventoryItemId(item.getId())
                .oldPrice(oldPrice == null ? null : new BigDecimal(oldPrice))
                .newPrice(newPrice == null ? null : new BigDecimal(newPrice)).changedAt(NOON).reason("r").build();
    }

    @Test
    @DisplayName("a change is kept with its hospital, pharmacy, item, prices, reason (500 characters at most), author and time")
    void keepsAChange() {
        when(repository.save(any(PharmacyPriceChange.class))).thenAnswer(inv -> inv.getArgument(0));

        history.keep(item, new BigDecimal("250"), new BigDecimal("300"), "x".repeat(600), userId, NOON);

        ArgumentCaptor<PharmacyPriceChange> saved = ArgumentCaptor.forClass(PharmacyPriceChange.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getHospitalId()).isEqualTo(hospitalId);
        assertThat(saved.getValue().getPharmacyId()).isEqualTo(item.getPharmacy().getId());
        assertThat(saved.getValue().getInventoryItemId()).isEqualTo(item.getId());
        assertThat(saved.getValue().getOldPrice()).isEqualByComparingTo("250");
        assertThat(saved.getValue().getNewPrice()).isEqualByComparingTo("300");
        assertThat(saved.getValue().getReason()).hasSize(500);
        assertThat(saved.getValue().getChangedBy()).isEqualTo(userId);
        assertThat(saved.getValue().getChangedAt()).isEqualTo(NOON);
    }

    @Test
    @DisplayName("the price in force: the last change by then, else the old price of the next one, else today's")
    void priceInForce() {
        // No history at all (an item priced before V311): the price it has now.
        assertThat(history.priceAt(item, NOON)).contains(new BigDecimal("300"));
        assertThat(history.priceAt(item, null)).contains(new BigDecimal("300"));
        assertThat(history.priceAt(null, NOON)).isEmpty();

        // Changed 250 -> 300 at noon: 250 before, 300 from then on.
        when(repository.findFirstByInventoryItemIdAndChangedAtGreaterThanOrderByChangedAtAscCreatedAtAsc(
                item.getId(), NOON.minusHours(1))).thenReturn(Optional.of(change("250", "300")));
        assertThat(history.priceAt(item, NOON.minusHours(1))).contains(new BigDecimal("250"));
        when(repository.findFirstByInventoryItemIdAndChangedAtLessThanEqualOrderByChangedAtDescCreatedAtDesc(
                item.getId(), NOON.plusHours(1))).thenReturn(Optional.of(change("250", "300")));
        assertThat(history.priceAt(item, NOON.plusHours(1))).contains(new BigDecimal("300"));

        // Not priced before its first price, and not priced once the price is removed.
        when(repository.findFirstByInventoryItemIdAndChangedAtGreaterThanOrderByChangedAtAscCreatedAtAsc(
                item.getId(), NOON.minusDays(1))).thenReturn(Optional.of(change(null, "250")));
        assertThat(history.priceAt(item, NOON.minusDays(1))).isEmpty();
        when(repository.findFirstByInventoryItemIdAndChangedAtLessThanEqualOrderByChangedAtDescCreatedAtDesc(
                item.getId(), NOON.plusDays(1))).thenReturn(Optional.of(change("300", "0")));
        assertThat(history.priceAt(item, NOON.plusDays(1))).isEmpty();
    }

    @Test
    @DisplayName("no price and zero are the same price; the history is read oldest first")
    void samePriceAndHistory() {
        assertThat(PharmacyPriceHistory.samePrice(null, BigDecimal.ZERO)).isTrue();
        assertThat(PharmacyPriceHistory.samePrice(new BigDecimal("250"), new BigDecimal("250.00"))).isTrue();
        assertThat(PharmacyPriceHistory.samePrice(new BigDecimal("250"), new BigDecimal("300"))).isFalse();
        assertThat(PharmacyPriceHistory.samePrice(null, new BigDecimal("1"))).isFalse();

        List<PharmacyPriceChange> rows = List.of(change(null, "250"), change("250", "300"));
        when(repository.findByInventoryItemIdOrderByChangedAtAscCreatedAtAsc(item.getId())).thenReturn(rows);
        assertThat(history.historyOf(item)).isSameAs(rows);
    }

    private StockTransaction returned(String quantity, LocalDateTime at) {
        StockTransaction ret = StockTransaction.builder().inventoryItem(item).quantity(new BigDecimal(quantity)).build();
        ret.setCreatedAt(at);
        return ret;
    }

    @Test
    @DisplayName("a return is priced in the units its sale or fill was counted in; nothing paid gives nothing back")
    void refundAmounts() {
        // No pack conversion: as recorded.
        assertThat(PharmacyRefundAmount.ofSaleLine(new BigDecimal("250"), returned("4", NOON), NOON.minusDays(1)))
                .isEqualByComparingTo("1000");
        assertThat(PharmacyRefundAmount.ofDispense(new BigDecimal("2000"), BigDecimal.TEN, returned("4", NOON),
                NOON.minusDays(1))).isEqualByComparingTo("800");

        // 1 box = 10 tablets since noon. A fill of 2 boxes paid 1 000 before it; 5 tablets returned after it.
        item.setUnitsPerPack(10);
        item.setPackSizeSetAt(NOON);
        assertThat(PharmacyRefundAmount.ofDispense(new BigDecimal("1000"), new BigDecimal("2"),
                returned("5", NOON.plusHours(1)), NOON.minusHours(1))).as("5 tablets = half a box of the 2 paid 1 000")
                .isEqualByComparingTo("250");
        // Both before, or both after, the conversion: the same unit on each side.
        assertThat(PharmacyRefundAmount.ofDispense(new BigDecimal("1000"), new BigDecimal("2"),
                returned("1", NOON.minusMinutes(1)), NOON.minusHours(1))).isEqualByComparingTo("500");
        assertThat(PharmacyRefundAmount.ofDispense(new BigDecimal("1000"), new BigDecimal("20"),
                returned("5", NOON.plusHours(2)), NOON.plusHours(1))).isEqualByComparingTo("250");
        // Unknown dates (rows older than the timestamps): as recorded.
        assertThat(PharmacyRefundAmount.inSourceUnits(returned("5", null), NOON.minusHours(1)))
                .isEqualByComparingTo("5");
        assertThat(PharmacyRefundAmount.inSourceUnits(returned("5", NOON.plusHours(1)), null))
                .isEqualByComparingTo("5");

        assertThat(PharmacyRefundAmount.ofDispense(BigDecimal.ZERO, BigDecimal.TEN, returned("4", NOON), NOON))
                .isEqualByComparingTo("0");
        assertThat(PharmacyRefundAmount.ofDispense(new BigDecimal("500"), BigDecimal.ZERO, returned("4", NOON), NOON))
                .isEqualByComparingTo("0");
        assertThat(PharmacyRefundAmount.ofSaleLine(null, returned("4", NOON), NOON)).isEqualByComparingTo("0");
    }
}
