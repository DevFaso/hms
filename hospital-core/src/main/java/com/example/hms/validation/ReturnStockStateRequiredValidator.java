package com.example.hms.validation;

import com.example.hms.enums.StockTransactionType;
import com.example.hms.payload.dto.pharmacy.StockTransactionRequestDTO;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** Review of #1285: a RETURN with no {@code stockCondition} is refused on that field, with the others. */
public class ReturnStockStateRequiredValidator
        implements ConstraintValidator<ReturnStockStateRequired, StockTransactionRequestDTO> {

    @Override
    public boolean isValid(StockTransactionRequestDTO request, ConstraintValidatorContext context) {
        if (request == null || request.getTransactionType() != StockTransactionType.RETURN
                || request.getStockCondition() != null) {
            return true;
        }
        if (context != null) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                    .addPropertyNode("stockCondition")
                    .addConstraintViolation();
        }
        return false;
    }
}
