package com.example.hms.service;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.LabResultMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.LabResultComparisonDTO;
import com.example.hms.payload.dto.LabResultReferenceRangeDTO;
import com.example.hms.payload.dto.LabResultRequestDTO;
import com.example.hms.payload.dto.LabResultResponseDTO;
import com.example.hms.payload.dto.LabResultSignatureRequestDTO;
import com.example.hms.payload.dto.LabResultTrendPointDTO;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.utility.ElapsedTime;
import com.example.hms.service.lab.SupersededLabResults;
import com.example.hms.utility.RoleValidator;
import com.example.hms.utility.TransactionCallbacks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.example.hms.enums.AbnormalFlag;
import com.example.hms.enums.LabOrderStatus;
import com.example.hms.model.LabReflexRule;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.repository.LabReflexRuleRepository;
import com.example.hms.repository.LabTestDefinitionRepository;
import com.example.hms.service.lab.LabOrderLifecycle;

@Service
@RequiredArgsConstructor
public class LabResultServiceImpl implements LabResultService {
    private static final String ROLE_SUPER_ADMIN = "ROLE_SUPER_ADMIN";
    private static final String ROLE_LAB_DIRECTOR = "ROLE_LAB_DIRECTOR";
    private static final String UNKNOWN_CLINICIAN = "Unknown clinician";
    private static final String CRITICAL_FLAG = "CRITICAL";


    private static final String LAB_RESULT_NOT_FOUND = "labresult.notfound";
    private static final String LAB_ORDER_NOT_FOUND = "laborder.notfound";

    private static final Logger LOG = LoggerFactory.getLogger(LabResultServiceImpl.class);

    private final LabResultRepository labResultRepository;
    private final com.example.hms.service.lab.LabResultEntryGuard labResultEntryGuard;
    private final LabOrderRepository labOrderRepository;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final LabResultMapper labResultMapper;
    private final RoleValidator roleValidator;
    private final AuthService authService;
    private final UserRepository userRepository;
    private final InstrumentOutboxService instrumentOutboxService;
    private final LabReflexRuleRepository labReflexRuleRepository;
    private final LabTestDefinitionRepository labTestDefinitionRepository;
    private final CriticalValueNotificationService criticalValueNotificationService;
    private final com.example.hms.service.recordaccess.CrossHospitalReachRecorder reachRecorder;

    /** Ceiling on an accounted result page — see {@link com.example.hms.utility.PageBounds}. */
    private static final int MAX_RESULT_PAGE_SIZE = 500;

    /** The one description every performing-laboratory result disclosure carries (Sonar S1192). */
    private static final String PERFORMED_HERE_REACH_DESCRIPTION =
        "Cross-hospital lab result read at the performing laboratory";
    private final com.example.hms.service.lab.LabOrderRoutingNotifier routingNotifier;

    /**
     * Whether a normal-range result is released the moment it is saved, with
     * nobody attesting to it.
     *
     * <p><b>Default false, and that is deliberate (B5).</b> Release is the
     * laboratory saying "this number is correct and you may act on it"
     * ({@link com.example.hms.service.lab.LabResultAuthority#RELEASE_ROLES}
     * narrows who may say so). Auto-verification used to say it for every
     * non-abnormal result instantly, under the name "Autoverification", which
     * made the release gate decorative for the bulk of results. A hospital
     * whose analysers and delta checks justify auto-release turns this on with
     * {@code hms.lab.auto-verification.enabled=true}; until then a human
     * releases.
     */
    @Value("${hms.lab.auto-verification.enabled:false}")
    private boolean autoVerificationEnabled;

    /**
     * Whether an HL7 ingest caller with no resolvable hospital scope may skip
     * the tenancy and author checks. Off by default — the condition cannot
     * tell a service account from a person, so it waits for
     * fix/hl7-inbound-tenancy to resolve the hospital from the sending
     * facility.
     */
    @Value("${hms.lab.hl7-ingest.unscoped-exemption.enabled:false}")
    private boolean unscopedIngestExemptionEnabled;

    @Override
    @Transactional
    public LabResultResponseDTO createLabResult(LabResultRequestDTO request, Locale locale) {
        return createLabResult(request, false);
    }

    @Override
    @Transactional
    public LabResultResponseDTO createIngestedLabResult(LabResultRequestDTO request, Locale locale) {
        // The HL7 inbound adapter: the caller is an interface account posting
        // an ORU under a lab role, with no X-Hospital-Id and possibly no
        // assignment of its own. It is named explicitly rather than inferred
        // from "no scope resolves", which also fitted any ordinary staff user
        // holding two assignments who forgot the header.
        return createLabResult(request, true);
    }

    private LabResultResponseDTO createLabResult(LabResultRequestDTO request, boolean ingested) {
        // Read first, lock later. The write lock on the order is needed only
        // for the status decision further down, and taking it here held it
        // across the permission checks and — before the side effects moved
        // after the commit — across a blocking SMS gateway call.
        LabOrder labOrder = labOrderRepository.findById(request.getLabOrderId())
                .orElseThrow(() -> new ResourceNotFoundException(LAB_ORDER_NOT_FOUND));
        // An interface principal: the ingest door AND no hospital scope of its
        // own. Both halves are needed. Exempting the ENDPOINT would let a
        // multi-hospital lab user — or a HOSPITAL_ADMIN, who passes that
        // endpoint's @PreAuthorize — write into another tenant's order through
        // it; exempting "no scope resolves" alone would let any unscoped
        // interactive caller do the same.
        //
        // OFF BY DEFAULT, and that is the honest posture. "No resolvable
        // scope" is not proof of a machine: a lab-role human with no active
        // assignment, or with two and no X-Hospital-Id, satisfies it too, and
        // for them this waives BOTH the tenancy comparison and the author
        // check on an endpoint they can reach. Telling a service account from
        // a person needs the sending facility resolved to a hospital, which
        // is fix/hl7-inbound-tenancy; until that lands the exemption sits
        // behind hms.lab.hl7-ingest.unscoped-exemption.enabled, and with it
        // off an unscoped ingest caller is refused exactly as before #721.
        boolean interfacePrincipal = ingested && unscopedIngestExemptionEnabled && !hasResolvableHospitalScope();

        // Same 404-not-403 tenancy comparison as every other single-row path
        // here (B11, on B1's ordering-or-performing predicate): a hospital on
        // neither side must not learn the order exists, let alone attach a
        // result to it.
        if (!interfacePrincipal) {
            requireOrderInActiveHospital(labOrder);
        }

        Hospital hospital = extractHospitalFromLabOrder(labOrder);
        // requireActiveHospitalId THROWS when nothing resolves, which is an
        // interface account's normal state; a null acting hospital then means
        // "no scope to judge against", which the helpers below already handle.
        UUID actingHospitalId = interfacePrincipal ? null : roleValidator.requireActiveHospitalId();

        // Who may record THIS test's result. Role alone cannot answer it: a
        // nurse recording a bedside glucose is doing their job, and the same
        // nurse typing in a chemistry panel is not. The gate reads the test's
        // point-of-care flag, which is why it lives here rather than in the
        // controller annotation — the annotation runs before the order and
        // its test are loaded.
        labResultEntryGuard.requireMayEnterResult(labOrder.getLabTestDefinition());

    UUID currentUserId = authService.getCurrentUserId();
    // Skipped for an interface account on the same narrow condition as the
    // tenancy check: it holds no role at any hospital, which is the premise
    // of the ingest path. HOSPITAL_ADMIN passes that endpoint's @PreAuthorize
    // but is not in the author allow-list, and is a scoped principal, so it
    // is still judged here.
    if (!interfacePrincipal) {
        validateLabResultAuthor(currentUserId, authorityHospitalId(labOrder, hospital, actingHospitalId));
    }

        // An interface principal has no acting hospital, so the acting-hospital
        // comparison below would wave any tenant's assignment through and put
        // that staff member's name on the result and in the response. The
        // order is the anchor instead: the assignment must belong to a
        // hospital that handles it (ordering or performing, B1's predicate).
        UserRoleHospitalAssignment assignment = interfacePrincipal
            ? requireAssignmentHandlingOrder(request.getAssignmentId(), labOrder)
            : requireAssignmentAtActingHospital(request.getAssignmentId(), actingHospitalId);

        // From here to the status write the order row is LOCKED. The
        // duplicate check belongs inside it: run before the lock, two
        // concurrent retries both saw no existing row and both inserted —
        // precisely the double record this check exists to prevent.
        LabOrder lockedOrder = labOrderRepository.findWithLockById(labOrder.getId()).orElse(labOrder);
        // The locking finder hands back the instance this persistence context
        // loaded above, unlocked, and Hibernate does not refresh its fields,
        // so the entity's status can predate the lock. The committed value
        // comes from a scalar projection, which is not served from the
        // first-level cache.
        LabOrderStatus committedStatus = labOrderRepository.findStatusById(lockedOrder.getId());

        // A retried post records a second result, deliberately. Detecting the
        // retry meant comparing the fields a request carries, and the portal's
        // entry form carries too few to tell two results apart: no analyte
        // code, a minute-precision result date, usually blank notes. Two
        // analytes of one order entered in the same minute with the same value
        // and unit therefore collapsed, and the endpoint answered 201 with
        // somebody else's row — a result silently lost. A duplicate row is
        // visible, correctable and moves the order to RESULTED, which the
        // compare-and-set below handles and a release corrects; a dropped
        // result is none of those things.
        // Replay protection, ingest only. The interactive path stays
        // dedup-free on purpose (its form cannot describe a result precisely
        // enough to tell a retry from a second analyte), but the HL7 adapter
        // is the one caller that genuinely retransmits, and a retransmission
        // reuses MSH-3, MSH-4 and MSH-10 — the triple the MLLP path already
        // recognises. Without this a resent message doubled the result, the
        // critical alert, the SMS and the outbound message.
        if (ingested) {
            java.util.Optional<LabResult> alreadyRecorded = findRecordedMessage(request);
            if (alreadyRecorded.isPresent()) {
                LabResult existing = alreadyRecorded.get();
                // Ids and nothing else. The control id, the sending
                // application and the sending facility all come off the
                // message, so a caller could put newlines and control
                // characters in them and forge log entries; the result id
                // identifies the row just as well and cannot be authored.
                LOG.info("An HL7 ORU that was already recorded as result {} arrived again; not recorded twice",
                    existing.getId());
                initialiseTestDefinition(existing.getLabOrder());
                return labResultMapper.toResponseDTO(existing);
            }
        }

        LabResult result = labResultMapper.toEntity(request, labOrder, assignment);
        if (!ingested) {
            // Ingest-only fields: a hand-entered result is nobody's
            // preliminary and arrived on no message, whatever a client sent.
            result.setTestCode(null);
            result.setSourceSendingApplication(null);
            result.setSourceSendingFacility(null);
            result.setSourceMessageControlId(null);
        }
        LabResult saved = labResultRepository.save(result);

        // An entered result IS the order's RESULTED state (B2). Nothing else
        // advanced the order, so released results never reached the ordering
        // doctor's review queue, which keys on COMPLETED. A result landing on
        // a COMPLETED order (a correction, a late analyte) re-opens it: the
        // doctor must see the order as having something new to review.
        //
        // Written as a compare-and-set STATEMENT, not through the entity: the
        // instance's snapshot predates the lock, and when the target equals
        // that snapshot value (snapshot RESULTED, database COMPLETED, target
        // RESULTED) the dirty check sees no change and flushes nothing — the
        // re-open vanished and the order stayed COMPLETED.
        moveStatus(lockedOrder.getId(), committedStatus,
            LabOrderLifecycle.statusAfterNewResult(committedStatus));
        // One severity for both decisions below. The REST path never sets
        // abnormalFlag (no DTO field; only MLLP populates it), so gating
        // auto-release on the flag alone released critical manual results.
        String severity = severityOf(saved);
        performAutoVerification(saved, severity);
        if (saved.isReleased()) {
            completeOrderIfAllReleased(lockedOrder);
        }
        triggerReflexOrders(saved);

        // P0 #5 — critical values must reach the ordering provider. This runs
        // IN this transaction: the alert row and the criticalNotifiedAt stamp
        // are local writes, so they commit with the result and no restart can
        // lose them. The service defers only its SMS, which is the blocking
        // network hop.
        criticalValueNotificationService.notifyIfCritical(saved, severity);

        // The outbound message is queued IN this transaction too. It was
        // deferred to after the commit for a moment, which quietly made it
        // losable: a crash or a failed REQUIRES_NEW between commit and
        // callback drops it for good, because the dispatcher only sends rows
        // that exist and nothing re-derives them from the result. Enqueuing
        // here is the outbox pattern as intended — row and result commit
        // together or not at all — and it costs nothing to hold: building an
        // ORU message is local work, not a network hop. (The SMS is the
        // network hop, and that is what waits for the commit.) Reconciliation
        // was the alternative; a periodic job that re-derives missing rows is
        // more moving parts for a guarantee one transaction already gives.
        instrumentOutboxService.enqueueResultObservation(saved);

        return labResultMapper.toResponseDTO(saved);
    }

    /**
     * The mapper's reference-range verdict (NORMAL / LOW / HIGH / UNSPECIFIED),
     * or null when it cannot map.
     *
     * <p>The mapper answers UNSPECIFIED for a test definition it finds
     * uninitialised, and the order's definition is LAZY: on a laboratory
     * role's entry nothing has touched it yet at this point (the entry guard
     * reads it only for bedside roles), so without the explicit initialise a
     * potassium of 50 read as "unspecified" here and as HIGH in the response.
     */
    private String severityOf(LabResult result) {
        initialiseTestDefinition(result.getLabOrder());
        LabResultResponseDTO dto = labResultMapper.toResponseDTO(result);
        return dto != null ? dto.getSeverityFlag() : null;
    }

    /**
     * The result this exact message already produced, if it has.
     *
     * <p>Scoped to the order in the request, and that scope is the whole
     * security of it. The three message values come off the request body
     * here — unlike the MLLP path, which has resolved the sending analyzer
     * to a hospital before it asks — so an unscoped lookup let a caller
     * write an MSH copying another hospital's analyzer, facility and control
     * id, match that hospital's row, and be handed it back in full, patient
     * name and value included. Inside one order there is nothing to leak:
     * the caller's own tenancy check already covers it.
     *
     * <p>The composite is still what HL7 v2 guarantees — MSH-10 is unique
     * only within a sending system, so two analyzers may legitimately emit
     * the same control id — and the partial unique index from V98 still
     * backs it.
     */
    private java.util.Optional<LabResult> findRecordedMessage(LabResultRequestDTO request) {
        if (request.getSourceMessageControlId() == null
                || request.getSourceMessageControlId().isBlank()) {
            return java.util.Optional.empty();
        }
        return labResultRepository
            .findFirstByLabOrder_IdAndSourceSendingApplicationAndSourceSendingFacilityAndSourceMessageControlId(
                request.getLabOrderId(),
                request.getSourceSendingApplication(),
                request.getSourceSendingFacility(),
                request.getSourceMessageControlId());
    }

    /**
     * The mapper reads the order's test definition for the test name and code,
     * the reference ranges and the severity it derives from them, and treats
     * an uninitialised one as absent — so anything mapping a result has to
     * touch it first while the session is open.
     */
    private void initialiseTestDefinition(LabOrder labOrder) {
        if (labOrder != null && labOrder.getLabTestDefinition() != null) {
            org.hibernate.Hibernate.initialize(labOrder.getLabTestDefinition());
        }
    }

    /**
     * The ONLY writer of a lab order's status on this service's paths.
     *
     * <p>A compare-and-set statement, never a field on a managed entity. Two
     * reasons, both learned the hard way in this PR. The instance these paths
     * hold was loaded before the row was locked, so its snapshot predates the
     * lock: writing through it silently flushes nothing when the target
     * happens to equal the snapshot value, and flushes a full-row UPDATE
     * otherwise — reverting whatever another transaction changed in any other
     * column meanwhile. {@code expected} is the status read under the lock, so
     * a row somebody else moved is reported, not overwritten.
     */
    private void moveStatus(UUID orderId, LabOrderStatus expected, LabOrderStatus target) {
        if (orderId == null || target == null || target == expected) {
            return;
        }
        int moved = labOrderRepository.updateStatusFrom(orderId, expected, target);
        if (moved == 0) {
            LOG.info("Lab order {} moved from {} while its result was being recorded; status left alone",
                orderId, expected);
        }
    }

    /**
     * The order is COMPLETED once every one of its results is released — the
     * point at which the ordering doctor's review queue picks it up.
     *
     * <p>"Every result" means every result that exists when the release
     * lands. A LabOrder names exactly one {@code LabTestDefinition}
     * ({@code @ManyToOne}), so the common case is one result per order;
     * reflex tests are separate child orders. A result that arrives later
     * (a correction, an extra analyte) re-opens the order to RESULTED in
     * {@code createLabResult}, so completing early is never final.
     *
     * <p>The order row is locked ({@code PESSIMISTIC_WRITE}) first, which
     * serialises two concurrent releases of the last two results; what then
     * fixes the race is the RE-QUERY of the results underneath that lock —
     * the second release re-reads them after the first has committed and sees
     * its sibling released. (The lock alone would not: the locking finder
     * returns the order instance this persistence context already has, with
     * the field values it was loaded with.)
     *
     * <p>Because that instance can be stale, the committed status is read
     * under the lock before deciding. A cancellation that landed while this
     * transaction worked is a decision somebody made, and completing over it
     * would erase it.
     */
    private void completeOrderIfAllReleased(LabOrder labOrder) {
        if (labOrder == null || labOrder.getId() == null) {
            return;
        }
        labOrderRepository.findWithLockById(labOrder.getId());
        UUID orderId = labOrder.getId();
        // Everything below decides from the status the DATABASE holds, read
        // under that lock. Deciding from the loaded instance meant a
        // concurrent re-open was invisible: the order was advanced from a
        // stale value and stranded with every result released and nothing
        // left to move it.
        LabOrderStatus committedStatus = labOrderRepository.findStatusById(orderId);
        if (committedStatus == LabOrderStatus.CANCELLED) {
            LOG.debug("Lab order {} was cancelled while its result was being released; not completing",
                orderId);
            return;
        }
        List<LabResult> results = labResultRepository.findByLabOrder_Id(orderId);
        // #720's rule, kept: a preliminary row the lab has since finalised and
        // released is not work still outstanding — it is a record of what the
        // analyzer said first. Left counted, it would hold the order open for
        // ever, since nobody will ever release a superseded preliminary.
        Set<UUID> superseded = SupersededLabResults.supersededRowIds(results, results);
        boolean nothingOutstanding = results.stream()
            .allMatch(result -> result.isReleased() || superseded.contains(result.getId()));
        if (!results.isEmpty() && nothingOutstanding) {
            // ...and #721's writer: the status is written by the compare-and-set
            // statement, decided on the value committed under the lock.
            moveStatus(orderId, committedStatus,
                LabOrderLifecycle.statusAfterAllResultsReleased(committedStatus));
        }
    }

    /**
     * Can a hospital scope be resolved for this caller at all?
     *
     * <p>Mirrors the branches {@code requireActiveHospitalId} takes before it
     * gives up and throws: a real super-admin, an explicit {@code
     * X-Hospital-Id} context, or a single active assignment. False means an
     * interface principal — nothing to compare an order against. It is one
     * half of the ingest exemption, never the whole of it.
     */
    private boolean hasResolvableHospitalScope() {
        com.example.hms.security.context.HospitalContext ctx =
            com.example.hms.security.context.HospitalContextHolder.getContextOrEmpty();
        return ctx.isSuperAdmin()
            || ctx.getActiveHospitalId() != null
            || roleValidator.isSuperAdminFromAuth()
            || roleValidator.getCurrentHospitalId() != null;
    }

    /**
     * The 404-not-403 tenancy comparison for the order a result hangs off
     * (B11), on B1's predicate: the ordering hospital and the laboratory
     * performing the order both handle it. Comparing on the ordering hospital
     * alone would refuse the performing laboratory the very result paths B1
     * exists to open. A third hospital must not learn the order exists, let
     * alone attach a result to it.
     */
    private void requireOrderInActiveHospital(LabOrder labOrder) {
        if (!labOrder.isHandledBy(roleValidator.requireActiveHospitalId())) {
            throw new ResourceNotFoundException(LAB_ORDER_NOT_FOUND);
        }
    }

    /**
     * Whether this row reached us from an external analyzer, and so must not
     * be transmitted back to one.
     *
     * <p>Shares {@link SupersededLabResults#cameFromAnAnalyzer}, which asks the
     * same question for a different purpose — which rows the pre-V164 pairing
     * fallback may govern. One predicate, so a sender that identifies itself
     * in an unusual way cannot be an analyzer to one of them and ours to the
     * other.
     */
    private static boolean wasIngestedFromAnAnalyzer(LabResult labResult) {
        return SupersededLabResults.cameFromAnAnalyzer(labResult);
    }

    @Override
    @Transactional(readOnly = true)
    public LabResultResponseDTO getLabResultById(UUID id, Locale locale) {
        LabResult labResult = labResultRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(LAB_RESULT_NOT_FOUND));

        requireResultInActiveHospital(labResult);
        recordPerformedHereReach(List.of(labResult));

        LabResultResponseDTO response = labResultMapper.toResponseDTO(labResult);
        response.setTrendHistory(buildTrendHistory(labResult));
        return response;
    }

    @Override
    @Transactional(readOnly = true)
    public List<LabResultResponseDTO> getAllLabResults(Locale locale) {
        UUID currentUserId = authService.getCurrentUserId();

        if (authService.hasRole(ROLE_SUPER_ADMIN)) {
            return labResultRepository.findAll().stream()
                .map(labResultMapper::toResponseDTO)
                .toList();
        }

        List<UserRoleHospitalAssignment> assignments = assignmentRepository.findByUser_IdAndActiveTrue(currentUserId);
        if (assignments.isEmpty()) {
            return List.of();
        }

        Set<UUID> hospitalIds = assignments.stream()
            .map(UserRoleHospitalAssignment::getHospital)
            .filter(Objects::nonNull)
            .map(Hospital::getId)
            .collect(Collectors.toSet());

        if (hospitalIds.isEmpty()) {
            return List.of();
        }

        List<LabResult> results = labResultRepository.findHandledByHospitals(hospitalIds);
        recordPerformedHereReach(results);
        return results.stream()
            .map(labResultMapper::toResponseDTO)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<LabResultResponseDTO> getLabResultsPage(Pageable pageable, Locale locale) {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId == null) {
            // Super-admin: return all paged
            return labResultRepository.findAll(pageable)
                .map(labResultMapper::toResponseDTO);
        }
        Page<LabResult> page = labResultRepository.findHandledByHospital(hospitalId,
            com.example.hms.utility.PageBounds.atMost(pageable, MAX_RESULT_PAGE_SIZE));
        recordPerformedHereReach(page.getContent());
        return page.map(labResultMapper::toResponseDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<LabResultResponseDTO> getPendingRelease(Pageable pageable, Locale locale) {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId == null) {
            // A release worklist is one hospital's queue; the global view has none.
            throw new BusinessException("A hospital scope is required for the release worklist.");
        }
        // B1: a laboratory releases what it ran, so an order another hospital
        // sent here belongs on this queue — releasing it is exactly what the
        // performing laboratory is for. Unlike every other lab-side read this
        // one is NOT the ordering-OR-performing predicate: an outsourced
        // result waits on the performing laboratory's queue alone, because
        // putting it on both invited the ordering hospital to sign off work
        // its laboratory never did.
        Page<LabResult> page = labResultRepository.findPendingReleaseHandledBy(hospitalId,
            com.example.hms.utility.PageBounds.atMost(pageable, MAX_RESULT_PAGE_SIZE));
        recordPerformedHereReach(page.getContent());
        return page.map(labResultMapper::toResponseDTO);
    }

    @Override
    @Transactional
    public LabResultResponseDTO updateLabResult(UUID id, LabResultRequestDTO request, Locale locale) {
    LabResult labResult = labResultRepository.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException(LAB_RESULT_NOT_FOUND));

        // Hospital scope enforcement
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        requireResultInActiveHospital(labResult);

        // Amending a result is entering one. Same gate — otherwise a bedside
        // role blocked from creating a lab-performed result could simply
        // overwrite an existing one instead.
        labResultEntryGuard.requireMayEnterResult(
            labResult.getLabOrder() != null ? labResult.getLabOrder().getLabTestDefinition() : null);

        LabOrder labOrder = labOrderRepository.findById(request.getLabOrderId())
                .orElseThrow(() -> new ResourceNotFoundException(LAB_ORDER_NOT_FOUND));

        Hospital hospital = extractHospitalFromLabOrder(labOrder);
        if (!labOrder.isHandledBy(activeHospitalId)) {
            throw new ResourceNotFoundException("laborder.notfound");
        }
    UUID currentUserId = authService.getCurrentUserId();
    validateLabResultAuthor(currentUserId, authorityHospitalId(labOrder, hospital, activeHospitalId));

        UserRoleHospitalAssignment assignment =
            requireAssignmentAtActingHospital(request.getAssignmentId(), activeHospitalId);

        labResult.setLabOrder(labOrder);
        labResult.setResultValue(request.getResultValue());
        labResult.setResultUnit(request.getResultUnit());
        labResult.setResultDate(request.getResultDate());
        labResult.setNotes(request.getNotes());
        labResult.setAssignment(assignment);

        LabResult updated = labResultRepository.save(labResult);

        return labResultMapper.toResponseDTO(updated);
    }

    @Override
    public void deleteLabResult(UUID id, Locale locale) {
        LabResult labResult = labResultRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(LAB_RESULT_NOT_FOUND));
        requireResultInActiveHospital(labResult);
        labResultRepository.deleteById(id);
    }

    @Override
    @Transactional
    public void acknowledgeLabResult(UUID id, Locale locale) {
        UUID currentUserId = authService.getCurrentUserId();
        // Unknown ids used to be silently swallowed to accommodate the
        // synthetic pending-review rows; those are gone, so a missing
        // result is a real 404 again.
        LabResult labResult = labResultRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(LAB_RESULT_NOT_FOUND));
        // Tenant guard (the getLabResultById idiom, 404-not-403): this was
        // the one write path with no scope check — a foreign tenant could
        // acknowledge, and thereby silence, another hospital's critical
        // result by guessing the UUID.
        requireResultInActiveHospital(labResult);
        acknowledgeResult(labResult, currentUserId);
    }

    @Override
    @Transactional
    public LabResultResponseDTO recordCriticalReadBack(
            UUID id,
            com.example.hms.payload.dto.CriticalValueReadBackRequestDTO request,
            Locale locale) {
        LabResult labResult = labResultRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(LAB_RESULT_NOT_FOUND));
        // Tenant guard: worse than acknowledge — the response DTO carries
        // patient name + value, so this was a cross-tenant PHI read as
        // well as a write, and a matching read-back removes the row from
        // the escalation sweep permanently.
        requireResultInActiveHospital(labResult);

        UUID actorId = authService.getCurrentUserId();
        LabResult updated = criticalValueNotificationService.recordReadBack(
            labResult, request.getRepeatedValue(), actorId, resolveActorDisplay(actorId));
        return labResultMapper.toResponseDTO(updated);
    }

    /**
     * The shared 404-not-403 tenancy comparison used by every other
     * single-row path in this class (get/update/delete/compare). LabResult
     * has no hospital column of its own — scope flows through
     * labOrder.hospital. Null active scope = super-admin, unscoped.
     */
    private void requireResultInActiveHospital(LabResult labResult) {
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        // B1: the order is handled by its ordering hospital and by the
        // laboratory performing it (LabOrder.isHandledBy); a third hospital
        // gets the same 404 as before.
        if (activeHospitalId != null && labResult.getLabOrder() != null
                && !labResult.getLabOrder().isHandledBy(activeHospitalId)) {
            throw new ResourceNotFoundException(LAB_RESULT_NOT_FOUND);
        }
    }

    /**
     * The ingest path's assignment check: no acting hospital to compare
     * against, so the order supplies the anchor.
     *
     * <p>Without this, an ingest caller could name an assignment at any
     * tenant and that staff member's name would be recorded as the author of
     * the result and returned in the response. 404 rather than 403, like
     * every other tenancy answer here.
     */
    private UserRoleHospitalAssignment requireAssignmentHandlingOrder(UUID assignmentId, LabOrder labOrder) {
        UserRoleHospitalAssignment assignment = assignmentRepository.findById(assignmentId)
            .orElseThrow(() -> new ResourceNotFoundException("assignment.notfound"));
        UUID assignmentHospitalId = assignment.getHospital() != null
            ? assignment.getHospital().getId() : null;
        if (assignmentHospitalId == null || !labOrder.isHandledBy(assignmentHospitalId)) {
            throw new ResourceNotFoundException("assignment.notfound");
        }
        return assignment;
    }

    /**
     * The assignment a result is attributed to must belong to the hospital
     * the author is acting at.
     *
     * <p>Nothing checked this: the id came straight off the request and
     * {@code LabResult.validate()} was the only gate, which asks merely that
     * the assignment's hospital handles the order — and B1 widened "handles"
     * to two hospitals. A scientist at the performing laboratory could
     * therefore attribute a result to a named staff member at the ordering
     * hospital and read that person's name back out of the response. 404
     * rather than 403: another hospital's assignment is not this caller's to
     * learn about.
     */
    private UserRoleHospitalAssignment requireAssignmentAtActingHospital(UUID assignmentId, UUID actingHospitalId) {
        UserRoleHospitalAssignment assignment = assignmentRepository.findById(assignmentId)
            .orElseThrow(() -> new ResourceNotFoundException("assignment.notfound"));
        if (actingHospitalId != null
                && (assignment.getHospital() == null
                    || !actingHospitalId.equals(assignment.getHospital().getId()))) {
            throw new ResourceNotFoundException("assignment.notfound");
        }
        return assignment;
    }

    /**
     * B1 + E8: a result of an order this hospital's laboratory performs
     * belongs to the hospital that ordered it, so surfacing it here is a
     * cross-hospital disclosure — the same conclusion the order reads reached,
     * one layer down. The reads were widened by the same predicate change and
     * accounted nothing, which left the result routes disclosing silently
     * while the order routes recorded.
     *
     * <p>Never throws: accounting a read must not fail it.
     */
    private void recordPerformedHereReach(java.util.Collection<LabResult> results) {
        UUID actingHospitalId = roleValidator.requireActiveHospitalId();
        if (actingHospitalId == null || results.isEmpty()) {
            return;
        }
        try {
            java.util.Map<UUID, java.util.Map<String, Long>> perPatient = new java.util.HashMap<>();
            for (LabResult result : results) {
                LabOrder order = result.getLabOrder();
                if (order != null && order.isPerformedAt(actingHospitalId)) {
                    UUID patientId = order.getPatient() != null ? order.getPatient().getId() : null;
                    UUID source = com.example.hms.service.recordaccess.CrossHospitalReachRecorder
                        .hospitalIdOf(order.getHospital());
                    if (patientId != null && source != null) {
                        perPatient.computeIfAbsent(patientId, key -> new java.util.HashMap<>())
                            .merge(source.toString(), 1L, Long::sum);
                    }
                }
            }
            if (perPatient.isEmpty()) {
                return;
            }
            reachRecorder.recordBatchedReach(perPatient, actingHospitalId,
                authService.getCurrentUserId(), null, PERFORMED_HERE_REACH_DESCRIPTION);
        } catch (RuntimeException ex) {
            LOG.warn("Cross-hospital disclosure accounting failed for a performing-laboratory result read at {}: {}",
                actingHospitalId, ex.getMessage());
        }
    }

    /**
     * B1: the hospital whose laboratory runs the order — the one it was sent
     * to, else the one that ordered it. Releasing a result is that
     * laboratory's attestation of its own work, so it is the running
     * hospital's roles that authorise a release and the running hospital's
     * queue the result waits on. Signing is not a release: it is the
     * receiving clinician taking the result into the chart, and either
     * hospital's clinicians may do it (see {@code signLabResult}).
     */
    private static UUID runningHospitalId(LabOrder labOrder, Hospital orderingHospital) {
        UUID running = labOrder != null ? labOrder.resolvePerformingHospitalId() : null;
        if (running != null) {
            return running;
        }
        return orderingHospital != null ? orderingHospital.getId() : null;
    }

    /**
     * B1: the hospital whose roles authorise a lab-side write. An actor working
     * at the performing laboratory is judged by their roles THERE; everybody
     * else (the ordering hospital, a super-admin in global view) by the
     * ordering hospital's, exactly as before.
     */
    private static UUID authorityHospitalId(LabOrder labOrder, Hospital orderingHospital, UUID actingHospitalId) {
        if (actingHospitalId != null && labOrder != null && labOrder.isPerformedAt(actingHospitalId)) {
            return actingHospitalId;
        }
        return orderingHospital != null ? orderingHospital.getId() : null;
    }

    /**
     * Best-effort human name for the read-back record. A missing display name
     * must not block a clinician confirming a critical value.
     */
    private String resolveActorDisplay(UUID actorId) {
        if (actorId == null) {
            return null;
        }
        try {
            return userRepository.findById(actorId)
                .map(u -> {
                    String first = u.getFirstName() == null ? "" : u.getFirstName();
                    String last = u.getLastName() == null ? "" : u.getLastName();
                    String full = (first + " " + last).trim();
                    return full.isEmpty() ? u.getUsername() : full;
                })
                .orElse(null);
        } catch (RuntimeException ex) {
            LOG.warn("Could not resolve display name for read-back actor {}: {}", actorId, ex.getMessage());
            return null;
        }
    }

    @Override
    @Transactional
    public LabResultResponseDTO releaseLabResult(UUID id, Locale locale) {
        LabResult labResult = labResultRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(LAB_RESULT_NOT_FOUND));
        // Release was the one write path here without the scope comparison
        // (B11); it runs before the role check so a foreign tenant — including
        // a super-admin pinned to another hospital — sees 404, not 403.
        requireResultInActiveHospital(labResult);

        Hospital hospital = extractHospitalFromLabOrder(labResult.getLabOrder());
        // B1: releasing is the running laboratory's sign-off. For an order
        // sent out, the ordering hospital reads the result and acts on it but
        // does not release it — its lab staff were being offered the sign-off
        // on work their laboratory never did.
        UUID hospitalId = runningHospitalId(labResult.getLabOrder(), hospital);
        UUID actorId = authService.getCurrentUserId();

        validateReleasePermissions(actorId, hospitalId, roleValidator.requireActiveHospitalId());

        if (labResult.isReleased()) {
            // Not a no-op: a result released by a path that does not touch the
            // order (MLLP inbound, or a release from before this lifecycle
            // existed) leaves the order short of COMPLETED with nothing to
            // repair it. Re-releasing is the repair. The call is idempotent.
            completeOrderIfAllReleased(labResult.getLabOrder());
            return labResultMapper.toResponseDTO(labResult);
        }

        labResult.setReleased(true);
        labResult.setReleasedAt(LocalDateTime.now());
        labResult.setReleasedByUserId(actorId);
        labResult.setReleasedByDisplay(resolveActorDisplay(actorId, hospitalId));

        labResultRepository.save(labResult);
        // Both halves of this line's history: #716 closes the order once every
        // result on it is released, and the release then transmits its final
        // form. Domain write first — the message is the consequence, and it is
        // registered for after this transaction commits.
        completeOrderIfAllReleased(labResult.getLabOrder());
        enqueueReleasedObservationAfterCommit(labResult);
        return labResultMapper.toResponseDTO(labResult);
    }

    /**
     * The ORU enqueued at creation went out as preliminary (OBX-11 P), because
     * that is what an unreleased result is; without a second message a receiver
     * would hold that preliminary for ever.
     *
     * <p>Two conditions, both learned the hard way:
     *
     * <p>Only for a result WE created. A result INGESTED from an analyzer
     * (MLLP ORU^R01) never had a first message from us, so enqueuing one on
     * release would transmit an unsolicited result back to the instrument
     * peers — carrying OBR-2 = our internal order UUID, which is not the
     * accession number the analyzer knows the order by.
     *
     * <p>The signal is the row's own provenance, not the order's. Asking
     * whether an ORU had ever gone out for the ORDER was too coarse: an order
     * holding both a hand-entered result and an ingested one answered yes, and
     * releasing the ingested one then sent exactly the unsolicited message with
     * the internal identifier that this guard exists to prevent. An ingested
     * row carries the sending system's message control id; a row we created
     * carries none.
     *
     * <p>And after commit, in its own transaction, with the id only. An enqueue
     * inside this transaction is inserted and validated at commit, so its
     * try/catch catches nothing and an outbox failure would roll back the
     * release — the clinical write — for the sake of a message.
     */
    private void enqueueReleasedObservationAfterCommit(LabResult labResult) {
        if (wasIngestedFromAnAnalyzer(labResult)) {
            LOG.debug("Release of result {} not transmitted — it was ingested from {}, not sent by us",
                labResult.getId(), labResult.getSourceSendingApplication());
            return;
        }
        UUID labResultId = labResult.getId();
        // The catch belongs HERE, outside the REQUIRES_NEW proxy: the enqueue's
        // INSERT is validated and written when that inner transaction commits,
        // so a catch inside the service method never sees the failure. And an
        // exception escaping an afterCommit callback propagates to whoever
        // committed — the endpoint would answer 500 for a release that DID
        // happen, and the retry would hit the already-released early return and
        // never enqueue anything. The release is the clinical write; the
        // message is not.
        TransactionCallbacks.afterCommit(() -> {
            try {
                instrumentOutboxService.enqueueReleasedObservation(labResultId);
            } catch (RuntimeException ex) {
                LOG.error("Released ORU^R01 not enqueued for result {}: {}",
                    labResultId, ex.getMessage(), ex);
            }
        });
    }

    @Override
    @Transactional
    public LabResultResponseDTO signLabResult(UUID id, LabResultSignatureRequestDTO request, Locale locale) {
        LabResult labResult = labResultRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(LAB_RESULT_NOT_FOUND));
        requireResultInActiveHospital(labResult);

        Hospital hospital = extractHospitalFromLabOrder(labResult.getLabOrder());
        // B1: signing and releasing are different acts, and only releasing is
        // the laboratory's. Three things say so: this endpoint admits DOCTOR
        // and MIDWIFE where /release admits lab roles only, its own summary
        // calls it "a clinician signature", and signing auto-acknowledges —
        // and acknowledging is the ORDERING CLINICIAN confirming receipt (see
        // below). Restricting it to the running laboratory removed the
        // attending doctor's signature from every outsourced order, which is
        // exactly the person who has to take that result into the chart. So
        // the actor is judged at the hospital they act at, either side of the
        // relationship; a third hospital never reaches here (404 above).
        UUID hospitalId = authorityHospitalId(labResult.getLabOrder(), hospital,
            roleValidator.requireActiveHospitalId());
        UUID actorId = authService.getCurrentUserId();

        validateSignPermissions(actorId, hospitalId);

        String actorDisplay = resolveActorDisplay(actorId, hospitalId);
        LocalDateTime now = LocalDateTime.now();

        labResult.setSignedAt(now);
        labResult.setSignedByUserId(actorId);
        labResult.setSignedByDisplay(actorDisplay);
        labResult.setSignatureValue(normalizeSignatureValue(request));
        labResult.setSignatureNotes(normalizeSignatureNotes(request));

        // Signing and acknowledging are both the receiving clinician's, and
        // this endpoint does the two together. Folding them is mostly a
        // harmless convenience — except on a critical result, where the
        // auto-acknowledge would silence the escalation sweep with no read-back
        // ever recorded, bypassing the guard on the acknowledge path. A signed
        // critical result therefore stays unacknowledged (and the sweep keeps
        // chasing) until someone reads the value back.
        if (labResult.getCriticalNotifiedAt() == null || labResult.getCriticalReadBackAt() != null) {
            labResult.setAcknowledged(true);
            labResult.setAcknowledgedAt(now);
            labResult.setAcknowledgedByUserId(actorId);
            labResult.setAcknowledgedByDisplay(actorDisplay);
        }

        labResultRepository.save(labResult);
        return labResultMapper.toResponseDTO(labResult);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LabResultResponseDTO> getLabResultsByLabOrderId(UUID labOrderId, Locale locale) {
    return labResultRepository.findByLabOrder_Id(labOrderId).stream()
        .map(labResultMapper::toResponseDTO)
        .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<LabResultResponseDTO> getLabResultsByPatientId(UUID patientId, Locale locale) {
    return labResultRepository.findByLabOrder_Patient_Id(patientId).stream()
        .map(labResultMapper::toResponseDTO)
        .toList();
    }

    private Hospital extractHospitalFromLabOrder(LabOrder labOrder) {
        if (labOrder.getHospital() != null) {
            return labOrder.getHospital();
        }

        if (labOrder.getEncounter() != null && labOrder.getEncounter().getHospital() != null) {
            return labOrder.getEncounter().getHospital();
        } else if (labOrder.getPatient().getPrimaryHospital() != null) {
            return labOrder.getPatient().getPrimaryHospital();
        } else {
            throw new ResourceNotFoundException("hospital.notfound");
        }
    }

    private void validateReleasePermissions(UUID userId, UUID runningHospitalId, UUID actingHospitalId) {
        if (userId == null) {
            throw new BusinessException("Unable to determine current user for release operation.");
        }
        if (authService.hasRole(ROLE_SUPER_ADMIN)) {
            return;
        }
        if (runningHospitalId == null) {
            throw new BusinessException("Unable to determine hospital context for lab result release.");
        }
        // B1: an order sent to another laboratory is released there. The
        // ordering hospital still reads the result — this is not a 404 — but
        // it does not sign off work it did not do.
        if (actingHospitalId != null && !actingHospitalId.equals(runningHospitalId)) {
            throw new BusinessException(
                "Only the laboratory performing this order may release its results.");
        }
        UUID hospitalId = runningHospitalId;

        // LabResultAuthority.RELEASE_ROLES, checked against the caller's
        // assignment at THIS hospital (B10). This list used to admit doctors,
        // nurses, midwives and hospital admins — exactly the roles the
        // annotation had already shut out, so a doctor holding a lab role at
        // some other hospital could still release here.
        boolean allowed = roleValidator.isLabScientist(userId, hospitalId)
            || roleValidator.isLabManager(userId, hospitalId)
            || roleValidator.hasRole(userId, hospitalId, ROLE_LAB_DIRECTOR);

        if (!allowed) {
            throw new BusinessException("Only laboratory scientists, managers or directors can release lab results.");
        }
    }

    private void validateSignPermissions(UUID userId, UUID hospitalId) {
        if (userId == null) {
            throw new BusinessException("Unable to determine current user for sign operation.");
        }
        if (authService.hasRole(ROLE_SUPER_ADMIN)) {
            return;
        }
        if (hospitalId == null) {
            throw new BusinessException("Unable to determine hospital context for lab result sign-off.");
        }

        boolean allowed = roleValidator.isDoctor(userId, hospitalId)
            || roleValidator.isMidwife(userId, hospitalId)
            || roleValidator.isLabScientist(userId, hospitalId);

        if (!allowed) {
            throw new BusinessException("Only attending clinicians may sign lab results.");
        }
    }

    private String resolveActorDisplay(UUID userId, UUID hospitalId) {
        if (userId == null) {
            return UNKNOWN_CLINICIAN;
        }

        if (hospitalId != null) {
            var assignment = assignmentRepository.findFirstByUser_IdAndHospital_IdAndActiveTrue(userId, hospitalId);
            if (assignment.isPresent()) {
                return formatUserDisplay(assignment.get().getUser());
            }
        }

        return assignmentRepository.findFirstByUserIdAndActiveTrue(userId)
            .map(UserRoleHospitalAssignment::getUser)
            .map(this::formatUserDisplay)
            .or(() -> userRepository.findById(userId).map(this::formatUserDisplay))
            .orElse(UNKNOWN_CLINICIAN);
    }

    private String formatUserDisplay(User user) {
        if (user == null) {
            return UNKNOWN_CLINICIAN;
        }
        String fullName = (nullToEmpty(user.getFirstName()) + " " + nullToEmpty(user.getLastName())).trim();
        if (StringUtils.hasText(fullName)) {
            return fullName;
        }
        if (StringUtils.hasText(user.getEmail())) {
            return user.getEmail();
        }
        if (StringUtils.hasText(user.getUsername())) {
            return user.getUsername();
        }
        return UNKNOWN_CLINICIAN;
    }

    private String normalizeSignatureValue(LabResultSignatureRequestDTO request) {
        if (request == null) {
            return null;
        }
        String signature = request.getSignature();
        return StringUtils.hasText(signature) ? signature.trim() : null;
    }

    private String normalizeSignatureNotes(LabResultSignatureRequestDTO request) {
        if (request == null) {
            return null;
        }
        String notes = request.getNotes();
        return StringUtils.hasText(notes) ? notes.trim() : null;
    }

    // ── MVP3 helpers ─────────────────────────────────────────────────────────

    /**
     * Auto-release only a result that is normal by BOTH signals: the HL7
     * abnormal flag (set by MLLP inbound) and the mapper's reference-range
     * severity (the only signal a manually entered result has). LOW, HIGH
     * and CRITICAL stay unreleased for a human; UNSPECIFIED (no reference
     * range on the test) counts as "nothing abnormal found".
     */
    private void performAutoVerification(LabResult result, String severity) {
        if (!autoVerificationEnabled) {
            return;
        }
        boolean severityNormal = severity == null
            || "NORMAL".equalsIgnoreCase(severity)
            || LabResultMapper.FLAG_UNSPECIFIED.equalsIgnoreCase(severity);
        if (!result.isReleased()
                && severityNormal
                && (result.getAbnormalFlag() == null
                    || result.getAbnormalFlag() == AbnormalFlag.NORMAL)) {
            result.setReleased(true);
            result.setReleasedAt(LocalDateTime.now());
            result.setReleasedByDisplay("Autoverification");
            labResultRepository.save(result);
            LOG.debug("Auto-verified result {}", result.getId());
        }
    }

    private void triggerReflexOrders(LabResult result) {
        LabOrder parent = result.getLabOrder();
        if (parent == null || parent.getLabTestDefinition() == null) return;
        UUID testDefId = parent.getLabTestDefinition().getId();
        List<LabReflexRule> rules = labReflexRuleRepository
            .findByTriggerTestDefinition_IdAndActiveTrue(testDefId);
        for (LabReflexRule rule : rules) {
            if (evaluateReflexCondition(rule.getCondition(), result)) {
                createReflexChildOrder(rule, parent, result);
            }
        }
    }

    private boolean evaluateReflexCondition(String conditionJson, LabResult result) {
        try {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> cond =
                new tools.jackson.databind.ObjectMapper()
                    .readValue(conditionJson, java.util.Map.class);
            if (cond.containsKey("severityFlag")) {
                String required = (String) cond.get("severityFlag");
                AbnormalFlag flag = result.getAbnormalFlag() != null
                    ? result.getAbnormalFlag() : AbnormalFlag.NORMAL;
                // A rule written against the family ("ABNORMAL") fires on
                // either direction; a directional rule stays exact.
                return required.equalsIgnoreCase(flag.name())
                    || required.equalsIgnoreCase(flag.severity().name());
            }
            if (cond.containsKey("thresholdValue") && cond.containsKey("thresholdOperator")) {
                double threshold = ((Number) cond.get("thresholdValue")).doubleValue();
                String operator = (String) cond.get("thresholdOperator");
                double value = Double.parseDouble(result.getResultValue().trim());
                return switch (operator.toUpperCase()) {
                    case "GT"  -> value > threshold;
                    case "GTE" -> value >= threshold;
                    case "LT"  -> value < threshold;
                    case "LTE" -> value <= threshold;
                    default    -> false;
                };
            }
        } catch (Exception e) {
            LOG.warn("Failed to evaluate reflex condition '{}' for result {}: {}",
                conditionJson, result.getId(), e.getMessage());
        }
        return false;
    }

    private void createReflexChildOrder(LabReflexRule rule, LabOrder parent, LabResult result) {
        LabTestDefinition reflexDef = labTestDefinitionRepository
            .findById(rule.getReflexTestDefinition().getId()).orElse(null);
        if (reflexDef == null) {
            LOG.warn("Reflex rule {} references unknown test definition {}",
                rule.getId(), rule.getReflexTestDefinition().getId());
            return;
        }
        LabOrder child = LabOrder.builder()
            .patient(parent.getPatient())
            .orderingStaff(parent.getOrderingStaff())
            .encounter(parent.getEncounter())
            .labTestDefinition(reflexDef)
            .hospital(parent.getHospital())
            .performingHospital(parent.getPerformingHospital())
            .assignment(parent.getAssignment())
            .orderDatetime(LocalDateTime.now())
            .status(LabOrderStatus.ORDERED)
            .priority(parent.getPriority())
            .clinicalIndication("Reflex from order: " + parent.getId())
            .medicalNecessityNote("Auto-generated reflex order triggered by result " + result.getId())
            .orderChannel(parent.getOrderChannel())
            .orderChannelOther(parent.getOrderChannelOther())
            // A reflex order is the parent order continued (B12): the same
            // medical necessity, the same provider, the same documentation.
            // createLabOrder mandates these; the child used to carry none.
            // NOT copied: providerSignatureDigest / signedAt / signedByUserId.
            // The provider attested to the PARENT test; copying their
            // e-signature onto a test the rule ordered would fabricate an
            // attestation. The entity persists without one (only the REST
            // create path demands a signature), so the child records
            // truthfully that no provider signed it.
            .primaryDiagnosisCode(parent.getPrimaryDiagnosisCode())
            .additionalDiagnosisCodes(new ArrayList<>(parent.getAdditionalDiagnosisCodes() != null
                ? parent.getAdditionalDiagnosisCodes() : List.of()))
            .orderingProviderNpi(parent.getOrderingProviderNpi())
            .documentationSharedWithLab(parent.isDocumentationSharedWithLab())
            .documentationReference(parent.getDocumentationReference())
            .build();
        LabOrder saved = labOrderRepository.save(child);
        // B1: the child inherits the parent's performing laboratory, so it
        // lands on another hospital's worklist — silently, until now. A
        // reflex order is still an order arriving at that laboratory, and it
        // is announced the way createLabOrder announces one.
        routingNotifier.notifyPerformingLab(saved);
        LOG.info("Created reflex child order {} (test: {}) triggered by result {}",
            saved.getId(), reflexDef.getTestCode(), result.getId());
    }

    private void validateLabResultAuthor(UUID userId, UUID hospitalId) {
        // A real super-admin is unscoped by design across this product, and the
        // edge matcher admits them to POST /lab-results (B8). Without the same
        // bypass validateReleasePermissions has, they reached this check with
        // no per-hospital assignment and got a 400 from their own endpoint.
        if (authService.hasRole(ROLE_SUPER_ADMIN)) {
            return;
        }
        boolean allowed = roleValidator.hasRole(userId, hospitalId, "ROLE_LAB_SCIENTIST")
            || roleValidator.isMidwife(userId, hospitalId)
            || roleValidator.isDoctor(userId, hospitalId)
            || roleValidator.isNurse(userId, hospitalId)
            || roleValidator.isLabTechnician(userId, hospitalId)
            || roleValidator.isLabManager(userId, hospitalId)
            || roleValidator.hasRole(userId, hospitalId, ROLE_LAB_DIRECTOR)
            || roleValidator.hasRole(userId, hospitalId, "ROLE_QUALITY_MANAGER");
        if (!allowed) {
            throw new BusinessException("User does not have a lab or clinical role for this hospital.");
        }
    }

    private void acknowledgeResult(LabResult result, UUID userId) {
        if (result.isAcknowledged()) {
            LOG.debug("Lab result {} already acknowledged", result.getId());
            return;
        }
        // A critical result cannot be acknowledged without a read-back. Until
        // the 2026-08-21 reassessment this path silenced the escalation sweep
        // (which exits on acknowledged=false) with no read-back ever recorded —
        // the whole ceremony was optional for exactly the results it exists
        // for. The read-back path sets acknowledged itself on a match, so this
        // guard never blocks it.
        if (result.getCriticalNotifiedAt() != null && result.getCriticalReadBackAt() == null) {
            throw new BusinessException(
                "This is a critical result: acknowledge it by reading the value back, not by "
                    + "dismissing the alert. Use the read-back action.");
        }
        result.setAcknowledged(true);
        result.setAcknowledgedAt(LocalDateTime.now());
        result.setAcknowledgedByUserId(userId);
        result.setAcknowledgedByDisplay(resolveDisplayName(result));
        labResultRepository.save(result);
    }

    private List<LabResultTrendPointDTO> buildTrendHistory(LabResult source) {
        LabOrder labOrder = source.getLabOrder();
        if (labOrder == null
            || labOrder.getPatient() == null
            || labOrder.getPatient().getId() == null
            || labOrder.getLabTestDefinition() == null
            || labOrder.getLabTestDefinition().getId() == null) {
            return List.of();
        }

        UUID patientId = labOrder.getPatient().getId();
        UUID testDefinitionId = labOrder.getLabTestDefinition().getId();

        List<LabResult> rawTrend = labResultRepository
            .findTop12ByLabOrder_Patient_IdAndLabOrder_LabTestDefinition_IdOrderByResultDateDesc(patientId, testDefinitionId);

        if (rawTrend.isEmpty()) {
            return List.of();
        }

        return rawTrend.stream()
            .map(labResultMapper::toTrendPointDTO)
            .filter(Objects::nonNull)
            .sorted(Comparator.comparing(LabResultTrendPointDTO::getResultDate, Comparator.nullsLast(Comparator.naturalOrder())))
            .toList();
    }

    private String resolveDisplayName(LabResult result) {
        if (result.getAssignment() != null && result.getAssignment().getUser() != null) {
            var user = result.getAssignment().getUser();
            String fullName = (nullToEmpty(user.getFirstName()) + " " + nullToEmpty(user.getLastName())).trim();
            if (!fullName.isEmpty()) {
                return fullName;
            }
            if (user.getEmail() != null) {
                return user.getEmail();
            }
        }
        return UNKNOWN_CLINICIAN;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    // ==================== Enhanced Trending Methods (Story #5) ====================

    @Override
    @Transactional(readOnly = true)
    public LabResultComparisonDTO compareLabResults(UUID currentResultId, Locale locale) {
        LabResult current = labResultRepository.findById(currentResultId)
            .orElseThrow(() -> new ResourceNotFoundException(LAB_RESULT_NOT_FOUND));

        // Hospital scope enforcement
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        if (activeHospitalId != null && current.getLabOrder() != null
                && !current.getLabOrder().isHandledBy(activeHospitalId)) {
            throw new ResourceNotFoundException(LAB_RESULT_NOT_FOUND);
        }

        LabOrder labOrder = current.getLabOrder();
        if (labOrder == null || labOrder.getPatient() == null || labOrder.getLabTestDefinition() == null) {
            throw new BusinessException("Cannot compare lab results: missing patient or test definition");
        }

        UUID patientId = labOrder.getPatient().getId();
        UUID testDefinitionId = labOrder.getLabTestDefinition().getId();

        List<LabResult> trendResults = labResultRepository
            .findTop12ByLabOrder_Patient_IdAndLabOrder_LabTestDefinition_IdOrderByResultDateDesc(patientId, testDefinitionId);

        LabResult previous = trendResults.stream()
            .filter(r -> r.getResultDate().isBefore(current.getResultDate()))
            .findFirst()
            .orElse(null);

        List<LabResultTrendPointDTO> trendHistory = trendResults.stream()
            .map(labResultMapper::toTrendPointDTO)
            .filter(Objects::nonNull)
            .sorted(Comparator.comparing(LabResultTrendPointDTO::getResultDate, Comparator.nullsLast(Comparator.naturalOrder())))
            .toList();

        LabResultTrendPointDTO currentPoint = labResultMapper.toTrendPointDTO(current);
        LabResultTrendPointDTO previousPoint = previous != null ? labResultMapper.toTrendPointDTO(previous) : null;

        LabResultComparisonDTO.ComparisonMetadata comparison = calculateComparison(current, previous, trendHistory);

        return LabResultComparisonDTO.builder()
            .testCode(labOrder.getLabTestDefinition().getTestCode())
            .testName(labOrder.getLabTestDefinition().getName())
            .patientId(patientId.toString())
            .patientName(labOrder.getPatient().getFullName())
            .currentResult(currentPoint)
            .previousResult(previousPoint)
            .comparison(comparison)
            .trendHistory(trendHistory)
            .referenceRanges(extractReferenceRanges(labOrder))
            .build();
    }

    @Override
    @Transactional(readOnly = true)
    public List<LabResultComparisonDTO> compareSequentialResults(UUID patientId, UUID testDefinitionId, Locale locale) {
        List<LabResult> allResults = labResultRepository
            .findTop12ByLabOrder_Patient_IdAndLabOrder_LabTestDefinition_IdOrderByResultDateDesc(patientId, testDefinitionId);

        if (allResults.isEmpty()) {
            return List.of();
        }

        List<LabResultComparisonDTO> comparisons = new ArrayList<>();
        for (int i = 0; i < allResults.size(); i++) {
            LabResult current = allResults.get(i);
            LabResult previous = (i < allResults.size() - 1) ? allResults.get(i + 1) : null;

            LabResultTrendPointDTO currentPoint = labResultMapper.toTrendPointDTO(current);
            LabResultTrendPointDTO previousPoint = previous != null ? labResultMapper.toTrendPointDTO(previous) : null;

            LabResultComparisonDTO.ComparisonMetadata comparison = calculateComparison(current, previous, buildTrendHistory(current));

            comparisons.add(LabResultComparisonDTO.builder()
                .testCode(current.getLabOrder().getLabTestDefinition().getTestCode())
                .testName(current.getLabOrder().getLabTestDefinition().getName())
                .patientId(patientId.toString())
                .patientName(current.getLabOrder().getPatient().getFullName())
                .currentResult(currentPoint)
                .previousResult(previousPoint)
                .comparison(comparison)
                .referenceRanges(extractReferenceRanges(current.getLabOrder()))
                .build());
        }

        return comparisons;
    }

    @Override
    @Transactional(readOnly = true)
    public List<LabResultResponseDTO> getCriticalResults(UUID hospitalId, LocalDateTime since, Locale locale) {
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        UUID effectiveHospitalId = activeHospitalId != null ? activeHospitalId : hospitalId;
        // B1: a critical value is the running laboratory's to see and chase —
        // it is the one that produced it. These two were the last lab-side
        // reads still asking only who ordered.
        List<LabResult> candidates = labResultRepository.findHandledByHospitals(List.of(effectiveHospitalId));
        return surfaceCritical(candidates,
            r -> r.getResultDate() != null && r.getResultDate().isAfter(since));
    }

    @Override
    @Transactional(readOnly = true)
    public List<LabResultResponseDTO> getCriticalResultsRequiringAcknowledgment(UUID hospitalId, Locale locale) {
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        UUID effectiveHospitalId = activeHospitalId != null ? activeHospitalId : hospitalId;
        List<LabResult> candidates = labResultRepository.findHandledByHospitals(List.of(effectiveHospitalId));
        return surfaceCritical(candidates, r -> !r.isAcknowledged());
    }

    /**
     * The critical rows a caller actually gets, accounted for exactly.
     *
     * <p>Both critical-value endpoints read every result the hospital handles
     * and then filter hard — by date or acknowledgement, then by severity —
     * so accounting the query result rather than the answer wrote disclosures
     * for patients who were never surfaced, inflated every surfaced count,
     * and let a polled dashboard write without bound. The filtering happens
     * first now and only the survivors are accounted.
     */
    private List<LabResultResponseDTO> surfaceCritical(List<LabResult> candidates,
                                                       java.util.function.Predicate<LabResult> queueFilter) {
        List<LabResult> surfaced = new ArrayList<>();
        List<LabResultResponseDTO> answer = new ArrayList<>();
        for (LabResult result : candidates) {
            if (queueFilter.test(result)) {
                LabResultResponseDTO dto = labResultMapper.toResponseDTO(result);
                if (dto != null && isCriticalSeverity(dto)) {
                    surfaced.add(result);
                    answer.add(dto);
                }
            }
        }
        recordPerformedHereReach(surfaced);
        return answer.stream()
            .sorted(Comparator.comparing(LabResultResponseDTO::getResultDate,
                Comparator.nullsLast(Comparator.naturalOrder())).reversed())
            .toList();
    }

    private static boolean isCriticalSeverity(LabResultResponseDTO dto) {
        return CRITICAL_FLAG.equalsIgnoreCase(dto.getSeverityFlag())
            || "HIGH".equalsIgnoreCase(dto.getSeverityFlag());
    }

    private LabResultComparisonDTO.ComparisonMetadata calculateComparison(LabResult current, LabResult previous, List<LabResultTrendPointDTO> trendHistory) {
        if (previous == null) {
            return LabResultComparisonDTO.ComparisonMetadata.builder()
                .trendDirection(LabResultComparisonDTO.TrendDirection.INSUFFICIENT_DATA)
                .significanceLevel("BASELINE")
                .interpretation("First recorded measurement - no comparison available")
                .build();
        }

        String currentVal = current.getResultValue();
        String previousVal = previous.getResultValue();
        long daysBetween = ElapsedTime.daysBetween(previous.getResultDate(), current.getResultDate());

        // Attempt numeric comparison
        try {
            double currentNum = Double.parseDouble(currentVal);
            double previousNum = Double.parseDouble(previousVal);
            double absoluteChange = currentNum - previousNum;
            double percentageChange = ((currentNum - previousNum) / previousNum) * 100;

            LabResultComparisonDTO.TrendDirection direction = determineTrendDirection(trendHistory);
            String significance = determineSignificance(percentageChange);
            String interpretation = generateInterpretation(currentNum, previousNum, absoluteChange, percentageChange, daysBetween, current.getResultUnit());

            return LabResultComparisonDTO.ComparisonMetadata.builder()
                .absoluteChange(String.format("%.2f %s", absoluteChange, current.getResultUnit() != null ? current.getResultUnit() : ""))
                .percentageChange(percentageChange)
                .trendDirection(direction)
                .daysBetween(daysBetween)
                .significanceLevel(significance)
                .crossedThreshold(false) // Future: Implement reference range boundary checking
                .interpretation(interpretation)
                .build();
        } catch (NumberFormatException e) {
            // Non-numeric comparison
            boolean changed = !currentVal.equalsIgnoreCase(previousVal);
            return LabResultComparisonDTO.ComparisonMetadata.builder()
                .absoluteChange(changed ? "Changed from " + previousVal + " to " + currentVal : "No change")
                .trendDirection(changed ? LabResultComparisonDTO.TrendDirection.FLUCTUATING : LabResultComparisonDTO.TrendDirection.STABLE)
                .daysBetween(daysBetween)
                .significanceLevel(changed ? "SIGNIFICANT" : "STABLE")
                .interpretation(changed ? "Qualitative change detected" : "Result unchanged")
                .build();
        }
    }

    private LabResultComparisonDTO.TrendDirection determineTrendDirection(List<LabResultTrendPointDTO> trendHistory) {
        if (trendHistory.size() < 2) {
            return LabResultComparisonDTO.TrendDirection.INSUFFICIENT_DATA;
        }

        List<Double> numericValues = trendHistory.stream()
            .map(point -> {
                try {
                    return Double.parseDouble(point.getResultValue());
                } catch (NumberFormatException e) {
                    return null;
                }
            })
            .filter(Objects::nonNull)
            .toList();

        if (numericValues.size() < 2) {
            return LabResultComparisonDTO.TrendDirection.INSUFFICIENT_DATA;
        }

        // Simple trend analysis: compare first half to second half
        int midpoint = numericValues.size() / 2;
        double firstHalfAvg = numericValues.subList(0, midpoint).stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double secondHalfAvg = numericValues.subList(midpoint, numericValues.size()).stream().mapToDouble(Double::doubleValue).average().orElse(0);

        double changePercent = Math.abs((secondHalfAvg - firstHalfAvg) / firstHalfAvg) * 100;

        if (changePercent < 5) {
            return LabResultComparisonDTO.TrendDirection.STABLE;
        } else if (secondHalfAvg > firstHalfAvg) {
            return LabResultComparisonDTO.TrendDirection.INCREASING;
        } else {
            return LabResultComparisonDTO.TrendDirection.DECREASING;
        }
    }

    private String determineSignificance(double percentageChange) {
        double absPercent = Math.abs(percentageChange);

        if (absPercent > 50) {
            return CRITICAL_FLAG;
        } else if (absPercent > 25) {
            return "SIGNIFICANT";
        } else if (absPercent > 10) {
            return "MINOR";
        } else {
            return "STABLE";
        }
    }

    private String generateInterpretation(double current, double previous, double absoluteChange, double percentageChange, long daysBetween, String unit) {
        String direction = absoluteChange > 0 ? "increased" : "decreased";
        String unitStr = unit != null ? " " + unit : "";

        return String.format("Value %s by %.2f%s (%.1f%%) over %d days. Previous: %.2f%s, Current: %.2f%s",
            direction, Math.abs(absoluteChange), unitStr, Math.abs(percentageChange), daysBetween,
            previous, unitStr, current, unitStr);
    }

    private List<LabResultReferenceRangeDTO> extractReferenceRanges(LabOrder labOrder) {
        if (labOrder == null || labOrder.getLabTestDefinition() == null) {
            return List.of();
        }

        return labOrder.getLabTestDefinition().getReferenceRanges().stream()
            .map(range -> LabResultReferenceRangeDTO.builder()
                .minValue(range.getMinValue())
                .maxValue(range.getMaxValue())
                .unit(range.getUnit())
                .ageMin(range.getAgeMin())
                .ageMax(range.getAgeMax())
                .gender(range.getGender())
                .notes(range.getNotes())
                .build())
            .toList();
    }
}
