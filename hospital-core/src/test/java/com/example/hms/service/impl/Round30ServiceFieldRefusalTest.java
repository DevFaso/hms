package com.example.hms.service.impl;

import com.example.hms.enums.AdvanceDirectiveStatus;
import com.example.hms.enums.AdvanceDirectiveType;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.AdvanceDirective;
import com.example.hms.service.support.CancelledOrders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * QA round 30: the service refusals of D1353 (an order cancel without a
 * reason) and D1355 (an active DNR without its physician and date) carry
 * their fields, and D1355 names both fields in one refusal.
 */
@DisplayName("Round 30: service refusals name their fields")
class Round30ServiceFieldRefusalTest {

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    @DisplayName("D1353: a cancel without a reason is refused on the field the caller read it from")
    void d1353_aBlankCancelReasonNamesTheCallersField(String reason) {
        assertThatThrownBy(() -> CancelledOrders.requireCancelReason(reason, "cancellationReason"))
            .isInstanceOf(FieldBusinessException.class)
            .hasFieldOrPropertyWithValue("messageKey", CancelledOrders.CANCEL_REASON_REQUIRED)
            .satisfies(e -> assertThat(((FieldBusinessException) e).getFieldErrors()).containsOnlyKeys("cancellationReason"));
        assertThatThrownBy(() -> CancelledOrders.requireCancelReason(reason, "reason"))
            .isInstanceOf(FieldBusinessException.class)
            .hasFieldOrPropertyWithValue("field", "reason");
    }

    @Test
    @DisplayName("D1353: a given reason is kept, trimmed")
    void d1353_aGivenReasonIsKept() {
        assertThat(CancelledOrders.requireCancelReason("  doublon  ", "reason")).isEqualTo("doublon");
    }

    @Test
    @DisplayName("D1355: an active DNR with neither physician nor date names both in one refusal")
    void d1355_bothMissingFieldsAreNamedInOneRefusal() {
        AdvanceDirective directive = dnr(null, null);

        assertThatThrownBy(() -> AdvanceDirectiveServiceImpl.requireOrderDetails(directive))
            .isInstanceOf(FieldBusinessException.class)
            .satisfies(e -> assertThat(((FieldBusinessException) e).getFieldErrors())
                .containsOnlyKeys("physicianName", "effectiveDate"));
    }

    @Test
    @DisplayName("D1355: a blank physician name counts as missing, with the date")
    void d1355_aBlankPhysicianIsMissingToo() {
        AdvanceDirective directive = dnr("  ", null);

        assertThatThrownBy(() -> AdvanceDirectiveServiceImpl.requireOrderDetails(directive))
            .satisfies(e -> assertThat(((FieldBusinessException) e).getFieldErrors())
                .containsOnlyKeys("physicianName", "effectiveDate"));
    }

    @Test
    @DisplayName("D1355: one missing field keeps the single-field refusal and its key")
    void d1355_oneMissingFieldKeepsItsKey() {
        AdvanceDirective directive = dnr("Dr Ouédraogo", null);

        assertThatThrownBy(() -> AdvanceDirectiveServiceImpl.requireOrderDetails(directive))
            .isInstanceOf(FieldBusinessException.class)
            .hasFieldOrPropertyWithValue("field", "effectiveDate")
            .hasFieldOrPropertyWithValue("messageKey", "advanceDirective.effectiveDate.required");
    }

    @Test
    @DisplayName("D1355: a complete DNR, and a directive that is not a resuscitation order, pass")
    void d1355_completeOrNotAnOrderPasses() {
        assertThatCode(() -> AdvanceDirectiveServiceImpl.requireOrderDetails(dnr("Dr Ouédraogo", LocalDate.of(2026, 11, 2))))
            .doesNotThrowAnyException();
        AdvanceDirective living = dnr(null, null);
        living.setDirectiveType(AdvanceDirectiveType.LIVING_WILL);
        assertThatCode(() -> AdvanceDirectiveServiceImpl.requireOrderDetails(living)).doesNotThrowAnyException();
    }

    private static AdvanceDirective dnr(String physician, LocalDate effective) {
        AdvanceDirective directive = new AdvanceDirective();
        directive.setDirectiveType(AdvanceDirectiveType.DO_NOT_RESUSCITATE);
        directive.setStatus(AdvanceDirectiveStatus.ACTIVE);
        directive.setPhysicianName(physician);
        directive.setEffectiveDate(effective);
        return directive;
    }
}
