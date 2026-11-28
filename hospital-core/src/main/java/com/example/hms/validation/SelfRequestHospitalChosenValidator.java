package com.example.hms.validation;

import com.example.hms.payload.dto.roi.RoiSelfRequestCreateDTO;
import com.example.hms.service.PatientPortalService;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The {@link SelfRequestHospitalChosen} check. The patient is the signed-in caller; outside a request, without the
 * portal service (a test slice, a validator built by hand) or when the patient cannot be resolved, it says nothing
 * and the service check decides. The same rule as the service: {@code PatientPortalService#selfRequestHospitalRefusal}.
 */
public class SelfRequestHospitalChosenValidator
    implements ConstraintValidator<SelfRequestHospitalChosen, RoiSelfRequestCreateDTO> {

    static final String FIELD = "hospitalId";

    private final ObjectProvider<PatientPortalService> portal;

    /** Built by Spring's validator factory, with the portal service when the context has one. */
    @org.springframework.beans.factory.annotation.Autowired
    public SelfRequestHospitalChosenValidator(ObjectProvider<PatientPortalService> portal) {
        this.portal = portal;
    }

    /** Built by a plain Bean Validation factory: no service, so the service check decides. */
    public SelfRequestHospitalChosenValidator() {
        this.portal = null;
    }

    @Override
    public boolean isValid(RoiSelfRequestCreateDTO body, ConstraintValidatorContext context) {
        if (body == null || body.getHospitalId() != null) {
            // A named hospital is checked against the registrations by the service.
            return true;
        }
        PatientPortalService service = portal == null ? null : portal.getIfAvailable();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String refusal = service == null || auth == null ? null : service.selfRequestHospitalRefusal(auth, null);
        if (refusal == null) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate("{" + refusal + "}")
            .addPropertyNode(FIELD)
            .addConstraintViolation();
        return false;
    }
}
