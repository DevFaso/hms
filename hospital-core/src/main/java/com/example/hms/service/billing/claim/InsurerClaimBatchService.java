package com.example.hms.service.billing.claim;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.InsurerClaimBatchStatus;
import com.example.hms.enums.PayerShareStatus;
import com.example.hms.exception.ConflictException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.InsurerClaimBatch;
import com.example.hms.model.InsurerClaimLine;
import com.example.hms.model.InsurerClaimRefusal;
import com.example.hms.model.InsurerClaimWriteOff;
import com.example.hms.model.InvoicePayerShare;
import com.example.hms.model.Payer;
import com.example.hms.payload.dto.billing.InsurerClaimBatchDTO;
import com.example.hms.payload.dto.billing.InsurerClaimBatchReceiptRequestDTO;
import com.example.hms.payload.dto.billing.InsurerClaimBatchRequestDTO;
import com.example.hms.payload.dto.billing.InsurerClaimBatchSharesRequestDTO;
import com.example.hms.payload.dto.billing.InsurerClaimBatchSubmitRequestDTO;
import com.example.hms.payload.dto.billing.InsurerClaimLineDTO;
import com.example.hms.payload.dto.billing.InsurerClaimPagesDTO;
import com.example.hms.payload.dto.billing.InsurerClaimRefusalDTO;
import com.example.hms.repository.InsurerClaimBatchRepository;
import com.example.hms.repository.InsurerClaimLineRepository;
import com.example.hms.repository.InsurerClaimRefusalRepository;
import com.example.hms.repository.InsurerClaimWriteOffRepository;
import com.example.hms.repository.InsurerPaymentAllocationRepository;
import com.example.hms.repository.InvoicePayerShareRepository;
import com.example.hms.repository.PayerRepository;
import com.example.hms.service.billing.BillingTextRenderer;
import com.example.hms.utility.MessageUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Insurer claims plan PR 2 (V455, QA D3983): the bordereau, the claim sheet a hospital sends to one payer for one
 * period.
 *
 * <ul>
 *   <li><b>Draft</b> ({@link #create}): the server proposes every OPEN share of the payer whose claim date (the
 *       service date; the discharge date for a hospital stay) is in the period and that is on no other bordereau.
 *       Shares are added or taken off whole ({@link #changeShares}); a share is on at most one bordereau at a
 *       time.</li>
 *   <li><b>Submit</b> ({@link #submit}): the bordereau takes its number, each line takes its snapshot, the totals are
 *       frozen and each share moves OPEN to CLAIMED, once. Sending again an already sent bordereau answers with it
 *       unchanged.</li>
 *   <li><b>Cancel</b> ({@link #cancel}): a sent bordereau the payer has not answered (no payment, refusal or
 *       write-off on any line) is cancelled with a reason by the hospital admin or the accountant; its shares are
 *       OPEN again. A draft is deleted ({@link #deleteDraft}).</li>
 * </ul>
 *
 * Warnings (a missing policy number, a coverage not verified or expired, a missing pre-authorisation, a date outside
 * the period) never block sending. Everything is at the acting hospital, audited after commit with ids only.
 */
@Service
@RequiredArgsConstructor
public class InsurerClaimBatchService {

    static final String BATCH_NOT_FOUND = "billing.claim.batchNotFound";
    public static final int PAGE_MAX = 100;
    private static final int PAGE_DEFAULT = 25;
    private static final int REFERENCE_MAX = 120;
    private static final int DOCUMENTS_MAX = 300;
    private static final UUID NO_ID = new UUID(0L, 0L);
    private static final Set<InsurerClaimBatchStatus> LISTED = EnumSet.of(InsurerClaimBatchStatus.DRAFT,
        InsurerClaimBatchStatus.SUBMITTED, InsurerClaimBatchStatus.PARTIALLY_SETTLED,
        InsurerClaimBatchStatus.SETTLED);

    private final InsurerClaimBatchRepository batchRepository;
    private final InsurerClaimLineRepository lineRepository;
    private final InvoicePayerShareRepository shareRepository;
    private final PayerRepository payerRepository;
    private final InsurerClaimRefusalRepository refusalRepository;
    private final InsurerClaimWriteOffRepository writeOffRepository;
    private final InsurerPaymentAllocationRepository allocationRepository;
    private final InsurerClaimLineReader reader;
    private final InsurerClaimAccess access;

    /* ------------------------------------------------------------------ draft */

    /** A new draft for one payer and one period, holding every share the period proposes. */
    @Transactional
    public InsurerClaimBatchDTO create(InsurerClaimBatchRequestDTO body) {
        InsurerClaimAccess.Actor actor = access.requireBuilder();
        if (body.getPeriodTo().isBefore(body.getPeriodFrom())) {
            throw new FieldBusinessException("periodTo", "billing.payerShare.periodReversed");
        }
        if (body.getClientRequestId() != null) {
            InsurerClaimBatch replay = batchRepository
                .findByHospitalIdAndClientRequestId(actor.hospitalId(), body.getClientRequestId()).orElse(null);
            if (replay != null) {
                if (!replay.getPayerId().equals(body.getPayerId())) {
                    throw new ConflictException(MessageUtil.resolve("billing.claim.clientRequestReused"));
                }
                return detailOf(replay);
            }
        }
        Payer payer = payerRepository.findByIdAndHospitalId(body.getPayerId(), actor.hospitalId())
            .orElseThrow(() -> new FieldBusinessException("payerId", "billing.claim.payerNotFound"));
        InsurerClaimBatch batch;
        try {
            batch = batchRepository.saveAndFlush(InsurerClaimBatch.builder()
                .hospitalId(actor.hospitalId())
                .payerId(payer.getId())
                .status(InsurerClaimBatchStatus.DRAFT)
                .periodFrom(body.getPeriodFrom())
                .periodTo(body.getPeriodTo())
                .clientRequestId(body.getClientRequestId())
                .createdBy(actor.userId())
                .build());
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException(MessageUtil.resolve("billing.claim.clientRequestReused"));
        }
        List<InvoicePayerShare> open = shareRepository.findClaimableOfPayer(payer.getId(), actor.hospitalId());
        Map<UUID, InsurerClaimLineReader.ShareFacts> facts = reader.read(open, actor.hospitalId(),
            LocaleContextHolder.getLocale());
        List<InsurerClaimLine> lines = new ArrayList<>();
        for (InsurerClaimLineReader.ShareFacts share : facts.values()) {
            LocalDate day = share.claimDate();
            boolean inPeriod = day != null && !day.isBefore(body.getPeriodFrom()) && !day.isAfter(body.getPeriodTo());
            if (inPeriod && !share.stayOpen() && share.complete()) {
                lines.addAll(draftLines(batch, share));
            }
        }
        saveLines(lines);
        access.audit(AuditEventType.INSURER_CLAIM_BATCH_DRAFTED, actor, batch.getId(), "Insurer claim draft created",
            "batch", batch.getId());
        return detailOf(batch);
    }

    /** Adds payer shares to a draft and takes shares off it. Each share goes whole. */
    @Transactional
    public InsurerClaimBatchDTO changeShares(UUID batchId, InsurerClaimBatchSharesRequestDTO body) {
        InsurerClaimAccess.Actor actor = access.requireBuilder();
        InsurerClaimBatch batch = lockDraft(batchId, actor.hospitalId());
        Set<UUID> remove = idsOf(body != null ? body.getRemove() : null);
        Set<UUID> add = idsOf(body != null ? body.getAdd() : null);
        for (UUID shareId : remove) {
            lineRepository.deleteShareFromBatch(batch.getId(), shareId, actor.hospitalId());
        }
        if (!add.isEmpty()) {
            List<InvoicePayerShare> shares = shareRepository.findByIdInAndHospitalId(add, actor.hospitalId());
            if (shares.size() != add.size()) {
                throw new FieldBusinessException("add", "billing.payerShare.notFound");
            }
            requireFree(shares, actor.hospitalId());
            Set<UUID> ofPayer = shareRepository.findClaimableOfPayer(batch.getPayerId(), actor.hospitalId()).stream()
                .map(InvoicePayerShare::getId).collect(Collectors.toSet());
            Map<UUID, InsurerClaimLineReader.ShareFacts> facts = reader.read(shares, actor.hospitalId(),
                LocaleContextHolder.getLocale());
            List<InsurerClaimLine> lines = new ArrayList<>();
            for (InvoicePayerShare share : shares) {
                InsurerClaimLineReader.ShareFacts known = facts.get(share.getId());
                if (share.getStatus() != PayerShareStatus.OPEN || !ofPayer.contains(share.getId()) || known == null) {
                    throw new FieldBusinessException("add", "billing.claim.shareNotClaimable");
                }
                if (known.stayOpen()) {
                    throw new FieldBusinessException("add", "billing.claim.stayNotDischarged");
                }
                if (!known.complete()) {
                    throw new FieldBusinessException("add", "billing.claim.shareLinesMissing");
                }
                lines.addAll(draftLines(batch, known));
            }
            saveLines(lines);
        }
        access.audit(AuditEventType.INSURER_CLAIM_BATCH_DRAFTED, actor, batch.getId(), "Insurer claim draft changed",
            "batch", batch.getId());
        return detailOf(batch);
    }

    /** The documents sent with one act of a draft, as a short list typed by the clerk; blank clears it. */
    @Transactional
    public InsurerClaimBatchDTO setLineDocuments(UUID batchId, UUID lineId, String documents) {
        InsurerClaimAccess.Actor actor = access.requireBuilder();
        InsurerClaimBatch batch = lockDraft(batchId, actor.hospitalId());
        InsurerClaimLine line = lineRepository.findByBatchIdAndHospitalId(batch.getId(), actor.hospitalId()).stream()
            .filter(candidate -> candidate.getId().equals(lineId)).findFirst()
            .orElseThrow(() -> new ResourceNotFoundException(InsurerClaimLedger.LINE_NOT_FOUND));
        line.setAttachedDocuments(InsurerClaimAccess.optionalText(documents, "attachedDocuments", DOCUMENTS_MAX));
        lineRepository.save(line);
        return detailOf(batch);
    }

    /** A draft was never sent: it is removed with its lines, and its shares can go on another bordereau. */
    @Transactional
    public void deleteDraft(UUID batchId) {
        InsurerClaimAccess.Actor actor = access.requireBuilder();
        InsurerClaimBatch batch = lockDraft(batchId, actor.hospitalId());
        lineRepository.deleteByBatchAtHospital(batch.getId(), actor.hospitalId());
        batchRepository.delete(batch);
        access.audit(AuditEventType.INSURER_CLAIM_BATCH_DRAFTED, actor, batch.getId(), "Insurer claim draft deleted",
            "batch", batch.getId());
    }

    /* ----------------------------------------------------------------- submit */

    /** Sends the bordereau: number, snapshot, frozen totals, and each share OPEN to CLAIMED, once. */
    @Transactional
    public InsurerClaimBatchDTO submit(UUID batchId, InsurerClaimBatchSubmitRequestDTO body) {
        InsurerClaimAccess.Actor actor = access.requireBuilder();
        InsurerClaimBatch batch = lock(batchId, actor.hospitalId());
        if (batch.getStatus().isSent()) {
            // A retried submit: the bordereau stands as it was sent.
            return detailOf(batch);
        }
        if (batch.getStatus() != InsurerClaimBatchStatus.DRAFT) {
            throw new ConflictException(MessageUtil.resolve("billing.claim.notDraft"));
        }
        LocalDate today = access.today(actor.hospitalId());
        LocalDate submittedOn = body != null && body.getSubmittedOn() != null ? body.getSubmittedOn() : today;
        if (submittedOn.isAfter(today)) {
            throw new FieldBusinessException("submittedOn", "billing.claim.dateInFuture");
        }
        String payerReference = InsurerClaimAccess.optionalText(body != null ? body.getPayerReference() : null,
            "payerReference", REFERENCE_MAX);
        List<InsurerClaimLine> lines = lineRepository.findByBatchIdAndHospitalId(batch.getId(), actor.hospitalId());
        if (lines.isEmpty()) {
            throw new ConflictException(MessageUtil.resolve("billing.claim.emptyBatch"));
        }
        Set<UUID> shareIds = lines.stream().map(InsurerClaimLine::getShareId)
            .collect(Collectors.toCollection(TreeSet::new));
        List<InvoicePayerShare> shares = shareRepository.findByIdInAndHospitalId(shareIds, actor.hospitalId());
        // The document sent is French: the labels the server wrote are frozen in the staff language.
        Map<UUID, InsurerClaimLineReader.ShareFacts> facts = reader.read(shares, actor.hospitalId(),
            BillingTextRenderer.STORED_LOCALE);
        LocalDateTime at = access.now();
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal claimed = BigDecimal.ZERO;
        BigDecimal patient = BigDecimal.ZERO;
        Map<UUID, List<InsurerClaimLine>> byShare = lines.stream()
            .collect(Collectors.groupingBy(InsurerClaimLine::getShareId));
        for (UUID shareId : shareIds) {
            InsurerClaimLineReader.ShareFacts share = facts.get(shareId);
            List<InsurerClaimLine> own = byShare.get(shareId);
            if (share == null || !matches(share, own)) {
                throw new ConflictException(MessageUtil.resolve("billing.claim.shareChanged"));
            }
            // The conditional UPDATE wins once: a share cancelled, refused or claimed meanwhile stops the sending.
            if (shareRepository.decide(shareId, actor.hospitalId(), EnumSet.of(PayerShareStatus.OPEN),
                    PayerShareStatus.CLAIMED, actor.userId(), at, null) != 1) {
                throw new ConflictException(MessageUtil.resolve("billing.claim.shareChanged"));
            }
            Map<UUID, InsurerClaimLineReader.Act> acts = share.acts().stream()
                .collect(Collectors.toMap(InsurerClaimLineReader.Act::invoiceItemId, act -> act));
            for (InsurerClaimLine line : own) {
                InsurerClaimLineReader.Act act = acts.get(line.getInvoiceItemId());
                freeze(line, share, act);
                gross = gross.add(act.grossAmount());
                claimed = claimed.add(line.getClaimedAmount());
                patient = patient.add(act.patientAmount());
            }
        }
        lineRepository.saveAll(lines);
        int year = submittedOn.getYear();
        int seq = batchRepository.maxSeqAtHospital(actor.hospitalId(), year) + 1;
        batch.setNumberYear(year);
        batch.setNumberSeq(seq);
        batch.setNumber(String.format("BORD-%d-%04d", year, seq));
        batch.setStatus(InsurerClaimBatchStatus.SUBMITTED);
        batch.setSubmittedOn(submittedOn);
        batch.setSubmittedAt(at);
        batch.setSubmittedBy(actor.userId());
        batch.setPayerReference(payerReference);
        batch.setLineCount(lines.size());
        batch.setGrossTotal(gross);
        batch.setPayerTotal(claimed);
        batch.setPatientTotal(patient);
        try {
            batchRepository.saveAndFlush(batch);
        } catch (DataIntegrityViolationException ex) {
            // Two bordereaux numbered at the same moment: the second is sent again and takes the next rank.
            throw new ConflictException(MessageUtil.resolve("billing.claim.sendAgain"));
        }
        access.audit(AuditEventType.INSURER_CLAIM_BATCH_SUBMITTED, actor, batch.getId(), "Insurer claim submitted",
            "batch", batch.getId());
        return detailOf(batch);
    }

    /** The day the payer acknowledged the bordereau (and its own reference), kept apart from the sending date. */
    @Transactional
    public InsurerClaimBatchDTO recordPayerReceipt(UUID batchId, InsurerClaimBatchReceiptRequestDTO body) {
        InsurerClaimAccess.Actor actor = access.requireBuilder();
        InsurerClaimBatch batch = lock(batchId, actor.hospitalId());
        if (!batch.getStatus().isSent()) {
            throw new ConflictException(MessageUtil.resolve("billing.claim.notSubmitted"));
        }
        LocalDate receivedOn = body.getReceivedOn();
        if (receivedOn.isAfter(access.today(actor.hospitalId()))) {
            throw new FieldBusinessException("receivedOn", "billing.claim.dateInFuture");
        }
        if (receivedOn.isBefore(batch.getSubmittedOn())) {
            throw new FieldBusinessException("receivedOn", "billing.claim.receivedBeforeSent");
        }
        String reference = InsurerClaimAccess.optionalText(body.getPayerReference(), "payerReference", REFERENCE_MAX);
        batch.setPayerReceivedOn(receivedOn);
        if (reference != null) {
            batch.setPayerReference(reference);
        }
        batchRepository.save(batch);
        access.audit(AuditEventType.INSURER_CLAIM_BATCH_SUBMITTED, actor, batch.getId(),
            "Insurer claim receipt by the payer recorded", "batch", batch.getId());
        return detailOf(batch);
    }

    /** Cancels a sent bordereau the payer has not answered; its shares are OPEN again. */
    @Transactional
    public InsurerClaimBatchDTO cancel(UUID batchId, String rawReason) {
        InsurerClaimAccess.Actor actor = access.requireFinance();
        String reason = InsurerClaimAccess.requireText(rawReason, "reason", InsurerClaimAccess.TEXT_MAX,
            "billing.claim.reasonRequired");
        InsurerClaimBatch batch = lock(batchId, actor.hospitalId());
        if (batch.getStatus() == InsurerClaimBatchStatus.DRAFT) {
            throw new ConflictException(MessageUtil.resolve("billing.claim.notSubmitted"));
        }
        List<InsurerClaimLine> lines = lineRepository.findByBatchIdAndHospitalId(batch.getId(), actor.hospitalId());
        Set<UUID> lineIds = lines.stream().map(InsurerClaimLine::getId).collect(Collectors.toSet());
        boolean answered = batch.getStatus() != InsurerClaimBatchStatus.SUBMITTED
            || (!lineIds.isEmpty()
                && (allocationRepository.existsByClaimLineIdInAndHospitalId(lineIds, actor.hospitalId())
                    || refusalRepository.existsByClaimLineIdInAndHospitalId(lineIds, actor.hospitalId())
                    || writeOffRepository.existsByClaimLineIdInAndHospitalId(lineIds, actor.hospitalId())));
        if (answered) {
            throw new ConflictException(MessageUtil.resolve("billing.claim.cancelOnceAnswered"));
        }
        LocalDateTime at = access.now();
        Set<UUID> shareIds = lines.stream().map(InsurerClaimLine::getShareId)
            .collect(Collectors.toCollection(TreeSet::new));
        for (UUID shareId : shareIds) {
            if (shareRepository.decide(shareId, actor.hospitalId(), EnumSet.of(PayerShareStatus.CLAIMED),
                    PayerShareStatus.OPEN, actor.userId(), at, null) != 1) {
                throw new ConflictException(MessageUtil.resolve("billing.claim.changedMeanwhile"));
            }
        }
        lineRepository.releaseByBatchAtHospital(batch.getId(), actor.hospitalId());
        batch.setStatus(InsurerClaimBatchStatus.CANCELLED);
        batch.setCancelledAt(at);
        batch.setCancelledBy(actor.userId());
        batch.setCancelReason(reason);
        batchRepository.saveAndFlush(batch);
        access.audit(AuditEventType.INSURER_CLAIM_BATCH_CANCELLED, actor, batch.getId(), "Insurer claim cancelled",
            "batch", batch.getId());
        return summaryOf(batch, null, Map.of());
    }

    /* ------------------------------------------------------------------ reads */

    /** One page of the acting hospital's bordereaux, newest first, each with how far it is settled. */
    @Transactional(readOnly = true)
    public InsurerClaimPagesDTO.Batches list(Collection<InsurerClaimBatchStatus> statuses, UUID payerId,
                                             Integer page, Integer size) {
        UUID hospitalId = access.requireReader();
        Collection<InsurerClaimBatchStatus> wanted = statuses == null || statuses.isEmpty() ? LISTED : statuses;
        int pageSize = size == null || size <= 0 ? PAGE_DEFAULT : Math.min(size, PAGE_MAX);
        int pageIndex = page == null || page < 0 ? 0 : page;
        Page<InsurerClaimBatch> found = batchRepository.searchAtHospital(hospitalId, wanted, payerId == null,
            payerId != null ? payerId : NO_ID, PageRequest.of(pageIndex, pageSize));
        Map<UUID, InsurerClaimLineRepository.Sums> sums = sumsOf(found.getContent(), hospitalId);
        Map<UUID, Payer> payers = payersOf(found.getContent(), hospitalId);
        List<InsurerClaimBatchDTO> rows = found.getContent().stream()
            .map(batch -> summaryOf(batch, sums.get(batch.getId()), payers))
            .toList();
        return new InsurerClaimPagesDTO.Batches(rows, pageIndex, pageSize, found.getTotalElements(),
            found.getTotalPages());
    }

    /** One bordereau of the acting hospital with its lines, their warnings and the payer's refusals. */
    @Transactional(readOnly = true)
    public InsurerClaimBatchDTO get(UUID batchId) {
        UUID hospitalId = access.requireReader();
        return detailOf(batchRepository.findByIdAndHospitalId(batchId, hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException(BATCH_NOT_FOUND)));
    }

    /**
     * The lines of one payer's sent bordereaux that still take a payment at the acting hospital, the oldest
     * bordereau first: the allocation grid of an insurer payment.
     */
    @Transactional(readOnly = true)
    public List<InsurerClaimLineDTO> openLinesOf(UUID payerId) {
        UUID hospitalId = access.requireReader();
        List<InsurerClaimLine> lines = lineRepository.findOpenOfPayerAtHospital(payerId, hospitalId);
        Set<UUID> batchIds = lines.stream().map(InsurerClaimLine::getBatchId).collect(Collectors.toSet());
        Map<UUID, InsurerClaimBatch> batches = new HashMap<>();
        if (!batchIds.isEmpty()) {
            batchRepository.findByIdInAndHospitalId(batchIds, hospitalId)
                .forEach(batch -> batches.put(batch.getId(), batch));
        }
        return lines.stream().map(line -> frozenLine(line, batches.get(line.getBatchId()), List.of())).toList();
    }

    /* ------------------------------------------------------------ internals */

    /** The lines of a bordereau as they are exported: a draft read live, a sent one from its snapshot. */
    List<InsurerClaimLineDTO> linesOf(InsurerClaimBatch batch) {
        UUID hospitalId = batch.getHospitalId();
        List<InsurerClaimLine> lines = lineRepository.findByBatchIdAndHospitalId(batch.getId(), hospitalId);
        if (lines.isEmpty()) {
            return List.of();
        }
        List<InsurerClaimLineDTO> out = new ArrayList<>();
        if (batch.getStatus() == InsurerClaimBatchStatus.DRAFT) {
            Set<UUID> shareIds = lines.stream().map(InsurerClaimLine::getShareId).collect(Collectors.toSet());
            Map<UUID, InsurerClaimLineReader.ShareFacts> facts = reader.read(
                shareRepository.findByIdInAndHospitalId(shareIds, hospitalId), hospitalId,
                LocaleContextHolder.getLocale());
            for (InsurerClaimLine line : lines) {
                InsurerClaimLineReader.ShareFacts share = facts.get(line.getShareId());
                if (share == null) {
                    continue;
                }
                share.acts().stream()
                    .filter(candidate -> candidate.invoiceItemId().equals(line.getInvoiceItemId()))
                    .findFirst()
                    .ifPresent(act -> out.add(liveLine(line, batch, share, act)));
            }
        } else {
            Set<UUID> lineIds = lines.stream().map(InsurerClaimLine::getId).collect(Collectors.toSet());
            List<InsurerClaimRefusal> refusals = refusalRepository
                .findByClaimLineIdInAndHospitalIdOrderByRecordedAtAscIdAsc(lineIds, hospitalId);
            Map<UUID, String> writeOffStatus = writeOffRepository
                .findByClaimLineIdInAndHospitalIdOrderByRequestedAtAscIdAsc(lineIds, hospitalId).stream()
                .collect(Collectors.toMap(InsurerClaimWriteOff::getId, row -> row.getStatus().name()));
            Map<UUID, String> names = access.names(refusals.stream().map(InsurerClaimRefusal::getRecordedBy)
                .toList());
            Map<UUID, List<InsurerClaimRefusalDTO>> byLine = refusals.stream()
                .collect(Collectors.groupingBy(InsurerClaimRefusal::getClaimLineId,
                    Collectors.mapping(row -> refusalDto(row, names, writeOffStatus), Collectors.toList())));
            for (InsurerClaimLine line : lines) {
                out.add(frozenLine(line, batch, byLine.getOrDefault(line.getId(), List.of())));
            }
        }
        out.sort(Comparator.comparing(InsurerClaimLineDTO::getPatientName,
                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
            .thenComparing(InsurerClaimLineDTO::getInvoiceNumber, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(line -> String.valueOf(line.getId())));
        return out;
    }

    static InsurerClaimRefusalDTO refusalDto(InsurerClaimRefusal row, Map<UUID, String> names,
                                             Map<UUID, String> writeOffStatus) {
        return InsurerClaimRefusalDTO.builder()
            .id(row.getId())
            .claimLineId(row.getClaimLineId())
            .amount(row.getAmount())
            .reasonClass(row.getReasonClass().name())
            .reason(row.getReason())
            .resolution(row.getResolution().name())
            .writeOffId(row.getWriteOffId())
            .writeOffStatus(row.getWriteOffId() != null ? writeOffStatus.get(row.getWriteOffId()) : null)
            .recordedByName(names.get(row.getRecordedBy()))
            .recordedAt(row.getRecordedAt())
            .build();
    }

    /** The bordereau with its lines; the detail every write answers with. */
    InsurerClaimBatchDTO detailOf(InsurerClaimBatch batch) {
        List<InsurerClaimLineDTO> lines = linesOf(batch);
        Map<UUID, Payer> payers = payersOf(List.of(batch), batch.getHospitalId());
        InsurerClaimLineRepository.Sums sums = new InsurerClaimLineRepository.Sums(batch.getId(),
            sum(lines, InsurerClaimLineDTO::getClaimedAmount), sum(lines, InsurerClaimLineDTO::getPaidAmount),
            sum(lines, InsurerClaimLineDTO::getRebilledAmount), sum(lines, InsurerClaimLineDTO::getWrittenOffAmount),
            sum(lines, InsurerClaimLineDTO::getWriteOffPendingAmount));
        InsurerClaimBatchDTO summary = summaryOf(batch, sums, payers);
        boolean draft = batch.getStatus() == InsurerClaimBatchStatus.DRAFT;
        int warnings = lines.stream().mapToInt(line -> line.getWarnings() == null ? 0 : line.getWarnings().size())
            .sum();
        return InsurerClaimBatchDTO.builder()
            .id(summary.getId()).number(summary.getNumber()).payerId(summary.getPayerId())
            .payerName(summary.getPayerName()).payerType(summary.getPayerType()).status(summary.getStatus())
            .periodFrom(summary.getPeriodFrom()).periodTo(summary.getPeriodTo())
            .submittedOn(summary.getSubmittedOn()).submittedByName(summary.getSubmittedByName())
            .payerReference(summary.getPayerReference()).payerReceivedOn(summary.getPayerReceivedOn())
            .createdAt(summary.getCreatedAt()).createdByName(summary.getCreatedByName())
            .lineCount(lines.size())
            .fileCount((int) lines.stream().map(InsurerClaimLineDTO::getInvoiceId).distinct().count())
            .grossTotal(draft ? sum(lines, InsurerClaimLineDTO::getGrossAmount) : batch.getGrossTotal())
            .patientTotal(draft ? sum(lines, InsurerClaimLineDTO::getPatientAmount) : batch.getPatientTotal())
            .claimedTotal(summary.getClaimedTotal()).paidTotal(summary.getPaidTotal())
            .rebilledTotal(summary.getRebilledTotal()).writtenOffTotal(summary.getWrittenOffTotal())
            .writeOffPendingTotal(summary.getWriteOffPendingTotal())
            .outstandingTotal(summary.getOutstandingTotal())
            .cancelReason(summary.getCancelReason()).cancelledAt(summary.getCancelledAt())
            .warningCount(draft ? warnings : null)
            .lines(lines)
            .build();
    }

    private InsurerClaimBatchDTO summaryOf(InsurerClaimBatch batch, InsurerClaimLineRepository.Sums sums,
                                           Map<UUID, Payer> payers) {
        Payer payer = payers.get(batch.getPayerId());
        Map<UUID, String> names = access.names(java.util.Arrays.asList(batch.getCreatedBy(),
            batch.getSubmittedBy()));
        BigDecimal claimed = sums != null ? sums.claimed() : BigDecimal.ZERO;
        return InsurerClaimBatchDTO.builder()
            .id(batch.getId())
            .number(batch.getNumber())
            .payerId(batch.getPayerId())
            .payerName(payer != null ? payer.getName() : null)
            .payerType(payer != null && payer.getPayerType() != null ? payer.getPayerType().name() : null)
            .status(batch.getStatus().name())
            .periodFrom(batch.getPeriodFrom())
            .periodTo(batch.getPeriodTo())
            .submittedOn(batch.getSubmittedOn())
            .submittedByName(batch.getSubmittedBy() != null ? names.get(batch.getSubmittedBy()) : null)
            .payerReference(batch.getPayerReference())
            .payerReceivedOn(batch.getPayerReceivedOn())
            .createdAt(batch.getCreatedAt())
            .createdByName(batch.getCreatedBy() != null ? names.get(batch.getCreatedBy()) : null)
            .lineCount(batch.getLineCount() != null ? batch.getLineCount() : 0)
            .grossTotal(batch.getGrossTotal())
            .patientTotal(batch.getPatientTotal())
            .claimedTotal(claimed)
            .paidTotal(sums != null ? sums.paid() : BigDecimal.ZERO)
            .rebilledTotal(sums != null ? sums.rebilled() : BigDecimal.ZERO)
            .writtenOffTotal(sums != null ? sums.writtenOff() : BigDecimal.ZERO)
            .writeOffPendingTotal(sums != null ? sums.writeOffPending() : BigDecimal.ZERO)
            .outstandingTotal(sums != null ? sums.outstanding() : BigDecimal.ZERO)
            .cancelReason(batch.getCancelReason())
            .cancelledAt(batch.getCancelledAt())
            .build();
    }

    private Map<UUID, InsurerClaimLineRepository.Sums> sumsOf(List<InsurerClaimBatch> batches, UUID hospitalId) {
        Map<UUID, InsurerClaimLineRepository.Sums> out = new HashMap<>();
        Set<UUID> ids = batches.stream().map(InsurerClaimBatch::getId).collect(Collectors.toSet());
        if (!ids.isEmpty()) {
            lineRepository.sumsOfBatchesAtHospital(ids, hospitalId).forEach(sums -> out.put(sums.batchId(), sums));
        }
        return out;
    }

    private Map<UUID, Payer> payersOf(List<InsurerClaimBatch> batches, UUID hospitalId) {
        Map<UUID, Payer> out = new HashMap<>();
        for (InsurerClaimBatch batch : batches) {
            if (!out.containsKey(batch.getPayerId())) {
                payerRepository.findByIdAndHospitalId(batch.getPayerId(), hospitalId)
                    .ifPresent(payer -> out.put(payer.getId(), payer));
            }
        }
        return out;
    }

    private static BigDecimal sum(List<InsurerClaimLineDTO> lines,
                                  java.util.function.Function<InsurerClaimLineDTO, BigDecimal> amount) {
        BigDecimal total = BigDecimal.ZERO;
        for (InsurerClaimLineDTO line : lines) {
            BigDecimal value = amount.apply(line);
            total = total.add(value != null ? value : BigDecimal.ZERO);
        }
        return total;
    }

    private InsurerClaimBatch lock(UUID batchId, UUID hospitalId) {
        return batchRepository.lockByIdAndHospitalId(batchId, hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException(BATCH_NOT_FOUND));
    }

    private InsurerClaimBatch lockDraft(UUID batchId, UUID hospitalId) {
        InsurerClaimBatch batch = lock(batchId, hospitalId);
        if (batch.getStatus() != InsurerClaimBatchStatus.DRAFT) {
            throw new ConflictException(MessageUtil.resolve("billing.claim.notDraft"));
        }
        return batch;
    }

    private static Set<UUID> idsOf(List<UUID> ids) {
        Set<UUID> out = new LinkedHashSet<>();
        if (ids != null) {
            ids.stream().filter(Objects::nonNull).forEach(out::add);
        }
        return out;
    }

    /** A share already on a bordereau (not cancelled) is refused, naming that bordereau. */
    private void requireFree(List<InvoicePayerShare> shares, UUID hospitalId) {
        Set<UUID> ids = shares.stream().map(InvoicePayerShare::getId).collect(Collectors.toSet());
        List<InsurerClaimLine> held = lineRepository.findByShareIdInAndHospitalIdAndReleasedFalse(ids, hospitalId);
        if (held.isEmpty()) {
            return;
        }
        InsurerClaimBatch other = batchRepository.findByIdAndHospitalId(held.get(0).getBatchId(), hospitalId)
            .orElse(null);
        if (other != null && other.getNumber() != null) {
            throw new FieldBusinessException("add", "billing.claim.shareOnBatch", other.getNumber());
        }
        throw new FieldBusinessException("add", "billing.claim.shareOnDraft");
    }

    private static List<InsurerClaimLine> draftLines(InsurerClaimBatch batch, InsurerClaimLineReader.ShareFacts share) {
        List<InsurerClaimLine> lines = new ArrayList<>();
        for (InsurerClaimLineReader.Act act : share.acts()) {
            lines.add(InsurerClaimLine.builder()
                .hospitalId(batch.getHospitalId())
                .batchId(batch.getId())
                .shareId(share.share().getId())
                .invoiceId(share.invoice().getId())
                .invoiceItemId(act.invoiceItemId())
                .claimedAmount(act.claimedAmount())
                .build());
        }
        return lines;
    }

    /** Two drafts taking the same share at once meet the one-live-bordereau index: the second is refused. */
    private void saveLines(List<InsurerClaimLine> lines) {
        if (lines.isEmpty()) {
            return;
        }
        try {
            lineRepository.saveAllAndFlush(lines);
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException(MessageUtil.resolve("billing.claim.shareOnDraft"));
        }
    }

    /** The draft's lines of a share still say what the share claims: the same acts, the same amounts. */
    private static boolean matches(InsurerClaimLineReader.ShareFacts share, List<InsurerClaimLine> lines) {
        if (!share.complete() || share.stayOpen() || lines.size() != share.acts().size()) {
            return false;
        }
        Set<UUID> seen = new HashSet<>();
        for (InsurerClaimLine line : lines) {
            boolean same = share.acts().stream().anyMatch(act -> act.invoiceItemId().equals(line.getInvoiceItemId())
                && act.claimedAmount().compareTo(line.getClaimedAmount()) == 0);
            if (!same || !seen.add(line.getInvoiceItemId())) {
                return false;
            }
        }
        return true;
    }

    /** The snapshot of a line, written once when its bordereau is sent. */
    private static void freeze(InsurerClaimLine line, InsurerClaimLineReader.ShareFacts share,
                               InsurerClaimLineReader.Act act) {
        line.setPatientName(share.patientName());
        line.setPatientMrn(share.patientMrn());
        if (share.coverage() != null) {
            line.setPolicyNumber(share.coverage().getPolicyNumber());
            line.setSubscriberName(share.coverage().getSubscriberName());
            line.setSubscriberRelationship(share.coverage().getSubscriberRelationship());
        }
        line.setPlanName(share.plan() != null ? share.plan().getName() : null);
        line.setInvoiceNumber(share.invoice().getInvoiceNumber());
        line.setInvoiceDate(share.invoice().getInvoiceDate());
        line.setServiceDate(share.serviceDate());
        line.setDischargeDate(share.dischargeDate());
        line.setActCode(act.actCode());
        line.setActLabel(act.actLabel());
        line.setCategory(act.category());
        line.setQuantity(act.quantity());
        line.setGrossAmount(act.grossAmount());
        line.setPatientAmount(act.patientAmount());
        line.setPreAuthorisationNumber(act.preAuthorisationNumber());
    }

    private static InsurerClaimLineDTO liveLine(InsurerClaimLine line, InsurerClaimBatch batch,
                                                InsurerClaimLineReader.ShareFacts share,
                                                InsurerClaimLineReader.Act act) {
        // A live line always belongs to a loaded draft batch; its period is read before amounts(),
        // which also serves lines read without their batch.
        List<String> warnings = share.warnings(act, batch.getPeriodFrom(), batch.getPeriodTo());
        return amounts(line, batch)
            .patientName(share.patientName())
            .patientMrn(share.patientMrn())
            .policyNumber(share.coverage() != null ? share.coverage().getPolicyNumber() : null)
            .subscriberName(share.coverage() != null ? share.coverage().getSubscriberName() : null)
            .subscriberRelationship(share.coverage() != null ? share.coverage().getSubscriberRelationship() : null)
            .planName(share.plan() != null ? share.plan().getName() : null)
            .invoiceNumber(share.invoice().getInvoiceNumber())
            .invoiceDate(share.invoice().getInvoiceDate())
            .serviceDate(share.serviceDate())
            .dischargeDate(share.dischargeDate())
            .actCode(act.actCode())
            .actLabel(act.actLabel())
            .category(act.category())
            .quantity(act.quantity())
            .grossAmount(act.grossAmount())
            .patientAmount(act.patientAmount())
            .preAuthorisationNumber(act.preAuthorisationNumber())
            .warnings(warnings)
            .build();
    }

    static InsurerClaimLineDTO frozenLine(InsurerClaimLine line, InsurerClaimBatch batch,
                                          List<InsurerClaimRefusalDTO> refusals) {
        return amounts(line, batch)
            .patientName(line.getPatientName())
            .patientMrn(line.getPatientMrn())
            .policyNumber(line.getPolicyNumber())
            .subscriberName(line.getSubscriberName())
            .subscriberRelationship(line.getSubscriberRelationship())
            .planName(line.getPlanName())
            .invoiceNumber(line.getInvoiceNumber())
            .invoiceDate(line.getInvoiceDate())
            .serviceDate(line.getServiceDate())
            .dischargeDate(line.getDischargeDate())
            .actCode(line.getActCode())
            .actLabel(line.getActLabel())
            .category(line.getCategory())
            .quantity(line.getQuantity())
            .grossAmount(line.getGrossAmount())
            .patientAmount(line.getPatientAmount())
            .preAuthorisationNumber(line.getPreAuthorisationNumber())
            .refusals(refusals)
            .build();
    }

    private static InsurerClaimLineDTO.InsurerClaimLineDTOBuilder amounts(InsurerClaimLine line,
                                                                          InsurerClaimBatch batch) {
        return InsurerClaimLineDTO.builder()
            .id(line.getId())
            .batchId(line.getBatchId())
            .batchNumber(batch != null ? batch.getNumber() : null)
            .submittedOn(batch != null ? batch.getSubmittedOn() : null)
            .shareId(line.getShareId())
            .invoiceId(line.getInvoiceId())
            .invoiceItemId(line.getInvoiceItemId())
            .attachedDocuments(line.getAttachedDocuments())
            .claimedAmount(line.getClaimedAmount())
            .paidAmount(line.getPaidAmount())
            .rebilledAmount(line.getRebilledAmount())
            .writtenOffAmount(line.getWrittenOffAmount())
            .writeOffPendingAmount(line.getWriteOffPendingAmount())
            .outstandingAmount(line.outstandingAmount())
            .availableAmount(line.availableAmount());
    }
}
