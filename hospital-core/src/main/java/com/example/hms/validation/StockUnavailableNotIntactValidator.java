package com.example.hms.validation;

import com.example.hms.enums.ReadyCancelReason;
import com.example.hms.enums.ReturnedStockCondition;
import com.example.hms.payload.dto.pharmacy.CancelReadyRequestDTO;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Review of #1285: STOCK_UNAVAILABLE with INTACT is refused on both fields it involves ({@code reason} and
 * {@code stockCondition}), so the form marks both and changing either one answers it.
 */
public class StockUnavailableNotIntactValidator
        implements ConstraintValidator<StockUnavailableNotIntact, CancelReadyRequestDTO> {

    @Override
    public boolean isValid(CancelReadyRequestDTO request, ConstraintValidatorContext context) {
        if (request == null || request.getReason() != ReadyCancelReason.STOCK_UNAVAILABLE
                || request.getStockCondition() != ReturnedStockCondition.INTACT) {
            return true;
        }
        if (context != null) {
            context.disableDefaultConstraintViolation();
            for (String field : new String[] {"stockCondition", "reason"}) {
                context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                        .addPropertyNode(field)
                        .addConstraintViolation();
            }
        }
        return false;
    }
}
