package com.example.hms.service.recordaccess;

import com.example.hms.enums.RecordAccessDenialReason;
import com.example.hms.enums.RecordAccessPosture;
import com.example.hms.enums.TenantIsolationMode;
import com.example.hms.enums.TreatmentRelationshipKind;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRecordSharingOptOutRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class RecordAccessPolicyImpl implements RecordAccessPolicy {

    private static final String CACHE_PREFIX = RecordAccessPolicyImpl.class.getName() + ":";

    private final HospitalRepository hospitalRepository;
    private final PatientRecordSharingOptOutRepository optOutRepository;
    private final StaffRepository staffRepository;
    private final TreatmentRelationshipResolver resolver;
    private final PatientHospitalRegistrationRepository registrationRepository;
    private final BreakGlassGate breakGlassGate;
    private final PatientRepository patientRepository;

    public RecordAccessPolicyImpl(HospitalRepository hospitalRepository,
                                  PatientRecordSharingOptOutRepository optOutRepository,
                                  StaffRepository staffRepository,
                                  TreatmentRelationshipResolver resolver,
                                  PatientHospitalRegistrationRepository registrationRepository,
                                  BreakGlassGate breakGlassGate,
                                  PatientRepository patientRepository) {
        this.hospitalRepository = hospitalRepository;
        this.optOutRepository = optOutRepository;
        this.staffRepository = staffRepository;
        this.resolver = resolver;
        this.registrationRepository = registrationRepository;
        this.breakGlassGate = breakGlassGate;
        this.patientRepository = patientRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public RecordAccessDecision decide(UUID actorUserId, UUID patientId, UUID actingHospitalId) {
        return cachedDecision(actorUserId, patientId, actingHospitalId);
    }

    /**
     * The cached decision, reachable without going back through the proxy.
     * {@link #readableHospitalIds} needs the same answer, and calling
     * {@code decide} from it would be a {@code @Transactional} self-invocation
     * (Sonar S2229): the inner annotation never applies, because Spring's proxy
     * is invocation-time.
     */
    private RecordAccessDecision cachedDecision(UUID actorUserId, UUID patientId, UUID actingHospitalId) {
        String key = CACHE_PREFIX + actorUserId + ":" + patientId + ":" + actingHospitalId;
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            Object cached = attrs.getAttribute(key, RequestAttributes.SCOPE_REQUEST);
            if (cached instanceof RecordAccessDecision d) {
                return d;
            }
        }
        RecordAccessDecision decision = evaluate(actorUserId, patientId, actingHospitalId);
        if (attrs != null) {
            attrs.setAttribute(key, decision, RequestAttributes.SCOPE_REQUEST);
        }
        return decision;
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> readableHospitalIds(UUID actorUserId, UUID patientId, UUID actingHospitalId) {
        Set<UUID> readable = new LinkedHashSet<>();
        if (actingHospitalId != null) {
            // The acting hospital is always readable — that does not depend
            // on the posture or a treatment relationship. E9 #58 removed the
            // feature flag that used to stop here: the widening is the model
            // now (decision D1), not an experiment.
            readable.add(actingHospitalId);
        }
        if (patientId == null || actingHospitalId == null) {
            return readable;
        }
        if (!cachedDecision(actorUserId, patientId, actingHospitalId).permitted()) {
            return readable;
        }
        for (PatientHospitalRegistration registration : registrationRepository.findByPatientId(patientId)) {
            Hospital source = registration.getHospital();
            if (disclosesOnTreatmentPresumption(source)) {
                readable.add(source.getId());
            }
        }
        return readable;
    }

    /**
     * Whether this hospital lets its own records be read on the treatment
     * presumption. A {@code SCHEMA}-isolated tenant never does, by
     * construction; {@code EXPLICIT_CONSENT} keeps its records behind consent
     * even when the reader's hospital presumes treatment.
     */
    private static boolean disclosesOnTreatmentPresumption(Hospital source) {
        if (source == null || source.getId() == null
            || source.getIsolationMode() == TenantIsolationMode.SCHEMA) {
            return false;
        }
        RecordAccessPosture posture = source.getRecordAccessPosture();
        return posture == null || posture == RecordAccessPosture.TREATMENT_PRESUMED;
    }

    private RecordAccessDecision evaluate(UUID actorUserId, UUID patientId, UUID hospitalId) {
        if (hospitalId == null || patientId == null) {
            return RecordAccessDecision.refused(patientId, hospitalId, actorUserId,
                RecordAccessDenialReason.HOSPITAL_UNKNOWN, null);
        }
        Optional<Hospital> hospital = hospitalRepository.findById(hospitalId);
        if (hospital.isEmpty()) {
            return RecordAccessDecision.refused(patientId, hospitalId, actorUserId,
                RecordAccessDenialReason.HOSPITAL_UNKNOWN, null);
        }
        Hospital h = hospital.get();
        RecordAccessPosture posture = h.getRecordAccessPosture() != null
            ? h.getRecordAccessPosture() : RecordAccessPosture.TREATMENT_PRESUMED;

        // A SCHEMA tenant's clinical tables live in their own schema: there is
        // nothing to read across, and the decision record says there must not be.
        if (h.getIsolationMode() == TenantIsolationMode.SCHEMA) {
            return RecordAccessDecision.refused(patientId, hospitalId, actorUserId,
                RecordAccessDenialReason.SCHEMA_ISOLATED_TENANT, posture);
        }
        if (posture != RecordAccessPosture.TREATMENT_PRESUMED) {
            return RecordAccessDecision.refused(patientId, hospitalId, actorUserId,
                RecordAccessDenialReason.HOSPITAL_REQUIRES_CONSENT, posture);
        }
        // Opt-out is checked before the relationship on purpose: a patient who
        // has withdrawn from sharing gets the same answer whether or not they
        // are on this hospital's schedule, so the refusal itself discloses
        // nothing about their movements.
        if (optOutRepository.existsByPatient_IdAndRevokedAtIsNull(patientId)) {
            return RecordAccessDecision.refused(patientId, hospitalId, actorUserId,
                RecordAccessDenialReason.PATIENT_OPTED_OUT, posture);
        }
        boolean staffHere = actorUserId != null
            && staffRepository.findByUserIdAndHospitalId(actorUserId, hospitalId)
                .map(s -> s.isActive())
                .orElse(false);
        if (!staffHere) {
            return RecordAccessDecision.refused(patientId, hospitalId, actorUserId,
                RecordAccessDenialReason.NOT_STAFF_AT_HOSPITAL, posture);
        }
        // E9 #58 (D1) — registration at the acting hospital IS the treatment
        // relationship. Reception creates it on arrival, every write already
        // requires it, and it is a deliberate act by a person at a desk. The
        // resolver's carriers (admission, encounter, appointment window, panel,
        // open order) remain the second signal for a patient who is scheduled
        // or admitted here but not yet linked by the desk.
        Optional<PatientHospitalRegistration> registered =
            registrationRepository.findByPatientIdAndHospitalId(patientId, hospitalId);
        if (registered.isPresent()) {
            PatientHospitalRegistration r = registered.get();
            TreatmentRelationship byRegistration = new TreatmentRelationship(
                TreatmentRelationshipKind.REGISTRATION, r.getId(), hospitalId, patientId,
                r.getRegistrationDate() != null ? r.getRegistrationDate().atStartOfDay() : null,
                null, false);
            return unlessRestricted(actorUserId, patientId, hospitalId, byRegistration, posture);
        }
        Optional<TreatmentRelationship> carried = resolver.resolve(patientId, hospitalId, actorUserId);
        if (carried.isPresent()) {
            return unlessRestricted(actorUserId, patientId, hospitalId, carried.get(), posture);
        }
        // E9 #62 (decision D2, Tier B) — no registration and no carrier: a live
        // break-the-glass session the actor declared for this patient at this
        // hospital stands in for the relationship. Opt-out and the staff gate
        // above still hold; the session is time-boxed, audited on declaration,
        // and every disclosure row it enables carries its id.
        return breakGlassGate.liveSession(actorUserId, patientId, hospitalId)
            .map(s -> new TreatmentRelationship(TreatmentRelationshipKind.BREAK_GLASS, s.getId(),
                hospitalId, patientId, s.getStartedAt(), s.getExpiresAt(), true))
            .map(rel -> RecordAccessDecision.permitted(patientId, hospitalId, actorUserId, rel, posture))
            .orElseGet(() -> RecordAccessDecision.refused(patientId, hospitalId, actorUserId,
                RecordAccessDenialReason.NO_TREATMENT_RELATIONSHIP, posture));
    }

    /**
     * E8 #54 — a restricted chart needs a live break-the-glass session even
     * from staff who hold a treatment relationship. The flag is read only once
     * a relationship exists, so the refusal discloses nothing to a stranger;
     * under a session the relationship becomes BREAK_GLASS and the read is
     * audited as such.
     */
    private RecordAccessDecision unlessRestricted(UUID actorUserId, UUID patientId, UUID hospitalId,
                                                  TreatmentRelationship relationship, RecordAccessPosture posture) {
        boolean restricted = patientRepository.findByIdUnscoped(patientId)
            .map(Patient::isChartRestricted)
            .orElse(false);
        if (!restricted) {
            return RecordAccessDecision.permitted(patientId, hospitalId, actorUserId, relationship, posture);
        }
        return breakGlassGate.liveSession(actorUserId, patientId, hospitalId)
            .map(s -> new TreatmentRelationship(TreatmentRelationshipKind.BREAK_GLASS, s.getId(),
                hospitalId, patientId, s.getStartedAt(), s.getExpiresAt(), true))
            .map(rel -> RecordAccessDecision.permitted(patientId, hospitalId, actorUserId, rel, posture))
            .orElseGet(() -> RecordAccessDecision.refused(patientId, hospitalId, actorUserId,
                RecordAccessDenialReason.CHART_RESTRICTED, posture));
    }
}
