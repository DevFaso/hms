package com.example.hms.validation;

import com.example.hms.enums.ReadyCancelReason;
import com.example.hms.enums.ReturnedStockCondition;
import com.example.hms.enums.StockTransactionType;
import com.example.hms.payload.dto.pharmacy.CancelReadyRequestDTO;
import com.example.hms.payload.dto.pharmacy.StockTransactionRequestDTO;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Review of #1285: the stock-state rules are named at the edge, with the other refusals (QA D1300 / D1371). */
class PharmacyStockStateConstraintsTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private static <T> Set<String> fields(Set<ConstraintViolation<T>> violations) {
        return violations.stream().map(v -> v.getPropertyPath().toString()).collect(Collectors.toSet());
    }

    @Test
    @DisplayName("a RETURN with no stock state is refused on stockCondition; another movement is not")
    void aReturnNamesItsState() {
        StockTransactionRequestDTO ret = StockTransactionRequestDTO.builder()
                .transactionType(StockTransactionType.RETURN).build();
        assertThat(fields(validator.validate(ret))).contains("stockCondition");
        ret.setStockCondition(ReturnedStockCondition.DAMAGED);
        assertThat(fields(validator.validate(ret))).doesNotContain("stockCondition");
        StockTransactionRequestDTO adjustment = StockTransactionRequestDTO.builder()
                .transactionType(StockTransactionType.ADJUSTMENT).build();
        assertThat(fields(validator.validate(adjustment))).doesNotContain("stockCondition");
    }

    @Test
    @DisplayName("« stock manquant » cancelled as intact is refused on both fields; any other pair is not")
    void stockUnavailableIsNeverIntact() {
        CancelReadyRequestDTO intact = new CancelReadyRequestDTO(ReadyCancelReason.STOCK_UNAVAILABLE,
                ReturnedStockCondition.INTACT, null);
        assertThat(fields(validator.validate(intact))).contains("stockCondition", "reason");
        CancelReadyRequestDTO damaged = new CancelReadyRequestDTO(ReadyCancelReason.STOCK_UNAVAILABLE,
                ReturnedStockCondition.DAMAGED, null);
        assertThat(validator.validate(damaged)).isEmpty();
        CancelReadyRequestDTO notCollected = new CancelReadyRequestDTO(ReadyCancelReason.NOT_COLLECTED,
                ReturnedStockCondition.INTACT, null);
        assertThat(validator.validate(notCollected)).isEmpty();
    }
}
