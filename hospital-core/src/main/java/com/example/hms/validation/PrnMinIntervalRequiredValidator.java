package com.example.hms.validation;

import com.example.hms.payload.dto.PrescriptionRequestDTO;
import com.example.hms.service.impl.PrnMinInterval;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * QA round 30 / QA D1378 (round 32): a NEW prescription's as-needed part, on
 * create. The tick absent means not as-needed. Ticked: the interval is
 * required. Not ticked: an interval is refused, a condition is refused on
 * {@code asNeeded} and a wording the MAR cannot schedule is asked about on
 * {@code frequencyConfirmed} (the wording rule, QA D1419), and a missing
 * frequency is refused on {@code frequency} (QA D1491) - each reported on
 * its own field with every other refusal of the request (the D1349 rule).
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
        boolean confirmed = Boolean.TRUE.equals(request.getFrequencyConfirmed());
        if (Boolean.FALSE.equals(request.getAsNeeded())) {
            // The interval half is the Default-group constraint's; only the frequency and the safety net here.
            PrnMinInterval.Refusal missing = PrnMinInterval.missingFrequency(request.getFrequency());
            return PrnRefusalReport.report(context,
                missing != null ? missing : PrnMinInterval.safetyNet(request.getFrequency(), confirmed));
        }
        return PrnRefusalReport.report(context, PrnMinInterval.refusalOf(
            Boolean.TRUE.equals(request.getAsNeeded()), request.getPrnMinIntervalHours(), request.getFrequency(),
            confirmed));
    }
}
