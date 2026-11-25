package com.example.hms.security.chart;

import com.example.hms.config.SecurityConstants;
import com.example.hms.exception.ChartRestrictedException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.BreakGlassSession;
import com.example.hms.repository.BreakGlassSessionRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.security.PrincipalUserIds;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.service.recordaccess.BreakGlassGate;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * QA D3863: the one answer to "may this caller read this patient's chart now?"
 * for a chart the hospital administrator has restricted (E8 #54).
 *
 * <p>The restriction promises that every read of the chart needs a live
 * break-the-glass session. It used to be kept by each chart section on its own
 * ({@code PatientChartAccess} through the record-access policy). This gate is
 * read by the central hooks instead, so every read path, and a new endpoint
 * before anyone thinks about it, goes through the same check: {@link RestrictedChartGuardInterceptor} before the handler,
 * {@link RestrictedChartResponseMasking} on the answer, and the FHIR boundary.
 *
 * <p><b>Who the restriction applies to</b> ({@link #isSubject}): every signed-in
 * caller holding a role outside {@link #NOT_SUBJECT_ROLES}. Those are the
 * patient (their own record), the front desk (which already reads nothing
 * clinical, RCPT-CLIN), and the fulfilment roles (pharmacy, laboratory,
 * imaging), whose work is authorised by the signed order and who cannot
 * declare a break-the-glass session: refusing them would stop a restricted
 * patient's medicines, tests and images, not protect the chart.
 *
 * <p><b>Break-the-glass</b> lifts it: a live session the caller declared for
 * the patient at the hospital the request acts at, or, with no hospital
 * pinned yet (a super-admin in global view, a caller who names the hospital
 * by parameter), at any hospital where the caller works. Every read under it
 * is counted and audited by {@code PatientAccessAuditInterceptor}, unchanged.
 *
 * <p>Answers are cached for the request.
 */
@Component
public class RestrictedChartGate {

    /** Roles that leave a caller outside the restriction; holding any other role puts them inside. */
    public static final Set<String> NOT_SUBJECT_ROLES = Set.of(
        SecurityConstants.ROLE_PATIENT,
        SecurityConstants.ROLE_STAFF,
        SecurityConstants.ROLE_RECEPTIONIST,
        SecurityConstants.ROLE_PHARMACIST,
        SecurityConstants.ROLE_PHARMACY_VERIFIER,
        SecurityConstants.ROLE_INVENTORY_CLERK,
        SecurityConstants.ROLE_STORE_MANAGER,
        SecurityConstants.ROLE_LAB_SCIENTIST,
        SecurityConstants.ROLE_LAB_TECHNICIAN,
        SecurityConstants.ROLE_LAB_MANAGER,
        SecurityConstants.ROLE_LAB_DIRECTOR,
        SecurityConstants.ROLE_QUALITY_MANAGER,
        SecurityConstants.ROLE_RADIOLOGIST,
        SecurityConstants.ROLE_IMAGING_TECHNICIAN);

    private static final String CACHE_PREFIX = RestrictedChartGate.class.getName() + ":";
    private static final String RESTRICTED_CACHE = CACHE_PREFIX + "restricted";
    private static final String ANY_RESTRICTED = CACHE_PREFIX + "any";
    private static final String MSG_PATIENT_NOT_FOUND = "patient.notFound";
    private static final int MAX_IN_LIST = 1000;

    /** The record a key names → the JPQL that reads its patient. Unscoped: the answer only narrows a response. */
    private static final Map<ChartKeys.Owner, String> OWNER_QUERIES = Map.of(
        ChartKeys.Owner.ENCOUNTER, "SELECT e.patient.id FROM Encounter e WHERE e.id = :id",
        ChartKeys.Owner.ADMISSION, "SELECT a.patient.id FROM Admission a WHERE a.id = :id",
        ChartKeys.Owner.PRESCRIPTION, "SELECT p.patient.id FROM Prescription p WHERE p.id = :id",
        ChartKeys.Owner.NURSING_NOTE, "SELECT n.patient.id FROM NursingNote n WHERE n.id = :id",
        ChartKeys.Owner.REFERRAL, "SELECT r.patient.id FROM GeneralReferral r WHERE r.id = :id",
        ChartKeys.Owner.IMAGING_REPORT, "SELECT r.imagingOrder.patient.id FROM ImagingReport r WHERE r.id = :id",
        ChartKeys.Owner.IMAGING_IMAGE, "SELECT i.imagingOrder.patient.id FROM ImagingStudyImage i WHERE i.id = :id");

    private final PatientRepository patientRepository;
    private final PatientHospitalRegistrationRepository registrationRepository;
    private final BreakGlassGate breakGlassGate;
    private final BreakGlassSessionRepository sessionRepository;
    private final Clock clock;

    @PersistenceContext
    private EntityManager entityManager;

    public RestrictedChartGate(PatientRepository patientRepository,
                               PatientHospitalRegistrationRepository registrationRepository,
                               BreakGlassGate breakGlassGate,
                               BreakGlassSessionRepository sessionRepository,
                               Clock clock) {
        this.patientRepository = patientRepository;
        this.registrationRepository = registrationRepository;
        this.breakGlassGate = breakGlassGate;
        this.sessionRepository = sessionRepository;
        this.clock = clock;
    }

    /** The restriction applies to the current caller: signed in, holding a role outside {@link #NOT_SUBJECT_ROLES}. */
    public boolean isSubject() {
        return isSubject(SecurityContextHolder.getContext().getAuthentication());
    }

    static boolean isSubject(Authentication authentication) {
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken
            || !authentication.isAuthenticated() || authentication.getAuthorities() == null) {
            return false;
        }
        for (GrantedAuthority granted : authentication.getAuthorities()) {
            String authority = granted.getAuthority();
            if (authority != null && authority.startsWith(SecurityConstants.ROLE_PREFIX)
                && !NOT_SUBJECT_ROLES.contains(authority)) {
                return true;
            }
        }
        return false;
    }

    /** Is any chart restricted at all? While none is, no response needs looking at. */
    @Transactional(readOnly = true)
    public boolean anyRestricted() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs != null && attrs.getAttribute(ANY_RESTRICTED, RequestAttributes.SCOPE_REQUEST) instanceof Boolean b) {
            return b;
        }
        boolean any = patientRepository.existsAnyChartRestricted();
        if (attrs != null) {
            attrs.setAttribute(ANY_RESTRICTED, any, RequestAttributes.SCOPE_REQUEST);
        }
        return any;
    }

    /** True when the current caller may not read this patient's chart now. */
    @Transactional(readOnly = true)
    public boolean isBlocked(UUID patientId) {
        return patientId != null && !blockedAmong(List.of(patientId)).isEmpty();
    }

    /** The patients among {@code patientIds} whose chart the current caller may not read now. */
    @Transactional(readOnly = true)
    public Set<UUID> blockedAmong(Collection<UUID> patientIds) {
        Set<UUID> blocked = new HashSet<>();
        if (patientIds == null || patientIds.isEmpty() || !isSubject()) {
            return blocked;
        }
        for (UUID restricted : restrictedAmong(patientIds)) {
            if (!holdsLiveSession(restricted)) {
                blocked.add(restricted);
            }
        }
        return blocked;
    }

    /**
     * Refuse the read when the chart is blocked for the caller: 403
     * {@code CHART_RESTRICTED} (the portal answers it with the declaration
     * prompt) when the patient is known at a hospital the caller acts at, or
     * the caller is a super-admin; otherwise 404, so the refusal tells a
     * stranger nothing (the same rule as {@code RecordAccessPolicyImpl}, which
     * reads the flag only once a relationship exists).
     */
    @Transactional(readOnly = true)
    public void requireReadable(UUID patientId) {
        if (isBlocked(patientId)) {
            throw refusal(patientId);
        }
    }

    /** The refusal for a blocked chart: see {@link #requireReadable}. */
    @Transactional(readOnly = true)
    public RuntimeException refusal(UUID patientId) {
        HospitalContext context = HospitalContextHolder.getContextOrEmpty();
        if (context.isSuperAdmin()) {
            return new ChartRestrictedException(patientId);
        }
        for (UUID hospitalId : actingHospitals(context)) {
            if (registrationRepository.existsByPatientIdAndHospitalId(patientId, hospitalId)) {
                return new ChartRestrictedException(patientId);
            }
        }
        return new ResourceNotFoundException(MSG_PATIENT_NOT_FOUND, patientId);
    }

    /** The patient a key names: the id itself for a patient, the record's patient otherwise. */
    @Transactional(readOnly = true)
    public Optional<UUID> ownerOf(ChartKeys.Owner owner, UUID id) {
        if (owner == null || id == null) {
            return Optional.empty();
        }
        if (owner == ChartKeys.Owner.PATIENT) {
            return Optional.of(id);
        }
        List<UUID> found = entityManager.createQuery(OWNER_QUERIES.get(owner), UUID.class)
            .setParameter("id", id)
            .setMaxResults(1)
            .getResultList();
        return found.stream().findFirst();
    }

    private Set<UUID> restrictedAmong(Collection<UUID> patientIds) {
        Map<UUID, Boolean> cache = restrictionCache();
        List<UUID> unknown = new ArrayList<>();
        for (UUID id : new HashSet<>(patientIds)) {
            if (id != null && !cache.containsKey(id)) {
                unknown.add(id);
            }
        }
        if (unknown.size() == 1) {
            UUID only = unknown.get(0);
            cache.put(only, patientRepository.findChartRestrictedById(only).orElse(false));
        } else {
            for (int from = 0; from < unknown.size(); from += MAX_IN_LIST) {
                List<UUID> chunk = unknown.subList(from, Math.min(unknown.size(), from + MAX_IN_LIST));
                Set<UUID> restricted = new HashSet<>(patientRepository.findChartRestrictedIdsAmong(chunk));
                chunk.forEach(id -> cache.put(id, restricted.contains(id)));
            }
        }
        Set<UUID> restricted = new HashSet<>();
        for (UUID id : patientIds) {
            if (id != null && Boolean.TRUE.equals(cache.get(id))) {
                restricted.add(id);
            }
        }
        return restricted;
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, Boolean> restrictionCache() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return new HashMap<>();
        }
        Object cached = attrs.getAttribute(RESTRICTED_CACHE, RequestAttributes.SCOPE_REQUEST);
        if (cached instanceof Map<?, ?> map) {
            return (Map<UUID, Boolean>) map;
        }
        Map<UUID, Boolean> fresh = new HashMap<>();
        attrs.setAttribute(RESTRICTED_CACHE, fresh, RequestAttributes.SCOPE_REQUEST);
        return fresh;
    }

    /**
     * A live break-the-glass session of the caller's for this patient: at the
     * hospital the request is pinned to, or, with none pinned yet, at any
     * hospital the caller works at (any hospital for a super-admin).
     */
    private boolean holdsLiveSession(UUID patientId) {
        HospitalContext context = HospitalContextHolder.getContextOrEmpty();
        UUID actor = actorOf(context);
        if (actor == null) {
            return false;
        }
        UUID pinned = ActingScopeResolver.pinnedHospitalIdOf(context);
        if (pinned != null) {
            return breakGlassGate.liveSession(actor, patientId, pinned).isPresent();
        }
        Set<UUID> works = context.getStaffHospitalIds();
        for (BreakGlassSession session : sessionRepository.findLiveForUserAndPatient(actor, patientId,
                LocalDateTime.now(clock))) {
            if (session.getHospital() != null
                && (context.isSuperAdmin() || (works != null && works.contains(session.getHospital().getId())))) {
                return true;
            }
        }
        return false;
    }

    private static Collection<UUID> actingHospitals(HospitalContext context) {
        UUID pinned = ActingScopeResolver.pinnedHospitalIdOf(context);
        if (pinned != null) {
            return List.of(pinned);
        }
        return context.getStaffHospitalIds() != null ? context.getStaffHospitalIds() : Set.of();
    }

    private static UUID actorOf(HospitalContext context) {
        if (context.getPrincipalUserId() != null) {
            return context.getPrincipalUserId();
        }
        return PrincipalUserIds.of(SecurityContextHolder.getContext().getAuthentication()).orElse(null);
    }
}
