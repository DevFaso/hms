package com.example.hms.validation;

import com.example.hms.payload.dto.PrescriptionRequestDTO;
import com.example.hms.service.impl.PrnMinInterval;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * QA round 30 / QA D1378 (round 32): a NEW prescription's as-needed part, on
 * create. The tick absent means not as-needed. Ticked: the interval is
 * required. Not ticked: an interval is refused, and a frequency that looks
 * conditional is refused on {@code asNeeded} (the safety net) - each reported
 * on its own field with every other refusal of the request (the D1349 rule).
 * With an explicit {@code asNeeded: false} only the safety net is checked
 * here; its interval is {@link PrnMinIntervalOnlyWhenAsNeededValidator}'s,
 * which also runs on create, so a refusal is never reported twice.
 */
public class PrnMinIntervalRequiredValidator
        implements ConstraintValidator<PrnMinIntervalRequired, PrescriptionRequestDTO> {

    @Override
    public boolean isValid(PrescriptionRequestDTO request, ConstraintValidatorContext context) {
        if (request == null) {
            return true;
        }
        if (Boolean.FALSE.equals(request.getAsNeeded())) {
            // The interval half is the Default-group constraint's; only the safety net here.
            return PrnRefusalReport.report(context, PrnMinInterval.safetyNet(request.getFrequency()));
        }
        return PrnRefusalReport.report(context, PrnMinInterval.refusalOf(
            Boolean.TRUE.equals(request.getAsNeeded()), request.getPrnMinIntervalHours(), request.getFrequency()));
    }
}
