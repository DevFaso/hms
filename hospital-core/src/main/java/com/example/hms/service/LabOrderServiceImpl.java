package com.example.hms.service;

import com.example.hms.enums.LabOrderChannel;
import com.example.hms.enums.LabOrderStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.LabOrderMapper;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.Patient;
import com.example.hms.model.Role;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.LabOrderRequestDTO;
import com.example.hms.payload.dto.LabOrderResponseDTO;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.LabTestDefinitionRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.utility.DiagnosisCodeValidator;
import com.example.hms.utility.RoleValidator;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import com.example.hms.service.recordaccess.CrossHospitalReachRecorder;
import com.example.hms.service.recordaccess.RecordAccessPolicy;

@Slf4j
@Service
@RequiredArgsConstructor
public class LabOrderServiceImpl implements LabOrderService {

    // Sonar S1192 (Pattern 5 of docs/SonarQubeInstructions.md): the
    // i18n key for "lab order not found" appears 7x in this file.
    // One constant, one source of truth.
    private static final String LAB_ORDER_NOT_FOUND = "laborder.notfound";

    /**
     * Resolvable message key, not a sentence, and the same one
     * {@code PatientChartAccess} and {@code PatientLabResultServiceImpl} throw:
     * a scopeless read of one patient's record is refused identically wherever
     * the chart asks for it, and says nothing about whether the patient exists.
     */
    private static final String MSG_PATIENT_NOT_FOUND = "patient.notFound";

    /**
     * Ceiling on a single worklist page.
     *
     * <p>See {@link com.example.hms.utility.PageBounds}: 500 is well clear of
     * the portal's own request (200).
     */
    private static final int MAX_WORKLIST_PAGE_SIZE = 500;

    /** The one description every performing-laboratory disclosure carries (Sonar S1192). */
    private static final String PERFORMED_HERE_REACH_DESCRIPTION =
        "Cross-hospital lab order read at the performing laboratory";

    private final LabOrderRepository labOrderRepository;
    private final PatientRepository patientRepository;
    private final StaffRepository staffRepository;
    private final EncounterRepository encounterRepository;
    private final LabTestDefinitionRepository labTestDefinitionRepository;
    private final LabOrderMapper labOrderMapper;
    private final RoleValidator roleValidator;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final HospitalRepository hospitalRepository;
    private final PatientHospitalRegistrationRepository patientHospitalRegistrationRepository;
    private final RecordAccessPolicy recordAccessPolicy;
    private final CrossHospitalReachRecorder reachRecorder;
    private final com.example.hms.service.lab.LabOrderRoutingNotifier routingNotifier;
    private final com.example.hms.repository.LabSpecimenRepository labSpecimenRepository;
    private final com.example.hms.repository.LabResultRepository labResultRepository;
    private static final HexFormat HEX_FORMAT = HexFormat.of();

    @Override
    @Transactional
    public LabOrderResponseDTO createLabOrder(LabOrderRequestDTO request, Locale locale) {
        LabOrder newLabOrder = buildLabOrder(null, request, true);
        LabOrder saved = labOrderRepository.save(newLabOrder);
        // B1: the performing laboratory learns about the order (after commit, best-effort).
        routingNotifier.notifyPerformingLab(saved);
        return labOrderMapper.toLabOrderResponseDTO(saved);
    }

    @Override
    @Transactional
    public LabOrderResponseDTO updateLabOrder(UUID id, LabOrderRequestDTO request, Locale locale) {
        LabOrder existing = labOrderRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(LAB_ORDER_NOT_FOUND));

        // The clinical order belongs to the hospital that placed it: the
        // performing laboratory reads and results it but never rewrites it.
        requireOrderingHospital(existing);

        Hospital previousPerformer = existing.getPerformingHospital();
        LabOrder updated = buildLabOrder(existing, request, false);
        LabOrder saved = labOrderRepository.save(updated);
        // A legitimate re-route is a new order for the new laboratory.
        if (saved.isPerformedExternally()
                && (previousPerformer == null
                    || !Objects.equals(previousPerformer.getId(), saved.getPerformingHospital().getId()))) {
            routingNotifier.notifyPerformingLab(saved);
        }
        return labOrderMapper.toLabOrderResponseDTO(saved);
    }

    /** 404-not-403: an ordering-side write from any other hospital does not exist. */
    private void requireOrderingHospital(LabOrder labOrder) {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId != null
                && labOrder.getHospital() != null
                && !labOrder.getHospital().getId().equals(hospitalId)) {
            throw new ResourceNotFoundException(LAB_ORDER_NOT_FOUND);
        }
    }

    /**
     * B1: the laboratory that performs the test.
     *
     * <p>Three request states, and they are not the same thing on an update:
     * an <em>omitted</em> field leaves the routing untouched (what every
     * client that knows nothing of B1 sends), an <em>explicit null</em> — or
     * the ordering hospital's own id — brings the test back in-house, and any
     * other id routes it there. Both changes are subject to the
     * no-specimen/no-result guard below. On a create there is nothing to keep,
     * so absent and null both mean "our own laboratory".
     *
     * <p>A target must be an active hospital: the platform has no notion of
     * partner or affiliated hospitals to narrow it to. That check applies to a
     * laboratory the caller is choosing — re-sending the one already on the
     * order is not a choice, so editing the notes of an order whose laboratory
     * has since been suspended must not fail.
     */
    private Hospital resolvePerformingHospital(LabOrderRequestDTO request, Hospital orderingHospital, LabOrder base) {
        if (base != null && !request.hasPerformingHospitalId()) {
            return base.getPerformingHospital();
        }
        UUID requested = request.getPerformingHospitalId();
        Hospital current = base != null ? base.getPerformingHospital() : null;
        Hospital performing;
        if (requested == null || requested.equals(orderingHospital.getId())) {
            performing = null;
        } else if (current != null && requested.equals(current.getId())) {
            // Unchanged: keep the row as it is, routability unexamined.
            performing = current;
        } else {
            performing = hospitalRepository.findById(requested)
                .orElseThrow(() -> new ResourceNotFoundException("hospital.notfound"));
            if (!isRoutableLab(performing)) {
                throw new BusinessException("The performing laboratory must be an active hospital.");
            }
        }
        requirePerformerChangeAllowed(base, performing);
        return performing;
    }

    /**
     * The performing laboratory may change only while nobody has worked the
     * order: once a specimen or a result exists there, moving the order (or
     * bringing it in-house) would orphan that laboratory's rows —
     * {@code LabResult.validate()} would refuse every later release or sign.
     */
    private void requirePerformerChangeAllowed(LabOrder base, Hospital performing) {
        if (base == null || base.getId() == null) {
            return;
        }
        UUID current = base.getPerformingHospital() != null ? base.getPerformingHospital().getId() : null;
        UUID next = performing != null ? performing.getId() : null;
        if (Objects.equals(current, next)) {
            return;
        }
        if (!labSpecimenRepository.findByLabOrder_Id(base.getId()).isEmpty()
                || !labResultRepository.findByLabOrder_Id(base.getId()).isEmpty()) {
            throw new com.example.hms.exception.ConflictException(
                "The performing laboratory cannot change once a specimen or a result has been recorded for this order.");
        }
    }

    /**
     * B1 + E8: an order this hospital's laboratory performs belongs to the
     * hospital that ordered it, so surfacing it here is a cross-hospital
     * disclosure — the same conclusion round 4 reached for the patient's
     * list, applied to the routes the feature is actually used from. One
     * RECORD_SHARE per patient per source hospital; an in-house order, or a
     * caller with no hospital scope, records nothing.
     */
    private void recordPerformedHereReach(java.util.Collection<LabOrder> orders, UUID actingHospitalId) {
        if (actingHospitalId == null || orders.isEmpty()) {
            return;
        }
        // Accounting a read must never fail it. Everything here — resolving
        // the patients, the actor, the break-glass session inside the
        // recorder — is wrapped, so a worklist still answers when the audit
        // side is down. The reach itself is best-effort by the same contract
        // the notifier and the critical-value service follow.
        try {
            Map<UUID, Map<String, Long>> perPatient = new java.util.HashMap<>();
            for (LabOrder order : orders) {
                if (order.isPerformedAt(actingHospitalId)) {
                    UUID patientId = order.getPatient() != null ? order.getPatient().getId() : null;
                    UUID source = CrossHospitalReachRecorder.hospitalIdOf(order.getHospital());
                    if (patientId != null && source != null) {
                        perPatient.computeIfAbsent(patientId, key -> new java.util.HashMap<>())
                            .merge(source.toString(), 1L, Long::sum);
                    }
                }
            }
            if (perPatient.isEmpty()) {
                return;
            }
            // Batched purely for cost: the page's patients are resolved once
            // and written in one pass, where recording per patient cost a
            // committed transaction each. The break-glass lookup is still per
            // patient — it is per patient by nature. Nothing is suppressed:
            // every read is recorded.
            reachRecorder.recordBatchedReach(perPatient, actingHospitalId,
                roleValidator.getCurrentUserId(), null, PERFORMED_HERE_REACH_DESCRIPTION);
        } catch (RuntimeException ex) {
            log.warn("Cross-hospital disclosure accounting failed for a performing-laboratory read at {}: {}",
                actingHospitalId, ex.getMessage());
        }
    }

    private static boolean isRoutableLab(Hospital hospital) {
        return hospital.isActive()
            && hospital.getLifecycleState() == com.example.hms.enums.HospitalLifecycleState.ACTIVE;
    }

    @Override
    @Transactional(readOnly = true)
    public List<com.example.hms.payload.dto.PerformingLabOptionDTO> listPerformingLabs() {
        UUID actingHospitalId = roleValidator.requireActiveHospitalId();
        return hospitalRepository
            .findByActiveTrueAndLifecycleStateOrderByNameAsc(com.example.hms.enums.HospitalLifecycleState.ACTIVE)
            .stream()
            .filter(h -> !h.getId().equals(actingHospitalId))
            .map(h -> com.example.hms.payload.dto.PerformingLabOptionDTO.builder()
                .id(h.getId())
                .name(h.getName())
                .code(h.getCode())
                .build())
            .toList();
    }

    private LabOrder buildLabOrder(LabOrder base, LabOrderRequestDTO request, boolean isNew) {
    String clinicalIndication = normalizeRequiredText(request.getClinicalIndication(), "Clinical indication is required for lab orders.");
    String medicalNecessityNote = normalizeRequiredText(request.getMedicalNecessityNote(), "Medical necessity rationale is required for lab orders.");
        String notes = normalizeOptionalText(request.getNotes());

        Patient patient = patientRepository.findByIdUnscoped(request.getPatientId())
            .orElseThrow(() -> new ResourceNotFoundException("patient.notfound"));

        Staff staff = staffRepository.findById(request.getOrderingStaffId())
            .orElseThrow(() -> new ResourceNotFoundException("staff.notfound"));

        UUID requestedHospitalId = request.getHospitalId();
        Encounter encounter = null;
        if (request.getEncounterId() != null) {
            encounter = encounterRepository.findById(request.getEncounterId())
                .orElseThrow(() -> new ResourceNotFoundException("encounter.notfound"));
        }

        Hospital hospital = encounter != null ? encounter.getHospital() : null;
        if (hospital != null && requestedHospitalId != null && !hospital.getId().equals(requestedHospitalId)) {
            throw new BusinessException("Encounter hospital does not match the requested hospital.");
        }

        if (hospital == null && requestedHospitalId != null) {
            hospital = hospitalRepository.findById(requestedHospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("hospital.notfound"));
        }

        if (hospital == null) {
            throw new BusinessException("Lab orders must reference a hospital either via encounter or hospital identifier.");
        }

        boolean patientRegistered = patientHospitalRegistrationRepository.existsByPatientIdAndHospitalId(
            patient.getId(),
            hospital.getId()
        );
        if (!patientRegistered) {
            throw new BusinessException("Patient is not registered with the specified hospital.");
        }

        // Role check based on assignment, not JWT
        UUID userId = staff.getUser().getId();
        UUID hospitalId = hospital.getId();
        boolean authorized = roleValidator.canOrderLabTests(userId, hospitalId);
        if (!authorized) {
            log.warn("User {} is not authorized to place lab orders in hospital {}", userId, hospitalId);
            throw new BusinessException("Only doctors, physicians, nurses, or nurse practitioners can place lab orders.");
        }

        LabTestDefinition testDefinition = labTestDefinitionRepository.findById(request.getLabTestDefinitionId())
            .orElseThrow(() -> new ResourceNotFoundException("labtestdefinition.notfound"));

        UserRoleHospitalAssignment assignment = assignmentRepository.findById(request.getAssignmentId())
            .orElseThrow(() -> new ResourceNotFoundException("assignment.notfound"));

        if (isNew && labOrderRepository.existsByPatient_IdAndLabTestDefinition_IdAndOrderDatetime(
            patient.getId(), testDefinition.getId(), request.getOrderDatetime())) {
            throw new BusinessException("Duplicate lab order detected for the same test, patient, and date.");
        }

        LabOrder labOrder = (base != null) ? base : new LabOrder();

        labOrder.setPatient(patient);
        labOrder.setOrderingStaff(staff);
        labOrder.setEncounter(encounter);
        labOrder.setHospital(hospital);
        labOrder.setPerformingHospital(resolvePerformingHospital(request, hospital, base));
        labOrder.setLabTestDefinition(testDefinition);
        labOrder.setAssignment(assignment);
        labOrder.setOrderDatetime(request.getOrderDatetime());
        applyRequestedStatus(labOrder, request.getStatus(), isNew);
        labOrder.setClinicalIndication(clinicalIndication);
        labOrder.setMedicalNecessityNote(medicalNecessityNote);
        labOrder.setNotes(notes);
        labOrder.setPrimaryDiagnosisCode(resolvePrimaryDiagnosisCode(request, base));
        labOrder.setAdditionalDiagnosisCodes(new ArrayList<>(resolveAdditionalDiagnosisCodes(request, base)));
        LabOrderChannel orderChannel = resolveOrderChannel(request.getOrderChannel(), base);
        labOrder.setOrderChannel(orderChannel);
        labOrder.setOrderChannelOther(resolveOrderChannelOther(orderChannel, request, base));
    boolean sharedDocumentation = resolveDocumentationSharedFlag(orderChannel, request, base);
    enforceDocumentationCompliance(orderChannel, sharedDocumentation);
    labOrder.setDocumentationSharedWithLab(sharedDocumentation);
        labOrder.setDocumentationReference(resolveDocumentationReference(sharedDocumentation, request, base));
        labOrder.setOrderingProviderNpi(resolveOrderingProviderNpi(staff, request.getOrderingProviderNpi(), base));
        labOrder.setProviderSignatureDigest(resolveProviderSignatureDigest(request.getProviderSignature(), base, isNew));
        labOrder.setSignedAt(resolveSignedAt(request.getSignedAt(), base));
        labOrder.setSignedByUserId(staff.getUser().getId());
        applyStandingOrderMetadata(labOrder, request, base, labOrder.getOrderDatetime());

        return labOrder;
    }

    /**
     * Where a new lab order may start: ORDERED or PENDING, and nothing else.
     *
     * <p>Everything past PENDING asserts laboratory work with no specimen or
     * result row behind it, and the two terminal states are worse than that.
     * CANCELLED was briefly allowed here as "a decision, not a claim of work";
     * that was wrong for the same reason COMPLETED is. An order created
     * CANCELLED is frozen against every lifecycle event for ever — nothing
     * re-opens a cancelled order, by design — so the row can never become
     * anything else, and a caller that wants one records the order and then
     * cancels it through the transition endpoint, which is role-checked.
     */
    private static final Set<LabOrderStatus> CREATABLE_STATUSES =
        EnumSet.of(LabOrderStatus.ORDERED, LabOrderStatus.PENDING);

    /**
     * Where a new order may start, and what an update may say about status.
     *
     * <p>A new order starts at a START state. Two callers legitimately choose
     * which one — {@code SuperAdminLabOrderServiceImpl}, where the status is a
     * mandatory field of the super-admin request, and
     * {@code OrderSetItemDispatcher}, which places order-set items as PENDING
     * — so forcing every create to ORDERED discarded a value one caller
     * validated and made the other log a warning for every order it placed.
     *
     * <p>What a create may NOT do is claim work the laboratory has not done:
     * an order born COMPLETED or CANCELLED is frozen against every specimen
     * and result event ({@code LabOrderLifecycle} will not move a terminal
     * order) and a COMPLETED one reaches the ordering doctor's review queue
     * with no results behind it. Those are refused. The states in between
     * (COLLECTED … VERIFIED) describe laboratory progress with no specimen or
     * result row to back it, so a create is held to the start states and told
     * why.
     *
     * <p>On UPDATE the value is echoed back by every edit form, so a matching
     * one is tolerated silently and a differing one is ignored with a warning:
     * the lifecycle moves only through {@link #transitionLabOrderStatus}
     * (role-checked per step) and the specimen and result events.
     */

    private void applyRequestedStatus(LabOrder labOrder, String requestedStatus, boolean isNew) {
        LabOrderStatus requested;
        try {
            requested = LabOrderStatus.valueOf(requestedStatus.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("Unknown lab order status: " + requestedStatus);
        }
        if (isNew) {
            if (!CREATABLE_STATUSES.contains(requested)) {
                // Intended contract, and a deliberate behaviour change for the
                // super-admin endpoint, which validates status as mandatory
                // but only checks it is non-blank. Refused rather than quietly
                // coerced, so the caller learns. (Checked: no seeded or
                // scripted caller sends one — the seeder builds entities
                // directly, and the only sample carrying IN_PROGRESS is a PUT,
                // which is unaffected.)
                throw new BusinessException(
                    "A new lab order cannot be created with status " + requested.name()
                        + ". New orders start at ORDERED or PENDING; the laboratory workflow moves "
                        + "them on from there, and a cancellation goes through the transition "
                        + "endpoint so it is role-checked and cannot freeze a brand-new order.");
            }
            labOrder.setStatus(requested);
            return;
        }
        if (labOrder.getStatus() != requested) {
            log.warn("Ignoring status {} on update of lab order {} (current {}): use the transition endpoint",
                requested, labOrder.getId(), labOrder.getStatus());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public LabOrderResponseDTO getLabOrderById(UUID id, Locale locale) {
        LabOrder labOrder = labOrderRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(LAB_ORDER_NOT_FOUND));

        // ── Hospital scope enforcement (ordering OR performing hospital) ──
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (!labOrder.isHandledBy(hospitalId)) {
            throw new ResourceNotFoundException(LAB_ORDER_NOT_FOUND);
        }
        recordPerformedHereReach(List.of(labOrder), hospitalId);

        return labOrderMapper.toLabOrderResponseDTO(labOrder);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LabOrderResponseDTO> getAllLabOrders(Locale locale) {
        // ── Hospital scope enforcement: scope to hospital when non-superadmin ──
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId != null) {
            List<LabOrder> orders = labOrderRepository.findHandledBy(hospitalId);
            recordPerformedHereReach(orders, hospitalId);
            return orders.stream()
                .map(labOrderMapper::toLabOrderResponseDTO)
                .toList();
        }
        return labOrderRepository.findAll().stream()
            .map(labOrderMapper::toLabOrderResponseDTO)
            .toList();
    }

    @Override
    @Transactional
    public void deleteLabOrder(UUID id, Locale locale) {
        LabOrder labOrder = labOrderRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(LAB_ORDER_NOT_FOUND));

        // ── Hospital scope enforcement: deletion stays with the ordering hospital ──
        requireOrderingHospital(labOrder);

        labOrderRepository.deleteById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<LabOrderResponseDTO> searchLabOrders(UUID patientId, LocalDateTime fromDate, LocalDateTime toDate, Pageable pageable, Locale locale) {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId == null && patientId != null) {
            // One patient's record, asked for with no acting hospital.
            //
            // `buildPredicates` omits the hospital predicate entirely when the
            // id is null, so this returned EVERY tenant's lab orders for the
            // patient; and `recordPerformedHereReach` returns early on a null
            // acting hospital, so not one of those foreign rows was accounted.
            // That second half is not an oversight that could be patched here:
            // a RECORD_SHARE row pairs a SOURCE hospital with an ACTING one,
            // and in global view there is no acting hospital for the disclosure
            // to be recorded against. Unscoped and accounted is not a state
            // this endpoint can be in.
            //
            // Only a real super-admin in global view reaches this:
            // requireActiveHospitalId returns null solely for the super-admin
            // branch and throws BusinessException for everyone else. A scoped
            // read is already whole — with an acting hospital the predicate
            // admits only rows this hospital ordered or performs, and the
            // performed-for-others rows are exactly the ones
            // recordPerformedHereReach accounts.
            //
            // Narrow by design: the patient-less listing below is the platform
            // worklist the lab screens page through, and a super-admin seeing
            // it whole stays this service's behaviour (getAllLabOrders and
            // getLabOrdersByLabTestDefinitionId still read that way — neither
            // is filtered to one person). What is refused is the shape that is
            // somebody's record rather than a worklist; getLabOrdersByPatientId
            // and getLabOrdersByStaffId are refused for the same reason.
            //
            // Which makes this a line, not a wall, and that is worth saying
            // plainly: the worklist below is still unscoped and still
            // unaccounted, so a super-admin in global view can page it (the lab
            // screens already ask for 500 a page) and filter to one patient on
            // the client — the same rows, still no RECORD_SHARE row. Closing
            // that means deciding what a platform-wide worklist is allowed to
            // be, which is a product question this change does not answer. What
            // it removes is the endpoint that served one patient's cross-tenant
            // record directly, on request, to the chart.
            //
            // 404 on the patient, so the refusal says nothing about whether the
            // rows exist.
            //
            // patient.notFound, not the patient.notfound this class throws in
            // buildLabOrder: both keys exist and messages_en resolves them
            // differently ("...with ID: {0}" vs "Patient not found"). The
            // lowercase one is only reachable on the create/update path, never
            // on this GET, so the answer to match is the chart's OTHER lab
            // block, which throws the camelCase key through PatientChartAccess.
            //
            // Matching that key is the intent; it does not depend on the other
            // block already refusing. This guard is right whether or not
            // PatientLabResultServiceImpl refuses the same input — an
            // unaccounted cross-tenant read is not made acceptable by a sibling
            // still serving one, and the two agreeing is a property to reach,
            // not a precondition. (At the time of writing it does still serve
            // one; that is a defect there, not a reason to keep this one.)
            throw new ResourceNotFoundException(MSG_PATIENT_NOT_FOUND, patientId);
        }
        Page<LabOrder> page = labOrderRepository.search(hospitalId, patientId, fromDate, toDate,
            com.example.hms.utility.PageBounds.atMost(pageable, MAX_WORKLIST_PAGE_SIZE));
        recordPerformedHereReach(page.getContent(), hospitalId);
        return page.map(labOrderMapper::toLabOrderResponseDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LabOrderResponseDTO> getLabOrdersByPatientId(UUID patientId, Locale locale) {
        // ── Hospital scope enforcement ──
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId != null) {
            // E9 #59b — lab orders follow the patient: the acting hospital plus
            // every hospital the policy lets this caller read for this patient.
            // Lab orders carry no sensitivity tag (V158), so every foreign row
            // travels; each one surfaced is accounted.
            UUID requesterUserId = roleValidator.getCurrentUserId();
            Set<UUID> readable = recordAccessPolicy.readableHospitalIds(requesterUserId, patientId, hospitalId);
            // B1: plus the orders this hospital's laboratory performs for others.
            List<LabOrder> orders = labOrderRepository.findByPatientIdReadableOrPerformedAt(patientId, readable, hospitalId);
            // Every row is accounted against the hospital it belongs to — the
            // ordering one — including the orders this laboratory performs.
            // The accounting asks whose record was surfaced where, not whether
            // the reader was entitled to it: RECORD_SHARE pairs a source
            // hospital with an acting hospital, and permitted reads are exactly
            // what it exists to account for (the treatment-relationship reads
            // E8 records are all permitted too). An order placed at A for a
            // patient registered at A, read at the laboratory B that runs it,
            // is A's record surfaced at B. Rewriting those rows to B's own id
            // made reachOf skip them, so the one disclosure this feature
            // introduces was the one disclosure nobody could see.
            reachRecorder.recordReach(patientId, hospitalId, requesterUserId, null,
                CrossHospitalReachRecorder.reachOf(orders.stream()
                    .map(o -> CrossHospitalReachRecorder.hospitalIdOf(o.getHospital()))
                    .toList(), hospitalId),
                "Cross-hospital lab order read on the treatment relationship");
            return orders.stream()
                .map(labOrderMapper::toLabOrderResponseDTO)
                .toList();
        }
        // The same hole searchLabOrders had, in the same shape: every tenant's
        // orders for one patient, and the recordReach call above sits INSIDE
        // the scoped branch, so none of it was accounted. This method is
        // unconditionally patient-filtered, so the guard is just the null
        // scope — no worklist reading to preserve.
        //
        // Guarded even though nothing calls it today: it is on LabOrderService
        // with no @GetMapping anywhere (the only other mention is a javadoc in
        // CrossHospitalReachRecorder), and a note in a pull request is not
        // something whoever wires it up will read.
        throw new ResourceNotFoundException(MSG_PATIENT_NOT_FOUND, patientId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LabOrderResponseDTO> getLabOrdersByStaffId(UUID staffId, Locale locale) {
        // ── Hospital scope enforcement ──
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId != null) {
            return labOrderRepository.findByOrderingStaff_IdAndHospital_Id(staffId, hospitalId).stream()
                .map(labOrderMapper::toLabOrderResponseDTO)
                .toList();
        }
        // Same shape as the two patient reads, and it took checking to be sure,
        // because the obvious reasoning says it cannot leak: a Staff ROW is
        // pinned to one hospital (hospital_id NOT NULL, uq_staff_user_hospital
        // on (user_id, hospital_id)), so a clinician working at two hospitals
        // has two staff rows with two ids, and "this staff id's orders" looks
        // like one tenant's data by construction.
        //
        // It is not. buildLabOrder takes the order's hospital from the ENCOUNTER
        // or the requested hospitalId, looks the ordering Staff up independently
        // by id, and authorizes canOrderLabTests(staff.getUser().getId(),
        // hospital.getId()) — on the USER, who may hold assignments at several
        // hospitals. Nothing anywhere compares staff.getHospital() to the
        // order's hospital. So one staff id can own orders at more than one
        // hospital, and this fallback unions them with no acting hospital to
        // account the disclosure against — one clinician's order history across
        // tenants, and with it every patient on it.
        //
        // Deriving the scope from the staff row instead was the tempting
        // alternative and it is wrong twice over. It would manufacture an
        // acting hospital the caller never scoped to, so the RECORD_SHARE row
        // would name a hospital the caller is not acting at — falsifying the
        // accounting rather than completing it; scope is a property of the
        // CALLER, never of the subject being asked about. And it would not even
        // answer the question: filtering to staff.getHospital() drops exactly
        // the orders that staff placed elsewhere, which are the rows that make
        // this cross-tenant in the first place.
        //
        // staff.notfound, the key this class already throws for a staff id it
        // will not resolve, so the refusal is indistinguishable from one.
        throw new ResourceNotFoundException("staff.notfound", staffId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LabOrderResponseDTO> getLabOrdersByLabTestDefinitionId(UUID labTestDefinitionId, Locale locale) {
        // Lab test definitions are global (not hospital-scoped), but results should still be scoped
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        List<LabOrder> orders = labOrderRepository.findByLabTestDefinition_Id(labTestDefinitionId);
        if (hospitalId != null) {
            orders = orders.stream()
                .filter(lo -> lo.isHandledBy(hospitalId))
                .toList();
            recordPerformedHereReach(orders, hospitalId);
        }
        return orders.stream()
            .map(labOrderMapper::toLabOrderResponseDTO)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<LabOrderResponseDTO> getLabOrdersByStatus(LabOrderStatus status, Locale locale) {
        // ── Hospital scope enforcement ──
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId != null) {
            List<LabOrder> orders = labOrderRepository.findByStatusHandledBy(status, hospitalId);
            recordPerformedHereReach(orders, hospitalId);
            return orders.stream()
                .map(labOrderMapper::toLabOrderResponseDTO)
                .toList();
        }
        return labOrderRepository.findByStatus(status).stream()
            .map(labOrderMapper::toLabOrderResponseDTO)
            .toList();
    }

    // ── Valid forward transitions (CANCELLED handled separately) ──────────────
    private static final Map<LabOrderStatus, Set<LabOrderStatus>> ALLOWED_TRANSITIONS;
    static {
        ALLOWED_TRANSITIONS = new EnumMap<>(LabOrderStatus.class);
        ALLOWED_TRANSITIONS.put(LabOrderStatus.ORDERED,     EnumSet.of(LabOrderStatus.PENDING,     LabOrderStatus.CANCELLED));
        ALLOWED_TRANSITIONS.put(LabOrderStatus.PENDING,     EnumSet.of(LabOrderStatus.COLLECTED,   LabOrderStatus.CANCELLED));
        ALLOWED_TRANSITIONS.put(LabOrderStatus.COLLECTED,   EnumSet.of(LabOrderStatus.RECEIVED,    LabOrderStatus.CANCELLED));
        ALLOWED_TRANSITIONS.put(LabOrderStatus.RECEIVED,    EnumSet.of(LabOrderStatus.IN_PROGRESS, LabOrderStatus.CANCELLED));
        ALLOWED_TRANSITIONS.put(LabOrderStatus.IN_PROGRESS, EnumSet.of(LabOrderStatus.RESULTED,    LabOrderStatus.CANCELLED));
        ALLOWED_TRANSITIONS.put(LabOrderStatus.RESULTED,    EnumSet.of(LabOrderStatus.VERIFIED,    LabOrderStatus.CANCELLED));
        ALLOWED_TRANSITIONS.put(LabOrderStatus.VERIFIED,    EnumSet.of(LabOrderStatus.COMPLETED,   LabOrderStatus.CANCELLED));
    }

    @Override
    @Transactional
    public LabOrderResponseDTO transitionLabOrderStatus(UUID id, LabOrderStatus toStatus, Locale locale) {
        LabOrder labOrder = labOrderRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(LAB_ORDER_NOT_FOUND));

        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (!labOrder.isHandledBy(hospitalId)) {
            throw new ResourceNotFoundException(LAB_ORDER_NOT_FOUND);
        }

        LabOrderStatus current = labOrder.getStatus();
        UUID orderingHospitalId = labOrder.getHospital().getId();
        // B1: lab-side steps are authorised at the hospital the actor works in
        // (the ordering hospital or the performing laboratory); cancelling is
        // an ordering-side decision and stays with the ordering hospital.
        UUID authorityHospitalId = hospitalId != null ? hospitalId : orderingHospitalId;
        validateStatusTransition(current, toStatus, authorityHospitalId, orderingHospitalId);
        labOrder.setStatus(toStatus);
        return labOrderMapper.toLabOrderResponseDTO(labOrderRepository.save(labOrder));
    }

    private void validateStatusTransition(LabOrderStatus from, LabOrderStatus to,
                                          UUID hospitalId, UUID orderingHospitalId) {
        Set<LabOrderStatus> allowed = ALLOWED_TRANSITIONS.getOrDefault(from, Set.of());
        if (!allowed.contains(to)) {
            throw new BusinessException(
                "Invalid lab order status transition: " + from.name() + " \u2192 " + to.name() + ".");
        }

        UUID currentUserId = roleValidator.getCurrentUserId();

        if (to == LabOrderStatus.CANCELLED) {
            if (!roleValidator.isLabManager(currentUserId, orderingHospitalId)
                    && !roleValidator.isHospitalAdmin(currentUserId, orderingHospitalId)
                    && !roleValidator.isSuperAdminFromAuth()) {
                throw new BusinessException("Only lab managers or admins may cancel a lab order.");
            }
            return;
        }

        switch (to) {
            case PENDING -> { /* Doctor/nurse/lab staff can acknowledge */ }
            case COLLECTED, RECEIVED -> requireLabStaff(currentUserId, hospitalId);
            case IN_PROGRESS, RESULTED -> requireLabScientistOrManager(currentUserId, hospitalId,
                    "advance analytical steps");
            case VERIFIED, COMPLETED -> requireLabScientistOrManager(currentUserId, hospitalId,
                    "verify or complete a lab order");
            default -> throw new BusinessException(
                "Status " + to.name() + " cannot be set via transition.");
        }
    }

    private String normalizeRequiredText(String value, String errorMessage) {
        String normalized = normalizeOptionalText(value);
        if (normalized == null) {
            throw new BusinessException(errorMessage);
        }
        return normalized;
    }

    private String normalizeOptionalText(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String resolvePrimaryDiagnosisCode(LabOrderRequestDTO request, LabOrder base) {
        String normalized = normalizeOptionalText(request.getPrimaryDiagnosisCode());
        if (normalized == null) {
            if (base != null) {
                return base.getPrimaryDiagnosisCode();
            }
            throw new BusinessException("Primary diagnosis code is required for lab orders.");
        }
        normalized = normalized.toUpperCase(Locale.ROOT);
        if (!DiagnosisCodeValidator.isValidIcd10(normalized)) {
            throw new BusinessException("Invalid ICD-10 diagnosis code: " + normalized);
        }
        return normalized;
    }

    private List<String> resolveAdditionalDiagnosisCodes(LabOrderRequestDTO request, LabOrder base) {
        List<String> requested = DiagnosisCodeValidator.normalizeList(request.getAdditionalDiagnosisCodes());
        if (requested.isEmpty()) {
            if (request.getAdditionalDiagnosisCodes() == null && base != null && base.getAdditionalDiagnosisCodes() != null) {
                return new ArrayList<>(base.getAdditionalDiagnosisCodes());
            }
            return List.of();
        }
        for (String code : requested) {
            if (!DiagnosisCodeValidator.isValidIcd10(code)) {
                throw new BusinessException("Invalid ICD-10 diagnosis code: " + code);
            }
        }
        return requested;
    }

    private LabOrderChannel resolveOrderChannel(String requestedChannel, LabOrder base) {
        if (requestedChannel != null && !requestedChannel.isBlank()) {
            try {
                return LabOrderChannel.fromCode(requestedChannel);
            } catch (IllegalArgumentException ex) {
                throw new BusinessException(ex.getMessage());
            }
        }
        if (base != null && base.getOrderChannel() != null) {
            return base.getOrderChannel();
        }
        return LabOrderChannel.ELECTRONIC;
    }

    private String resolveOrderChannelOther(LabOrderChannel channel, LabOrderRequestDTO request, LabOrder base) {
        if (channel != LabOrderChannel.OTHER) {
            return null;
        }
        String provided = normalizeOptionalText(request.getOrderChannelOther());
        if (provided != null) {
            return provided;
        }
        if (base != null && base.getOrderChannelOther() != null) {
            return base.getOrderChannelOther();
        }
        throw new BusinessException("orderChannelOther must be provided when orderChannel is OTHER.");
    }

    private String resolveOrderingProviderNpi(Staff staff, String override, LabOrder base) {
        String staffNpi = normalizeOptionalText(staff.getNpi());
        String requestNpi = normalizeOptionalText(override);
        String existing = base != null ? base.getOrderingProviderNpi() : null;
        String resolved;
        if (staffNpi != null) {
            resolved = staffNpi;
        } else if (requestNpi != null) {
            resolved = requestNpi;
        } else {
            resolved = existing;
        }
        if (resolved == null) {
            // NPI is not universally required — nurses and some allied-health
            // professionals are authorised to place lab orders but do not carry
            // an individual NPI.
            return null;
        }
        if (!resolved.matches("\\d{10}")) {
            throw new BusinessException("NPI must be a 10-digit numeric identifier.");
        }
        if (staffNpi != null && requestNpi != null && !staffNpi.equals(requestNpi)) {
            throw new BusinessException("Provided NPI does not match the staff member's recorded NPI.");
        }
        return resolved;
    }

    private String resolveProviderSignatureDigest(String signaturePayload, LabOrder base, boolean isNew) {
        String normalized = normalizeOptionalText(signaturePayload);
        if (normalized == null) {
            if (base != null && base.getProviderSignatureDigest() != null) {
                return base.getProviderSignatureDigest();
            }
            if (isNew) {
                throw new BusinessException("An electronic signature attestation is required for lab orders.");
            }
            return null;
        }
        return computeSignatureDigest(normalized);
    }

    private LocalDateTime resolveSignedAt(LocalDateTime requestedSignedAt, LabOrder base) {
        if (requestedSignedAt != null) {
            return requestedSignedAt;
        }
        if (base != null && base.getSignedAt() != null) {
            return base.getSignedAt();
        }
        return LocalDateTime.now();
    }

    private void applyStandingOrderMetadata(LabOrder labOrder, LabOrderRequestDTO request, LabOrder base, LocalDateTime orderDatetime) {
        boolean standingOrder = resolveStandingOrderFlag(request, base);
        labOrder.setStandingOrder(standingOrder);
        if (!standingOrder) {
            labOrder.setStandingOrderExpiresAt(null);
            labOrder.setStandingOrderLastReviewedAt(null);
            labOrder.setStandingOrderReviewDueAt(null);
            labOrder.setStandingOrderReviewIntervalDays(null);
            labOrder.setStandingOrderReviewNotes(null);
            return;
        }
        LocalDateTime expiresAt = resolveStandingOrderExpiresAt(request, base, orderDatetime);
        LocalDateTime lastReviewedAt = resolveStandingOrderLastReviewedAt(request, base);
        int reviewIntervalDays = resolveStandingOrderReviewInterval(request, base);
        LocalDateTime reviewDueAt = computeStandingOrderReviewDueAt(lastReviewedAt, reviewIntervalDays);

        labOrder.setStandingOrderExpiresAt(expiresAt);
        labOrder.setStandingOrderLastReviewedAt(lastReviewedAt);
        labOrder.setStandingOrderReviewIntervalDays(reviewIntervalDays);
        labOrder.setStandingOrderReviewDueAt(reviewDueAt);
        labOrder.setStandingOrderReviewNotes(resolveStandingOrderReviewNotes(request, base));
    }

    private boolean resolveDocumentationSharedFlag(LabOrderChannel channel, LabOrderRequestDTO request, LabOrder base) {
        Boolean requested = request.getDocumentationSharedWithLab();
        if (requested != null) {
            return requested;
        }
        if (base != null) {
            return base.isDocumentationSharedWithLab();
        }
        return channel == LabOrderChannel.PORTAL || channel == LabOrderChannel.ELECTRONIC;
    }

    private void enforceDocumentationCompliance(LabOrderChannel channel, boolean documentationShared) {
        // Medicare documentation-sharing requirement applies only to electronic/portal channels.
        // Physical channels (walk-in, written, fax, phone) handle paperwork outside the system.
        if (channel != LabOrderChannel.PORTAL && channel != LabOrderChannel.ELECTRONIC) {
            return;
        }
        if (!documentationShared) {
            String channelName = channel.name().toLowerCase(Locale.ENGLISH);
            throw new BusinessException("Medicare guidelines require lab orders submitted via " + channelName + " channel to include documentation shared with the performing laboratory.");
        }
    }

    private String resolveDocumentationReference(boolean shared, LabOrderRequestDTO request, LabOrder base) {
        if (!shared) {
            return null;
        }
        String requestedReference = normalizeOptionalText(request.getDocumentationReference());
        if (requestedReference != null) {
            return requestedReference;
        }
        if (base != null && base.getDocumentationReference() != null) {
            return base.getDocumentationReference();
        }
        // Reference is helpful but not mandatory — the documentation-sharing flag
        // is often auto-defaulted for PORTAL/ELECTRONIC channels where the system
        // itself IS the documentation trail.
        return null;
    }

    private boolean resolveStandingOrderFlag(LabOrderRequestDTO request, LabOrder base) {
        Boolean requestedFlag = request.getStandingOrder();
        if (requestedFlag != null) {
            return requestedFlag;
        }
        if (base != null) {
            return base.isStandingOrder();
        }
        return false;
    }

    private LocalDateTime resolveStandingOrderExpiresAt(LabOrderRequestDTO request, LabOrder base, LocalDateTime orderDatetime) {
        LocalDateTime expiresAt = request.getStandingOrderExpiresAt();
        if (expiresAt == null && base != null) {
            expiresAt = base.getStandingOrderExpiresAt();
        }
        if (expiresAt == null) {
            throw new BusinessException("Standing orders must include an expiration timestamp.");
        }
        if (orderDatetime != null && expiresAt.isBefore(orderDatetime)) {
            throw new BusinessException("Standing order expiration must be after the order date.");
        }
        return expiresAt;
    }

    private LocalDateTime resolveStandingOrderLastReviewedAt(LabOrderRequestDTO request, LabOrder base) {
        LocalDateTime lastReviewedAt = request.getStandingOrderLastReviewedAt();
        if (lastReviewedAt == null && base != null) {
            lastReviewedAt = base.getStandingOrderLastReviewedAt();
        }
        if (lastReviewedAt == null) {
            throw new BusinessException("Standing orders must include the last review timestamp.");
        }
        return lastReviewedAt;
    }

    private int resolveStandingOrderReviewInterval(LabOrderRequestDTO request, LabOrder base) {
        Integer reviewIntervalDays = request.getStandingOrderReviewIntervalDays();
        if ((reviewIntervalDays == null || reviewIntervalDays <= 0) && base != null && base.getStandingOrderReviewIntervalDays() != null) {
            reviewIntervalDays = base.getStandingOrderReviewIntervalDays();
        }
        if (reviewIntervalDays == null || reviewIntervalDays <= 0) {
            throw new BusinessException("Standing orders require a positive review interval (days).");
        }
        return reviewIntervalDays;
    }

    private LocalDateTime computeStandingOrderReviewDueAt(LocalDateTime lastReviewedAt, int reviewIntervalDays) {
        LocalDateTime reviewDueAt = lastReviewedAt.plusDays(reviewIntervalDays);
        if (reviewDueAt.isBefore(LocalDateTime.now())) {
            throw new BusinessException("Standing order review is overdue. Please review before placing new orders.");
        }
        return reviewDueAt;
    }

    private String resolveStandingOrderReviewNotes(LabOrderRequestDTO request, LabOrder base) {
        String rawNotes = request.getStandingOrderReviewNotes();
        String reviewNotes = normalizeOptionalText(rawNotes);
        if (reviewNotes != null) {
            return reviewNotes;
        }
        if (rawNotes != null) {
            return null;
        }
        return base != null ? base.getStandingOrderReviewNotes() : null;
    }

    private String computeSignatureDigest(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            return HEX_FORMAT.formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    private void requireLabStaff(UUID userId, UUID hospitalId) {
        if (!roleValidator.isLabStaff(userId, hospitalId)) {
            throw new BusinessException("Only lab staff may perform specimen collection/receipt operations.");
        }
    }

    private void requireLabScientistOrManager(UUID userId, UUID hospitalId, String action) {
        if (!roleValidator.isLabScientist(userId, hospitalId) && !roleValidator.isLabManager(userId, hospitalId)) {
            throw new BusinessException("Only lab scientists or managers may " + action + ".");
        }
    }
}
