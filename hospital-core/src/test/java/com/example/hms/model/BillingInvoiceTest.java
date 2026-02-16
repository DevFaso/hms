package com.example.hms.model;

import com.example.hms.enums.InvoiceStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertSame;

class BillingInvoiceTest {

    // ───────────── helpers ─────────────

    private InvoiceItem buildItem(BigDecimal totalPrice) {
        InvoiceItem item = new InvoiceItem();
        item.setId(UUID.randomUUID());
        item.setTotalPrice(totalPrice);
        return item;
    }

    private InvoiceItem buildItemNull() {
        InvoiceItem item = new InvoiceItem();
        item.setId(UUID.randomUUID());
        return item; // totalPrice is null
    }

    private BillingInvoice minimalInvoice() {
        BillingInvoice inv = new BillingInvoice();
        inv.setInvoiceDate(LocalDate.of(2026, 1, 1));
        inv.setDueDate(LocalDate.of(2026, 2, 1));
        return inv;
    }

    // ═══════════════ NoArgsConstructor ═══════════════

    @Nested
    @DisplayName("NoArgsConstructor")
    class NoArgsCtor {

        @Test
        @DisplayName("creates instance with null/default fields")
        void defaults() {
            BillingInvoice inv = new BillingInvoice();
            assertAll(
                () -> assertNull(inv.getPatient()),
                () -> assertNull(inv.getHospital()),
                () -> assertNull(inv.getEncounter()),
                () -> assertNull(inv.getInvoiceNumber()),
                () -> assertNull(inv.getInvoiceDate()),
                () -> assertNull(inv.getDueDate()),
                () -> assertNull(inv.getTotalAmount()),
                () -> assertNull(inv.getAmountPaid()),
                () -> assertNull(inv.getStatus()),
                () -> assertNull(inv.getNotes()),
                () -> assertNotNull(inv.getInvoiceItems()),
                () -> assertTrue(inv.getInvoiceItems().isEmpty()),
                () -> assertNull(inv.getCreatedBy()),
                () -> assertNull(inv.getUpdatedBy()),
                () -> assertNull(inv.getId())
            );
        }
    }

    // ═══════════════ AllArgsConstructor ═══════════════

    @Nested
    @DisplayName("AllArgsConstructor")
    class AllArgsCtor {

        @Test
        @DisplayName("creates instance with all fields populated")
        void allFields() {
            Patient patient = new Patient();
            Hospital hospital = new Hospital();
            Encounter encounter = new Encounter();
            LocalDate invoiceDate = LocalDate.of(2026, 3, 1);
            LocalDate dueDate = LocalDate.of(2026, 4, 1);
            BigDecimal total = new BigDecimal("500.00");
            BigDecimal paid = new BigDecimal("200.00");
            Set<InvoiceItem> items = new HashSet<>();
            UUID createdBy = UUID.randomUUID();
            UUID updatedBy = UUID.randomUUID();

            BillingInvoice inv = new BillingInvoice(
                patient, hospital, encounter,
                "INV-001", invoiceDate, dueDate,
                total, paid, InvoiceStatus.SENT,
                "Some notes", items, createdBy, updatedBy
            );

            assertAll(
                () -> assertSame(patient, inv.getPatient()),
                () -> assertSame(hospital, inv.getHospital()),
                () -> assertSame(encounter, inv.getEncounter()),
                () -> assertEquals("INV-001", inv.getInvoiceNumber()),
                () -> assertEquals(invoiceDate, inv.getInvoiceDate()),
                () -> assertEquals(dueDate, inv.getDueDate()),
                () -> assertEquals(total, inv.getTotalAmount()),
                () -> assertEquals(paid, inv.getAmountPaid()),
                () -> assertEquals(InvoiceStatus.SENT, inv.getStatus()),
                () -> assertEquals("Some notes", inv.getNotes()),
                () -> assertSame(items, inv.getInvoiceItems()),
                () -> assertEquals(createdBy, inv.getCreatedBy()),
                () -> assertEquals(updatedBy, inv.getUpdatedBy())
            );
        }
    }

    // ═══════════════ Builder ═══════════════

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("builds with all fields")
        void builderAll() {
            Patient patient = new Patient();
            Hospital hospital = new Hospital();
            UUID cb = UUID.randomUUID();

            BillingInvoice inv = BillingInvoice.builder()
                .patient(patient)
                .hospital(hospital)
                .encounter(null)
                .invoiceNumber("INV-B1")
                .invoiceDate(LocalDate.of(2026, 5, 1))
                .dueDate(LocalDate.of(2026, 6, 1))
                .totalAmount(new BigDecimal("100.00"))
                .amountPaid(BigDecimal.ZERO)
                .status(InvoiceStatus.DRAFT)
                .notes("builder note")
                .createdBy(cb)
                .updatedBy(cb)
                .build();

            assertAll(
                () -> assertSame(patient, inv.getPatient()),
                () -> assertEquals("INV-B1", inv.getInvoiceNumber()),
                () -> assertEquals(InvoiceStatus.DRAFT, inv.getStatus()),
                () -> assertEquals("builder note", inv.getNotes()),
                () -> assertNotNull(inv.getInvoiceItems()) // @Builder.Default
            );
        }

        @Test
        @DisplayName("builder default initializes invoiceItems to empty set")
        void builderDefaultInvoiceItems() {
            BillingInvoice inv = BillingInvoice.builder().build();
            assertNotNull(inv.getInvoiceItems());
            assertTrue(inv.getInvoiceItems().isEmpty());
        }
    }

    // ═══════════════ Getters / Setters ═══════════════

    @Nested
    @DisplayName("Getters and Setters")
    class GettersSetters {

        @Test @DisplayName("patient") void patient() {
            BillingInvoice inv = new BillingInvoice();
            Patient p = new Patient();
            inv.setPatient(p);
            assertSame(p, inv.getPatient());
        }

        @Test @DisplayName("hospital") void hospital() {
            BillingInvoice inv = new BillingInvoice();
            Hospital h = new Hospital();
            inv.setHospital(h);
            assertSame(h, inv.getHospital());
        }

        @Test @DisplayName("encounter") void encounter() {
            BillingInvoice inv = new BillingInvoice();
            Encounter e = new Encounter();
            inv.setEncounter(e);
            assertSame(e, inv.getEncounter());
        }

        @Test @DisplayName("invoiceNumber") void invoiceNumber() {
            BillingInvoice inv = new BillingInvoice();
            inv.setInvoiceNumber("INV-999");
            assertEquals("INV-999", inv.getInvoiceNumber());
        }

        @Test @DisplayName("invoiceDate") void invoiceDate() {
            BillingInvoice inv = new BillingInvoice();
            LocalDate d = LocalDate.of(2026, 7, 1);
            inv.setInvoiceDate(d);
            assertEquals(d, inv.getInvoiceDate());
        }

        @Test @DisplayName("dueDate") void dueDate() {
            BillingInvoice inv = new BillingInvoice();
            LocalDate d = LocalDate.of(2026, 8, 1);
            inv.setDueDate(d);
            assertEquals(d, inv.getDueDate());
        }

        @Test @DisplayName("totalAmount") void totalAmount() {
            BillingInvoice inv = new BillingInvoice();
            inv.setTotalAmount(new BigDecimal("999.99"));
            assertEquals(new BigDecimal("999.99"), inv.getTotalAmount());
        }

        @Test @DisplayName("amountPaid") void amountPaid() {
            BillingInvoice inv = new BillingInvoice();
            inv.setAmountPaid(new BigDecimal("123.45"));
            assertEquals(new BigDecimal("123.45"), inv.getAmountPaid());
        }

        @Test @DisplayName("status") void status() {
            BillingInvoice inv = new BillingInvoice();
            inv.setStatus(InvoiceStatus.CANCELLED);
            assertEquals(InvoiceStatus.CANCELLED, inv.getStatus());
        }

        @Test @DisplayName("notes") void notes() {
            BillingInvoice inv = new BillingInvoice();
            inv.setNotes("note here");
            assertEquals("note here", inv.getNotes());
        }

        @Test @DisplayName("invoiceItems") void invoiceItems() {
            BillingInvoice inv = new BillingInvoice();
            Set<InvoiceItem> items = new HashSet<>();
            inv.setInvoiceItems(items);
            assertSame(items, inv.getInvoiceItems());
        }

        @Test @DisplayName("createdBy") void createdBy() {
            BillingInvoice inv = new BillingInvoice();
            UUID id = UUID.randomUUID();
            inv.setCreatedBy(id);
            assertEquals(id, inv.getCreatedBy());
        }

        @Test @DisplayName("updatedBy") void updatedBy() {
            BillingInvoice inv = new BillingInvoice();
            UUID id = UUID.randomUUID();
            inv.setUpdatedBy(id);
            assertEquals(id, inv.getUpdatedBy());
        }
    }

    // ═══════════════ toString ═══════════════

    @Nested
    @DisplayName("toString")
    class ToStringTests {

        @Test
        @DisplayName("excludes patient, hospital, encounter, invoiceItems")
        void excludesRelations() {
            BillingInvoice inv = BillingInvoice.builder()
                .patient(new Patient())
                .hospital(new Hospital())
                .invoiceNumber("INV-TS")
                .status(InvoiceStatus.DRAFT)
                .build();

            String s = inv.toString();
            assertNotNull(s);
            assertTrue(s.contains("BillingInvoice"));
            assertTrue(s.contains("INV-TS"));
            assertFalse(s.contains("patient=Patient("));
            assertFalse(s.contains("hospital=Hospital("));
            assertFalse(s.contains("invoiceItems="));
        }
    }

    // ═══════════════ BaseEntity inheritance ═══════════════

    @Nested
    @DisplayName("BaseEntity inheritance")
    class BaseEntityTests {

        @Test @DisplayName("id") void id() {
            BillingInvoice inv = new BillingInvoice();
            UUID id = UUID.randomUUID();
            inv.setId(id);
            assertEquals(id, inv.getId());
        }

        @Test @DisplayName("createdAt") void createdAt() {
            BillingInvoice inv = new BillingInvoice();
            var now = java.time.LocalDateTime.now();
            inv.setCreatedAt(now);
            assertEquals(now, inv.getCreatedAt());
        }

        @Test @DisplayName("updatedAt") void updatedAt() {
            BillingInvoice inv = new BillingInvoice();
            var now = java.time.LocalDateTime.now();
            inv.setUpdatedAt(now);
            assertEquals(now, inv.getUpdatedAt());
        }
    }

    // ═══════════════ addItem ═══════════════

    @Nested
    @DisplayName("addItem")
    class AddItem {

        @Test
        @DisplayName("adds item, sets back-reference, recomputes totals")
        void addsItem() {
            BillingInvoice inv = minimalInvoice();
            inv.setAmountPaid(BigDecimal.ZERO);
            inv.setInvoiceItems(new HashSet<>());

            InvoiceItem item = buildItem(new BigDecimal("50.00"));
            inv.addItem(item);

            assertAll(
                () -> assertSame(inv, item.getBillingInvoice()),
                () -> assertTrue(inv.getInvoiceItems().contains(item)),
                () -> assertEquals(new BigDecimal("50.00"), inv.getTotalAmount()),
                () -> assertEquals(InvoiceStatus.DRAFT, inv.getStatus()) // amountPaid=0, total>0 → DRAFT
            );
        }

        @Test
        @DisplayName("adds item with null totalPrice – treated as zero")
        void addsItemNullPrice() {
            BillingInvoice inv = minimalInvoice();
            inv.setAmountPaid(BigDecimal.ZERO);
            inv.setInvoiceItems(new HashSet<>());

            InvoiceItem item = buildItemNull();
            inv.addItem(item);

            assertEquals(new BigDecimal("0.00"), inv.getTotalAmount());
        }

        @Test
        @DisplayName("adds multiple items – totals sum correctly")
        void addsMultiple() {
            BillingInvoice inv = minimalInvoice();
            inv.setAmountPaid(BigDecimal.ZERO);
            inv.setInvoiceItems(new HashSet<>());

            inv.addItem(buildItem(new BigDecimal("30.00")));
            inv.addItem(buildItem(new BigDecimal("70.50")));

            assertEquals(new BigDecimal("100.50"), inv.getTotalAmount());
        }
    }

    // ═══════════════ removeItem ═══════════════

    @Nested
    @DisplayName("removeItem")
    class RemoveItem {

        @Test
        @DisplayName("removes item, nulls back-reference, recomputes totals")
        void removesItem() {
            BillingInvoice inv = minimalInvoice();
            inv.setAmountPaid(BigDecimal.ZERO);
            inv.setInvoiceItems(new HashSet<>());

            InvoiceItem item = buildItem(new BigDecimal("80.00"));
            inv.addItem(item);
            assertEquals(new BigDecimal("80.00"), inv.getTotalAmount());

            inv.removeItem(item);

            assertAll(
                () -> assertNull(item.getBillingInvoice()),
                () -> assertFalse(inv.getInvoiceItems().contains(item)),
                () -> assertEquals(new BigDecimal("0.00"), inv.getTotalAmount())
            );
        }
    }

    // ═══════════════ recomputeTotals (via addItem) – branch coverage ═══════════════

    @Nested
    @DisplayName("recomputeTotals branch coverage")
    class RecomputeTotals {

        @Test
        @DisplayName("amountPaid null → defaults to 0.00")
        void amountPaidNullDefaults() {
            BillingInvoice inv = minimalInvoice();
            inv.setAmountPaid(null); // force null
            inv.setInvoiceItems(new HashSet<>());

            inv.addItem(buildItem(new BigDecimal("10.00")));

            // amountPaid should now be 0.00
            assertEquals(new BigDecimal("0.00"), inv.getAmountPaid());
            // amountPaid=0, total>0 → DRAFT
            assertEquals(InvoiceStatus.DRAFT, inv.getStatus());
        }

        @Test
        @DisplayName("amountPaid >= totalAmount and total > 0 → PAID")
        void paidInFull() {
            BillingInvoice inv = minimalInvoice();
            inv.setAmountPaid(new BigDecimal("100.00"));
            inv.setInvoiceItems(new HashSet<>());

            inv.addItem(buildItem(new BigDecimal("100.00")));

            assertEquals(InvoiceStatus.PAID, inv.getStatus());
        }

        @Test
        @DisplayName("amountPaid > totalAmount → PAID")
        void overpaid() {
            BillingInvoice inv = minimalInvoice();
            inv.setAmountPaid(new BigDecimal("200.00"));
            inv.setInvoiceItems(new HashSet<>());

            inv.addItem(buildItem(new BigDecimal("50.00")));

            assertEquals(InvoiceStatus.PAID, inv.getStatus());
        }

        @Test
        @DisplayName("amountPaid > 0 but < total, status already set → status unchanged")
        void partialPayStatusPreserved() {
            BillingInvoice inv = minimalInvoice();
            inv.setAmountPaid(new BigDecimal("25.00"));
            inv.setStatus(InvoiceStatus.PARTIALLY_PAID);
            inv.setInvoiceItems(new HashSet<>());

            inv.addItem(buildItem(new BigDecimal("100.00")));

            // Not zero, not >= total → falls through; status was already PARTIALLY_PAID → stays
            assertEquals(InvoiceStatus.PARTIALLY_PAID, inv.getStatus());
        }

        @Test
        @DisplayName("amountPaid > 0 but < total, status is null → defaults to DRAFT")
        void partialPayStatusNull() {
            BillingInvoice inv = minimalInvoice();
            inv.setAmountPaid(new BigDecimal("25.00"));
            inv.setStatus(null);
            inv.setInvoiceItems(new HashSet<>());

            inv.addItem(buildItem(new BigDecimal("100.00")));

            assertEquals(InvoiceStatus.DRAFT, inv.getStatus());
        }

        @Test
        @DisplayName("total is 0 and amountPaid is 0 → status null gets DRAFT")
        void zeroTotalZeroPaidStatusNull() {
            BillingInvoice inv = minimalInvoice();
            inv.setAmountPaid(BigDecimal.ZERO);
            inv.setStatus(null);
            inv.setInvoiceItems(new HashSet<>());

            // Add item with zero price → total=0
            inv.addItem(buildItem(BigDecimal.ZERO));

            // amountPaid==0 but total is NOT >0 → first branch false
            // amountPaid(0) >= totalAmount(0) but total is NOT >0 → second branch false
            // status == null → DRAFT
            assertEquals(InvoiceStatus.DRAFT, inv.getStatus());
        }

        @Test
        @DisplayName("total is 0, amountPaid is 0, status already set → status unchanged")
        void zeroTotalZeroPaidStatusPreset() {
            BillingInvoice inv = minimalInvoice();
            inv.setAmountPaid(BigDecimal.ZERO);
            inv.setStatus(InvoiceStatus.CANCELLED);
            inv.setInvoiceItems(new HashSet<>());

            inv.addItem(buildItem(BigDecimal.ZERO));

            // Neither paid-in-full branch nor zero-paid branch fires (total=0)
            // status != null → stays CANCELLED
            assertEquals(InvoiceStatus.CANCELLED, inv.getStatus());
        }
    }

    // ═══════════════ prePersist ═══════════════

    @Nested
    @DisplayName("prePersist")
    class PrePersist {

        @Test
        @DisplayName("all nulls → defaults invoiceDate, dueDate, amountPaid, totalAmount, status")
        void allNullDefaults() {
            BillingInvoice inv = new BillingInvoice();
            inv.setInvoiceItems(new HashSet<>());

            inv.prePersist();

            assertAll(
                () -> assertNotNull(inv.getInvoiceDate()),
                () -> assertEquals(inv.getInvoiceDate().plusDays(30), inv.getDueDate()),
                () -> assertEquals(new BigDecimal("0.00"), inv.getAmountPaid()),
                () -> assertEquals(new BigDecimal("0.00"), inv.getTotalAmount()),
                () -> assertEquals(InvoiceStatus.DRAFT, inv.getStatus())
            );
        }

        @Test
        @DisplayName("invoiceDate preset → not overwritten")
        void invoiceDatePreset() {
            BillingInvoice inv = new BillingInvoice();
            inv.setInvoiceItems(new HashSet<>());
            LocalDate preset = LocalDate.of(2025, 12, 25);
            inv.setInvoiceDate(preset);

            inv.prePersist();

            assertEquals(preset, inv.getInvoiceDate());
            assertEquals(preset.plusDays(30), inv.getDueDate());
        }

        @Test
        @DisplayName("dueDate preset → not overwritten")
        void dueDatePreset() {
            BillingInvoice inv = new BillingInvoice();
            inv.setInvoiceItems(new HashSet<>());
            LocalDate invoiceD = LocalDate.of(2026, 1, 1);
            LocalDate dueD = LocalDate.of(2026, 3, 1);
            inv.setInvoiceDate(invoiceD);
            inv.setDueDate(dueD);

            inv.prePersist();

            assertEquals(dueD, inv.getDueDate());
        }

        @Test
        @DisplayName("amountPaid preset → not overwritten")
        void amountPaidPreset() {
            BillingInvoice inv = minimalInvoice();
            inv.setInvoiceItems(new HashSet<>());
            inv.setAmountPaid(new BigDecimal("50.00"));

            inv.prePersist();

            // After recomputeTotals (no items → total=0), amountPaid stays at 50.00
            assertEquals(new BigDecimal("50.00"), inv.getAmountPaid());
        }

        @Test
        @DisplayName("totalAmount preset → recomputed from items anyway")
        void totalAmountRecomputed() {
            BillingInvoice inv = minimalInvoice();
            inv.setInvoiceItems(new HashSet<>());
            inv.setTotalAmount(new BigDecimal("999.00"));
            inv.setAmountPaid(BigDecimal.ZERO);

            InvoiceItem item = buildItem(new BigDecimal("42.00"));
            item.setBillingInvoice(inv);
            inv.getInvoiceItems().add(item);

            inv.prePersist();

            // recomputeTotals overrides totalAmount
            assertEquals(new BigDecimal("42.00"), inv.getTotalAmount());
        }

        @Test
        @DisplayName("status preset → may be updated by recomputeTotals")
        void statusPreset() {
            BillingInvoice inv = minimalInvoice();
            inv.setInvoiceItems(new HashSet<>());
            inv.setAmountPaid(BigDecimal.ZERO);
            inv.setStatus(InvoiceStatus.SENT);

            InvoiceItem item = buildItem(new BigDecimal("10.00"));
            item.setBillingInvoice(inv);
            inv.getInvoiceItems().add(item);

            inv.prePersist();

            // amountPaid=0 and total>0 → DRAFT (overwrites SENT)
            assertEquals(InvoiceStatus.DRAFT, inv.getStatus());
        }

        @Test
        @DisplayName("dueDate before invoiceDate → throws IllegalStateException")
        void dueDateBeforeInvoiceDate() {
            BillingInvoice inv = new BillingInvoice();
            inv.setInvoiceItems(new HashSet<>());
            inv.setInvoiceDate(LocalDate.of(2026, 6, 1));
            inv.setDueDate(LocalDate.of(2026, 5, 1));

            IllegalStateException ex = assertThrows(IllegalStateException.class, inv::prePersist);
            assertEquals("dueDate cannot be before invoiceDate", ex.getMessage());
        }

        @Test
        @DisplayName("dueDate equals invoiceDate → no exception")
        void dueDateEqualsInvoiceDate() {
            BillingInvoice inv = new BillingInvoice();
            inv.setInvoiceItems(new HashSet<>());
            LocalDate same = LocalDate.of(2026, 6, 1);
            inv.setInvoiceDate(same);
            inv.setDueDate(same);

            assertDoesNotThrow(inv::prePersist);
        }

        @Test
        @DisplayName("totalAmount null preset → defaults to 0.00 then recomputed")
        void totalAmountNullDefault() {
            BillingInvoice inv = new BillingInvoice();
            inv.setInvoiceItems(new HashSet<>());
            inv.setInvoiceDate(LocalDate.of(2026, 1, 1));
            inv.setDueDate(LocalDate.of(2026, 2, 1));

            inv.prePersist();

            assertEquals(new BigDecimal("0.00"), inv.getTotalAmount());
        }
    }

    // ═══════════════ preUpdate ═══════════════

    @Nested
    @DisplayName("preUpdate")
    class PreUpdate {

        @Test
        @DisplayName("recomputes totals and validates dates")
        void recomputesAndValidates() {
            BillingInvoice inv = minimalInvoice();
            inv.setInvoiceItems(new HashSet<>());
            inv.setAmountPaid(BigDecimal.ZERO);
            inv.setStatus(InvoiceStatus.DRAFT);

            InvoiceItem item = buildItem(new BigDecimal("75.00"));
            item.setBillingInvoice(inv);
            inv.getInvoiceItems().add(item);

            inv.preUpdate();

            assertEquals(new BigDecimal("75.00"), inv.getTotalAmount());
        }

        @Test
        @DisplayName("dueDate before invoiceDate → throws IllegalStateException")
        void dueDateBeforeInvoiceDate() {
            BillingInvoice inv = new BillingInvoice();
            inv.setInvoiceItems(new HashSet<>());
            inv.setAmountPaid(BigDecimal.ZERO);
            inv.setInvoiceDate(LocalDate.of(2026, 6, 1));
            inv.setDueDate(LocalDate.of(2026, 5, 1));

            IllegalStateException ex = assertThrows(IllegalStateException.class, inv::preUpdate);
            assertEquals("dueDate cannot be before invoiceDate", ex.getMessage());
        }

        @Test
        @DisplayName("dueDate equals invoiceDate → no exception")
        void dueDateEqualsInvoiceDate() {
            BillingInvoice inv = new BillingInvoice();
            inv.setInvoiceItems(new HashSet<>());
            inv.setAmountPaid(BigDecimal.ZERO);
            LocalDate same = LocalDate.of(2026, 6, 1);
            inv.setInvoiceDate(same);
            inv.setDueDate(same);

            assertDoesNotThrow(inv::preUpdate);
        }

        @Test
        @DisplayName("preUpdate with paid in full → PAID status")
        void preUpdatePaid() {
            BillingInvoice inv = minimalInvoice();
            inv.setInvoiceItems(new HashSet<>());
            inv.setAmountPaid(new BigDecimal("60.00"));
            inv.setStatus(InvoiceStatus.SENT);

            InvoiceItem item = buildItem(new BigDecimal("60.00"));
            item.setBillingInvoice(inv);
            inv.getInvoiceItems().add(item);

            inv.preUpdate();

            assertEquals(InvoiceStatus.PAID, inv.getStatus());
        }
    }

    // ═══════════════ Equals / HashCode ═══════════════

    @Nested
    @DisplayName("equals and hashCode (via BaseEntity)")
    class EqualsHashCode {

        @Test
        @DisplayName("same id → equal")
        void sameId() {
            UUID id = UUID.randomUUID();
            BillingInvoice a = new BillingInvoice();
            a.setId(id);
            BillingInvoice b = new BillingInvoice();
            b.setId(id);
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        @DisplayName("different id → not equal")
        void differentId() {
            BillingInvoice a = new BillingInvoice();
            a.setId(UUID.randomUUID());
            BillingInvoice b = new BillingInvoice();
            b.setId(UUID.randomUUID());
            assertNotEquals(a, b);
        }

        @Test
        @DisplayName("equal to itself")
        void sameRef() {
            BillingInvoice a = new BillingInvoice();
            a.setId(UUID.randomUUID());
            BillingInvoice sameRef = a;
            assertEquals(a, sameRef);
        }

        @Test
        @DisplayName("not equal to null")
        void notNull() {
            BillingInvoice a = new BillingInvoice();
            a.setId(UUID.randomUUID());
            assertNotEquals(null, a);
        }
    }

    // ═══════════════ InvoiceStatus enum values ═══════════════

    @Nested
    @DisplayName("InvoiceStatus enum coverage")
    class StatusEnum {

        @Test
        @DisplayName("can set every InvoiceStatus value")
        void allStatuses() {
            BillingInvoice inv = new BillingInvoice();
            for (InvoiceStatus s : InvoiceStatus.values()) {
                inv.setStatus(s);
                assertEquals(s, inv.getStatus());
            }
        }
    }
}
