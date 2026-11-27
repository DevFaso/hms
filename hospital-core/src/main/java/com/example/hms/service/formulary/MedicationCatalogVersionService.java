package com.example.hms.service.formulary;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.MedicationCatalogChangeType;
import com.example.hms.enums.MedicationCatalogDecisionBasis;
import com.example.hms.enums.MedicationCatalogVersionKind;
import com.example.hms.enums.MedicationCatalogVersionStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.model.User;
import com.example.hms.model.medication.DoseLimitSnapshot;
import com.example.hms.model.medication.FormularySnapshot;
import com.example.hms.model.medication.MedicationCatalogApprovedWrite;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.model.medication.MedicationCatalogVersion;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.medication.MedicationCatalogVersionResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.MedicationCatalogItemRepository;
import com.example.hms.repository.MedicationCatalogVersionRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.utility.RoleValidator;
import com.example.hms.utility.TransactionCallbacks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Go-live D2 (V385): the approval circuit of the medication catalogue, the same four eyes as a laboratory
 * definition (an author, then an independent approver), per hospital.
 *
 * <p>A change to a formulary entry or to its dose limits is a new {@link MedicationCatalogVersion}, « en attente
 * de validation ». Nothing it carries is in force: prescribing and dispensing keep reading the catalogue row,
 * which only {@link #approve} changes, through {@link MedicationCatalogApprovedWrite}, when a different person
 * with the right role says yes ({@link MedicationCatalogApprovalPolicy}). A refusal carries its reason; the
 * author may take a waiting version back. Every version stays as the entry's history.
 *
 * <p>Every read and every decision is at the caller's acting hospital, never a hospital the request names.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class MedicationCatalogVersionService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String VERSION_NOT_FOUND = "medication.catalog.version.notfound";
    private static final String CATALOG_NOT_FOUND = "medication.catalog.notfound";
    private static final String AUDIT_ENTITY = "MEDICATION_CATALOG_VERSION";

    private final MedicationCatalogVersionRepository versionRepository;
    private final MedicationCatalogItemRepository catalogRepository;
    private final HospitalRepository hospitalRepository;
    private final UserRepository userRepository;
    private final RoleValidator roleValidator;
    private final MedicationCatalogApprovalPolicy policy;
    private final AuditEventLogService auditEventLogService;
    /** QA D4180: the same medicine is not entered twice. */
    private final MedicationCatalogDuplicates duplicates;

    /* ───────────────────────────── proposing ───────────────────────────── */

    /** A new entry: it is in the catalogue only once approved. {@code hospital} null is a platform entry. */
    public MedicationCatalogVersion proposeNewEntry(Hospital hospital, FormularySnapshot proposed, String reason) {
        duplicates.requireNewAtProposal(hospital == null ? null : hospital.getId(), proposed, null);
        MedicationCatalogVersion version = MedicationCatalogVersion.builder()
            .hospitalId(hospital == null ? null : hospital.getId())
            .kind(MedicationCatalogVersionKind.FORMULARY)
            .changeType(MedicationCatalogChangeType.CREATE)
            .versionNumber(1)
            .itemLabel(proposed.label())
            .payload(write(proposed))
            .build();
        return savePending(version, reason);
    }

    /**
     * New formulary content for an entry in the catalogue, or the content in force submitted as it stands so
     * that an entry nobody approved yet gets its approval.
     */
    public MedicationCatalogVersion proposeFormulary(MedicationCatalogItem item, FormularySnapshot proposed,
                                                     String reason) {
        FormularySnapshot live = FormularySnapshot.of(item);
        boolean unchanged = proposed.clinicalDifferences(live).isEmpty();
        requireSomethingToApprove(unchanged, item.getFormularyApprovedVersion());
        if (changesIdentity(proposed, live)) {
            // QA D4180: renaming an entry (or changing its form or strength) into one that is already there.
            duplicates.requireNewAtProposal(item.getHospital() == null ? null : item.getHospital().getId(),
                proposed, item.getId());
        }
        MedicationCatalogVersion version = versionOf(item, MedicationCatalogVersionKind.FORMULARY, unchanged);
        version.setItemLabel(proposed.label());
        version.setPayload(write(proposed));
        version.setPreviousPayload(write(live));
        return savePending(version, reason);
    }

    /** New dose limits for an entry, or the limits in force submitted as they stand. */
    public MedicationCatalogVersion proposeDoseLimits(MedicationCatalogItem item, DoseLimitSnapshot proposed,
                                                      String reason) {
        DoseLimitSnapshot live = DoseLimitSnapshot.of(item);
        boolean unchanged = proposed.differences(live).isEmpty();
        requireSomethingToApprove(unchanged, item.getDoseLimitApprovedVersion());
        if (unchanged && !live.hasAnyLimit()) {
            // No limit in force and none proposed: an empty version would approve nothing.
            throw new BusinessException("medication.catalog.version.unchanged");
        }
        MedicationCatalogVersion version = versionOf(item, MedicationCatalogVersionKind.DOSE_LIMIT, unchanged);
        version.setItemLabel(FormularySnapshot.of(item).label());
        version.setPayload(write(proposed));
        version.setPreviousPayload(write(live));
        return savePending(version, reason);
    }

    /**
     * Whether a version is compared with the catalogue: it changes what an entry is compared by (its name, its
     * form, its strength), or it puts a withdrawn entry back, whose twin may have been entered meanwhile.
     */
    private static boolean changesIdentity(FormularySnapshot proposed, FormularySnapshot before) {
        return before == null || (proposed.active() && !before.active())
            || !MedicationCatalogDuplicates.exact(proposed, before);
    }

    /**
     * QA D4190 (V426): the entry is taken out of the catalogue, now, and its history says so: who, when, why. The
     * caller deactivates the row; this writes the line. A reason is required, as for any change to what is
     * prescribed and dispensed.
     */
    public MedicationCatalogVersion recordWithdrawal(MedicationCatalogItem item, String reason, String reasonField) {
        String why = FormularySnapshot.text(reason);
        if (why == null) {
            throw new FieldBusinessException(reasonField, "medication.catalog.withdrawal.reasonRequired");
        }
        if (why.length() > 1000) {
            throw new FieldBusinessException(reasonField, "medication.catalog.withdrawal.reasonTooLong");
        }
        UUID author = roleValidator.getCurrentUserId();
        if (author == null) {
            throw new BusinessException("medication.catalog.version.authorUnknown");
        }
        FormularySnapshot live = FormularySnapshot.of(item);
        LocalDateTime at = now();
        MedicationCatalogVersion line = MedicationCatalogVersion.builder()
            .hospitalId(item.getHospital() == null ? null : item.getHospital().getId())
            .catalogItemId(item.getId())
            .kind(MedicationCatalogVersionKind.FORMULARY)
            .changeType(MedicationCatalogChangeType.WITHDRAWAL)
            .versionNumber(versionRepository.highestVersionNumber(item.getId(), MedicationCatalogVersionKind.FORMULARY) + 1)
            .status(MedicationCatalogVersionStatus.RECORDED)
            .itemLabel(live.label())
            .payload(write(live.withActive(false)))
            .previousPayload(write(live))
            .changeReason(why)
            .proposedBy(author)
            .proposedAt(at)
            .decidedBy(author)
            .decidedAt(at)
            .build();
        MedicationCatalogVersion saved = versionRepository.save(line);
        audit(saved, "WITHDRAWN_FROM_CATALOGUE", author);
        return saved;
    }

    /** Values already in force AND already approved: there is nothing for a second person to decide. */
    private static void requireSomethingToApprove(boolean unchanged, Integer approvedVersion) {
        if (unchanged && approvedVersion != null) {
            throw new BusinessException("medication.catalog.version.unchanged");
        }
    }

    private MedicationCatalogVersion versionOf(MedicationCatalogItem item, MedicationCatalogVersionKind kind,
                                               boolean unchanged) {
        // One version in progress at a time, as for a laboratory definition: a second would race the first.
        if (!versionRepository.findByCatalogItemIdAndKindAndStatus(item.getId(), kind,
                MedicationCatalogVersionStatus.PENDING_APPROVAL).isEmpty()) {
            throw new BusinessException("medication.catalog.version.pendingExists");
        }
        return MedicationCatalogVersion.builder()
            .hospitalId(item.getHospital() == null ? null : item.getHospital().getId())
            .catalogItemId(item.getId())
            .kind(kind)
            .changeType(unchanged ? MedicationCatalogChangeType.RATIFY : MedicationCatalogChangeType.UPDATE)
            .versionNumber(versionRepository.highestVersionNumber(item.getId(), kind) + 1)
            .build();
    }

    private MedicationCatalogVersion savePending(MedicationCatalogVersion version, String reason) {
        String why = FormularySnapshot.text(reason);
        if (why == null) {
            throw new FieldBusinessException("changeReason", "medication.catalog.version.reasonRequired");
        }
        UUID author = roleValidator.getCurrentUserId();
        if (author == null) {
            throw new BusinessException("medication.catalog.version.authorUnknown");
        }
        version.setStatus(MedicationCatalogVersionStatus.PENDING_APPROVAL);
        version.setChangeReason(why);
        version.setProposedBy(author);
        version.setProposedAt(now());
        MedicationCatalogVersion saved = versionRepository.save(version);
        audit(saved, "PROPOSED", author);
        return saved;
    }

    /* ───────────────────────────── deciding ───────────────────────────── */

    /** The approval: the version takes effect, now and only now. */
    public MedicationCatalogVersionResponseDTO approve(UUID versionId, String note) {
        MedicationCatalogVersion version = pendingInScope(versionId);
        UUID approver = roleValidator.getCurrentUserId();
        MedicationCatalogDecisionBasis basis = policy.requireMayApprove(version, approver);

        if (version.getKind() == MedicationCatalogVersionKind.FORMULARY) {
            // QA D4180: its twin may have been approved while this one waited.
            FormularySnapshot proposed = readFormulary(version.getPayload());
            FormularySnapshot before = version.getPreviousPayload() == null ? null
                : readFormulary(version.getPreviousPayload());
            if (changesIdentity(proposed, before)) {
                duplicates.requireNewAtApproval(version, proposed);
            }
        }

        LocalDateTime at = now();
        MedicationCatalogItem item = version.getChangeType() == MedicationCatalogChangeType.CREATE
            ? MedicationCatalogApprovedWrite.newEntry(readFormulary(version.getPayload()),
                hospitalOf(version), version.getVersionNumber())
            : entryOf(version);
        if (version.getChangeType() != MedicationCatalogChangeType.CREATE) {
            supersedeTheVersionInForce(version);
            if (version.getKind() == MedicationCatalogVersionKind.FORMULARY) {
                MedicationCatalogApprovedWrite.formulary(item, formularyToApply(version, item),
                    version.getVersionNumber());
            } else {
                MedicationCatalogApprovedWrite.doseLimits(item,
                    readDoseLimits(version.getPayload()), version.getVersionNumber(), approver, at);
            }
        }
        MedicationCatalogItem saved = catalogRepository.save(item);

        version.setCatalogItemId(saved.getId());
        version.setStatus(MedicationCatalogVersionStatus.APPROVED);
        version.setDecidedBy(approver);
        version.setDecidedAt(at);
        version.setDecisionBasis(basis);
        version.setDecisionReason(FormularySnapshot.text(note));
        MedicationCatalogVersion decided = versionRepository.save(version);
        audit(decided, "APPROVED", approver);
        return toDto(decided);
    }

    /**
     * What an approved formulary version writes. An entry withdrawn from the catalogue AFTER the version was
     * proposed stays withdrawn: a version puts an entry back only when that is what it proposed (it was
     * withdrawn when the version was written, and the version asks for it back). An approval of a new strength
     * never puts a recalled medicine back in front of prescribers by the way.
     */
    private static FormularySnapshot formularyToApply(MedicationCatalogVersion version, MedicationCatalogItem item) {
        FormularySnapshot proposed = readFormulary(version.getPayload());
        FormularySnapshot before = version.getPreviousPayload() == null ? null
            : readFormulary(version.getPreviousPayload());
        boolean putsItBack = before != null && !before.active() && proposed.active();
        return putsItBack ? proposed : proposed.withActive(item.isActive());
    }

    /** The refusal, with its reason. Nothing changes in the catalogue. */
    public MedicationCatalogVersionResponseDTO reject(UUID versionId, String reason) {
        MedicationCatalogVersion version = pendingInScope(versionId);
        UUID decider = roleValidator.getCurrentUserId();
        MedicationCatalogDecisionBasis basis = policy.requireDecidingRole(version, decider);
        String why = FormularySnapshot.text(reason);
        if (why == null) {
            throw new FieldBusinessException("reason", "medication.catalog.version.rejectionReasonRequired");
        }
        version.setDecisionBasis(basis);
        return close(version, MedicationCatalogVersionStatus.REJECTED, decider, why, "REJECTED");
    }

    /** The author takes a waiting version back. Nothing changes in the catalogue. */
    public MedicationCatalogVersionResponseDTO withdraw(UUID versionId) {
        MedicationCatalogVersion version = pendingInScope(versionId);
        UUID caller = roleValidator.getCurrentUserId();
        if (caller == null || !caller.equals(version.getProposedBy())) {
            throw new BusinessException("medication.catalog.version.withdrawOwnOnly");
        }
        return close(version, MedicationCatalogVersionStatus.WITHDRAWN, caller, null, "WITHDRAWN");
    }

    private MedicationCatalogVersionResponseDTO close(MedicationCatalogVersion version,
                                                      MedicationCatalogVersionStatus status, UUID by, String why,
                                                      String step) {
        version.setStatus(status);
        version.setDecidedBy(by);
        version.setDecidedAt(now());
        version.setDecisionReason(why);
        MedicationCatalogVersion decided = versionRepository.save(version);
        audit(decided, step, by);
        return toDto(decided);
    }

    /** The version, at the caller's hospital, still waiting; locked so that two decisions cannot both land. */
    private MedicationCatalogVersion pendingInScope(UUID versionId) {
        UUID scope = roleValidator.requireActiveHospitalId();
        MedicationCatalogVersion version = versionRepository.findForDecision(versionId, scope)
            .orElseThrow(() -> new ResourceNotFoundException(VERSION_NOT_FOUND));
        if (version.getStatus() != MedicationCatalogVersionStatus.PENDING_APPROVAL) {
            throw new BusinessException("medication.catalog.version.alreadyDecided");
        }
        return version;
    }

    private Hospital hospitalOf(MedicationCatalogVersion version) {
        UUID hospitalId = version.getHospitalId();
        if (hospitalId == null) {
            return null;
        }
        return hospitalRepository.findClinicalById(hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException("hospital.notfound", hospitalId));
    }

    /**
     * The catalogue entry a version is for, only while it still belongs where the version says. Locked: an
     * administrator's edit or withdrawal of the same row waits for this approval, and this one for theirs.
     */
    private MedicationCatalogItem entryOf(MedicationCatalogVersion version) {
        MedicationCatalogItem item = catalogRepository.findLockedById(version.getCatalogItemId())
            .orElseThrow(() -> new ResourceNotFoundException(CATALOG_NOT_FOUND));
        UUID owner = item.getHospital() == null ? null : item.getHospital().getId();
        if (!java.util.Objects.equals(owner, version.getHospitalId())) {
            throw new ResourceNotFoundException(CATALOG_NOT_FOUND);
        }
        return item;
    }

    private void supersedeTheVersionInForce(MedicationCatalogVersion replacement) {
        for (MedicationCatalogVersion inForce : versionRepository.findByCatalogItemIdAndKindAndStatus(
                replacement.getCatalogItemId(), replacement.getKind(), MedicationCatalogVersionStatus.APPROVED)) {
            inForce.setStatus(MedicationCatalogVersionStatus.SUPERSEDED);
            // Flushed now: the partial unique index "one version in force" must see it gone before the new one lands.
            versionRepository.saveAndFlush(inForce);
        }
    }

    /* ───────────────────────────── reading ───────────────────────────── */

    /** The queue (or the decided versions) of the caller's hospital; the platform's own in global view. */
    @Transactional(readOnly = true)
    public Page<MedicationCatalogVersionResponseDTO> list(MedicationCatalogVersionStatus status,
                                                          MedicationCatalogVersionKind kind, Pageable pageable) {
        UUID scope = roleValidator.requireActiveHospitalId();
        Page<MedicationCatalogVersion> page = versionRepository.findInScope(scope, status, kind, pageable);
        Map<UUID, String> names = namesOf(page.getContent());
        UUID caller = roleValidator.getCurrentUserId();
        MedicationCatalogDuplicates.Known known = knownFor(page.getContent(), scope);
        return page.map(version -> toDto(version, names, caller, known));
    }

    /** Every version of one entry, the latest first: who proposed what, when, why, and what became of it. */
    @Transactional(readOnly = true)
    public List<MedicationCatalogVersionResponseDTO> history(UUID catalogItemId) {
        UUID scope = roleValidator.requireActiveHospitalId();
        MedicationCatalogItem item = catalogRepository.findById(catalogItemId)
            .orElseThrow(() -> new ResourceNotFoundException(CATALOG_NOT_FOUND));
        UUID owner = item.getHospital() == null ? null : item.getHospital().getId();
        // A hospital reads its own entries and the platform's; only global view reads any hospital's.
        if (scope != null && owner != null && !owner.equals(scope)) {
            throw new ResourceNotFoundException(CATALOG_NOT_FOUND);
        }
        List<MedicationCatalogVersion> versions = versionRepository.findHistoryInScope(catalogItemId, owner);
        Map<UUID, String> names = namesOf(versions);
        UUID caller = roleValidator.getCurrentUserId();
        MedicationCatalogDuplicates.Known known = knownFor(versions, owner);
        return versions.stream().map(version -> toDto(version, names, caller, known)).toList();
    }

    /** The versions waiting on these entries, by entry then kind: one statement for a page of the catalogue. */
    @Transactional(readOnly = true)
    public Map<UUID, Map<MedicationCatalogVersionKind, UUID>> pendingByEntry(Collection<UUID> catalogItemIds) {
        Map<UUID, Map<MedicationCatalogVersionKind, UUID>> pending = new HashMap<>();
        if (catalogItemIds == null || catalogItemIds.isEmpty()) {
            return pending;
        }
        for (MedicationCatalogVersion version : versionRepository.findByCatalogItemIdInAndStatus(catalogItemIds,
                MedicationCatalogVersionStatus.PENDING_APPROVAL)) {
            pending.computeIfAbsent(version.getCatalogItemId(),
                id -> new EnumMap<>(MedicationCatalogVersionKind.class)).put(version.getKind(), version.getId());
        }
        return pending;
    }

    public MedicationCatalogVersionResponseDTO toDto(MedicationCatalogVersion version) {
        return toDto(version, namesOf(List.of(version)), roleValidator.getCurrentUserId(),
            knownFor(List.of(version), version.getHospitalId()));
    }

    /** QA D4180: the catalogue to compare with, read only when one of the versions still waits for its decision. */
    private MedicationCatalogDuplicates.Known knownFor(List<MedicationCatalogVersion> shown, UUID hospitalId) {
        boolean anyWaiting = shown.stream().anyMatch(version -> version.getKind() == MedicationCatalogVersionKind.FORMULARY
            && version.getStatus() == MedicationCatalogVersionStatus.PENDING_APPROVAL);
        return anyWaiting ? duplicates.knownAt(hospitalId) : null;
    }

    private MedicationCatalogVersionResponseDTO toDto(MedicationCatalogVersion version, Map<UUID, String> names,
                                                      UUID caller, MedicationCatalogDuplicates.Known known) {
        boolean pending = version.getStatus() == MedicationCatalogVersionStatus.PENDING_APPROVAL;
        MedicationCatalogVersionResponseDTO dto = MedicationCatalogVersionResponseDTO.builder()
            .id(version.getId())
            .hospitalId(version.getHospitalId())
            .catalogItemId(version.getCatalogItemId())
            .kind(version.getKind())
            .changeType(version.getChangeType())
            .versionNumber(version.getVersionNumber())
            .status(version.getStatus())
            // A formulary version is named from what it carries (QA D4189: a label stored with its strength
            // twice is read right); dose limits keep the name they were proposed under.
            .itemLabel(version.getKind() == MedicationCatalogVersionKind.FORMULARY
                ? readFormulary(version.getPayload()).label() : version.getItemLabel())
            .changeReason(version.getChangeReason())
            .proposedById(version.getProposedBy())
            .proposedByName(names.get(version.getProposedBy()))
            .proposedAt(version.getProposedAt())
            .decidedById(version.getDecidedBy())
            .decidedByName(version.getDecidedBy() == null ? null : names.get(version.getDecidedBy()))
            .decidedAt(version.getDecidedAt())
            .decisionReason(version.getDecisionReason())
            .decisionBasis(version.getDecisionBasis())
            // The hint the queue offers its buttons on: the server refuses the same way (QA D722's lesson).
            .callerMayApprove(pending && policy.mayApprove(version, caller))
            .callerMayReject(pending && policy.holdsDecidingRole(version, caller))
            .ownProposal(caller != null && caller.equals(version.getProposedBy()))
            .build();
        if (version.getKind() == MedicationCatalogVersionKind.FORMULARY) {
            FormularySnapshot proposed = readFormulary(version.getPayload());
            FormularySnapshot previous = version.getPreviousPayload() == null ? null
                : readFormulary(version.getPreviousPayload());
            dto.setFormulary(proposed);
            dto.setPreviousFormulary(previous);
            dto.setChangedFields(previous == null ? List.of() : proposed.clinicalDifferences(previous));
            if (pending && known != null) {
                dto.setSimilarEntries(known.similarTo(proposed, version.getCatalogItemId(), version.getId()));
            }
        } else {
            DoseLimitSnapshot proposed = readDoseLimits(version.getPayload());
            DoseLimitSnapshot previous = version.getPreviousPayload() == null ? null
                : readDoseLimits(version.getPreviousPayload());
            dto.setDoseLimits(proposed);
            dto.setPreviousDoseLimits(previous);
            dto.setChangedFields(previous == null ? List.of() : proposed.differences(previous));
        }
        return dto;
    }

    private Map<UUID, String> namesOf(List<MedicationCatalogVersion> versions) {
        Set<UUID> ids = new HashSet<>();
        for (MedicationCatalogVersion version : versions) {
            ids.add(version.getProposedBy());
            if (version.getDecidedBy() != null) {
                ids.add(version.getDecidedBy());
            }
        }
        ids.remove(null);
        Map<UUID, String> names = new HashMap<>();
        if (ids.isEmpty()) {
            return names;
        }
        for (User user : userRepository.findAllById(ids)) {
            String first = user.getFirstName() == null ? "" : user.getFirstName().trim();
            String last = user.getLastName() == null ? "" : user.getLastName().trim();
            String full = (first + " " + last).trim();
            names.put(user.getId(), full.isEmpty() ? user.getUsername() : full);
        }
        return names;
    }

    /**
     * QA D4180: what already looks like the entry someone is about to propose, at the caller's hospital. The form
     * asks before it sends, so the proposer sees the entry that is there instead of creating its twin.
     */
    @Transactional(readOnly = true)
    public List<com.example.hms.payload.dto.medication.MedicationCatalogSimilarEntryDTO> similarEntries(
            FormularySnapshot proposed, UUID ownItemId) {
        // The bounds of the catalogue columns: this read takes its values from the query string.
        requireWithin("nameFr", proposed.nameFr());
        requireWithin("form", proposed.form());
        requireWithin("strength", proposed.strength());
        requireWithin("strengthUnit", proposed.strengthUnit());
        UUID scope = roleValidator.requireActiveHospitalId();
        return duplicates.knownAt(scope).similarTo(proposed, ownItemId, null);
    }

    /** No catalogue column holds more than this; a longer value is refused on its field. */
    static final int MAX_IDENTITY_LENGTH = 255;

    private static void requireWithin(String field, String value) {
        if (value != null && value.length() > MAX_IDENTITY_LENGTH) {
            throw new FieldBusinessException(field, "medication.catalog.similar.tooLong",
                String.valueOf(MAX_IDENTITY_LENGTH));
        }
    }

    /* ───────────────────────────── plumbing ───────────────────────────── */

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneId.systemDefault());
    }

    private static String write(Object snapshot) {
        try {
            return JSON.writeValueAsString(snapshot);
        } catch (JacksonException e) {
            throw new IllegalStateException("medication.catalog.version.unwritable", e);
        }
    }

    static FormularySnapshot readFormulary(String json) {
        try {
            return JSON.readValue(json, FormularySnapshot.class);
        } catch (JacksonException e) {
            throw new IllegalStateException("medication.catalog.version.unreadable", e);
        }
    }

    private static DoseLimitSnapshot readDoseLimits(String json) {
        try {
            return JSON.readValue(json, DoseLimitSnapshot.class);
        } catch (JacksonException e) {
            throw new IllegalStateException("medication.catalog.version.unreadable", e);
        }
    }

    /**
     * Each step is on the audit trail, named: ids, the kind, the number and the status, read here inside the
     * transaction and written after it commits (a rolled-back step leaves no row; a failed audit undoes nothing).
     */
    private void audit(MedicationCatalogVersion version, String step, UUID actor) {
        AuditEventRequestDTO row = AuditEventRequestDTO.builder()
            .userId(actor)
            .hospitalId(version.getHospitalId())
            .eventType(version.getKind() == MedicationCatalogVersionKind.DOSE_LIMIT
                ? AuditEventType.MEDICATION_DOSE_LIMITS_UPDATED : AuditEventType.CONFIGURATION_CHANGED)
            .eventDescription("Medication catalogue " + version.getKind() + " version " + version.getVersionNumber()
                + " " + step + " (" + version.getChangeType() + ") for '" + version.getItemLabel() + "'")
            .status(AuditStatus.SUCCESS)
            .resourceId(version.getId() == null ? null : version.getId().toString())
            .entityType(AUDIT_ENTITY)
            .build();
        UUID versionId = version.getId();
        TransactionCallbacks.afterCommit(() -> {
            try {
                auditEventLogService.logEvent(row);
            } catch (RuntimeException e) {
                log.warn("Audit not written for medication catalogue version {}: {}", versionId,
                    e.getClass().getSimpleName());
            }
        });
    }
}
