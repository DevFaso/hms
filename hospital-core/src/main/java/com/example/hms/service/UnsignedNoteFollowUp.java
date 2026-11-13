package com.example.hms.service;

import com.example.hms.enums.InBasketItemStatus;
import com.example.hms.enums.InBasketItemType;
import com.example.hms.enums.InBasketPriority;
import com.example.hms.exception.BusinessException;
import com.example.hms.model.Encounter;
import com.example.hms.model.InBasketItem;
import com.example.hms.model.Patient;
import com.example.hms.model.encounter.EncounterNote;
import com.example.hms.payload.dto.clinical.CreateInBasketItemRequest;
import com.example.hms.payload.dto.clinical.UnsignedNoteOverdueDTO;
import com.example.hms.repository.EncounterNoteRepository;
import com.example.hms.repository.InBasketItemRepository;
import com.example.hms.security.CallerIdentity;
import com.example.hms.service.i18n.NotificationLocales;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Provisional platform rule, not a clinical sign-off (tasklist.md): a visit closes without waiting for the
 * doctor, and every note of it still waiting for its author's signature goes to that author's in-basket.
 *
 * <ul>
 *   <li>{@link #visitClosed}: every path that closes a visit calls it ({@code UnsignedNoteHookGuardTest}). One
 *       "note to sign" item per note, for its author only, at the note's hospital. Filed after the close commits,
 *       in a transaction of its own: a failure here never undoes the close.</li>
 *   <li>{@link #noteSigned}: the item goes when the note is signed.</li>
 *   <li>{@link #remindOverdue}: after {@link #REMINDER_AFTER} the same item becomes a reminder (never a second
 *       item).</li>
 *   <li>{@link #overdueAtHospital}: the hospital administrator's list of notes unsigned for more than
 *       {@link #ADMIN_OVERDUE}. The administrator gets no in-basket item (E9 #67: their in-basket is the dispute
 *       review only).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UnsignedNoteFollowUp {

    /** The reference of a "note to sign" item: the note's id. */
    public static final String REFERENCE_TYPE = "ENCOUNTER_NOTE_SIGNATURE";
    static final String TITLE_KEY = "encounter.note.toSign.title";
    static final String REMINDER_TITLE_KEY = "encounter.note.toSign.reminderTitle";
    static final String BODY_KEY = "encounter.note.toSign.body";
    public static final Duration REMINDER_AFTER = Duration.ofHours(24);
    public static final Duration ADMIN_OVERDUE = Duration.ofHours(72);

    private final EncounterNoteRepository notes;
    private final InBasketService inBasket;
    private final InBasketItemRepository inBasketItemRepository;
    private final RoleValidator roleValidator;
    private final PlatformTransactionManager transactionManager;
    private final Clock clock;

    /**
     * The visit is closed: each of its notes still waiting for a signature goes to its author. Does nothing while
     * the visit is open, so a close path may call it whatever status it wrote.
     */
    public void visitClosed(Encounter encounter) {
        if (encounter == null || encounter.getId() == null || encounter.getStatus() == null
            || !encounter.getStatus().isClosed()) {
            return;
        }
        UUID encounterId = encounter.getId();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    fileForVisit(encounterId);
                }
            });
        } else {
            fileForVisit(encounterId);
        }
    }

    /** Ids only: this runs after the close committed, with no persistence context of its own. */
    void fileForVisit(UUID encounterId) {
        try {
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            // Its own transaction (REQUIRES_NEW): the failure swallowed here dooms nothing of the close.
            tx.executeWithoutResult(status -> fileNotesOf(encounterId));
        } catch (RuntimeException ex) {
            log.warn("Notes to sign not filed for encounter {}: {}", encounterId, ex.getClass().getSimpleName());
        }
    }

    private void fileNotesOf(UUID encounterId) {
        notes.findByEncounter_IdOrderByCreatedAtAscIdAsc(encounterId).stream()
            .filter(EncounterNote::awaitsSignature)
            .forEach(this::fileFor);
    }

    private void fileFor(EncounterNote note) {
        if (note.getAuthor() == null || note.getAuthor().getId() == null || note.getHospital() == null) {
            return;
        }
        Encounter encounter = note.getEncounter();
        Patient patient = note.getPatient() != null ? note.getPatient() : encounter.getPatient();
        String patientName = patient != null ? patient.getFullName() : null;
        String visitDate = visitDay(encounter);
        CreateInBasketItemRequest item = CreateInBasketItemRequest.builder()
            .recipientUserId(note.getAuthor().getId())
            .hospitalId(note.getHospital().getId())
            .itemType(InBasketItemType.TASK)
            .priority(InBasketPriority.NORMAL)
            .titleKey(TITLE_KEY)
            .titleArgs(new Object[] {patientName})
            .bodyKey(BODY_KEY)
            .bodyArgs(new Object[] {patientName, visitDate})
            .title(MessageUtil.resolve(NotificationLocales.STAFF, TITLE_KEY, patientName))
            .body(MessageUtil.resolve(NotificationLocales.STAFF, BODY_KEY, patientName, visitDate))
            .referenceId(note.getId())
            .referenceType(REFERENCE_TYPE)
            .encounterId(encounter.getId())
            .patientId(patient != null ? patient.getId() : null)
            .patientName(patientName)
            .build();
        // One item per note and author: the store answers null for a duplicate.
        inBasket.createItem(item);
    }

    /** The note is signed: its "note to sign" item goes, in the caller's transaction. */
    public void noteSigned(UUID noteId) {
        if (noteId != null) {
            inBasket.removeAllItemsForReference(noteId, REFERENCE_TYPE);
        }
    }

    /**
     * After {@link #REMINDER_AFTER}, each item whose note is still unsigned becomes a reminder: the same row,
     * unread again and urgent, never a second item. An item whose note was signed by a path that missed
     * {@link #noteSigned} is removed. Returns how many reminders were written.
     */
    @Transactional
    public int remindOverdue() {
        LocalDateTime before = LocalDateTime.now(clock).minus(REMINDER_AFTER);
        int reminded = 0;
        for (InBasketItem item : inBasketItemRepository.findByReferenceTypeAndTitleKeyAndCreatedAtBefore(
                REFERENCE_TYPE, TITLE_KEY, before)) {
            EncounterNote note = item.getReferenceId() != null
                ? notes.findById(item.getReferenceId()).orElse(null) : null;
            if (note == null || !note.awaitsSignature()) {
                inBasketItemRepository.delete(item);
                continue;
            }
            List<Object> args = item.getTitleArgs();
            Object patientName = args != null && !args.isEmpty() ? args.get(0) : item.getPatientName();
            item.setTitleKey(REMINDER_TITLE_KEY);
            item.setTitle(MessageUtil.resolve(NotificationLocales.STAFF, REMINDER_TITLE_KEY, patientName));
            item.setPriority(InBasketPriority.URGENT);
            item.setStatus(InBasketItemStatus.UNREAD);
            item.setReadAt(null);
            item.setAcknowledgedAt(null);
            item.setAcknowledgedBy(null);
            inBasketItemRepository.save(item);
            reminded++;
        }
        return reminded;
    }

    /**
     * The administrator's list: the notes at {@code hospitalId} unsigned for more than {@link #ADMIN_OVERDUE},
     * oldest first. Only an administrator of that very hospital (or a super-admin acting for it) reads it.
     */
    @Transactional(readOnly = true)
    public List<UnsignedNoteOverdueDTO> overdueAtHospital(UUID hospitalId) {
        if (hospitalId == null) {
            throw new BusinessException(RoleValidator.HOSPITAL_CONTEXT_REQUIRED);
        }
        UUID actorId = roleValidator.getCurrentUserId();
        if (!CallerIdentity.isSuperAdminFor(hospitalId)
            && (actorId == null || !roleValidator.isHospitalAdmin(actorId, hospitalId))) {
            throw new AccessDeniedException("Only an administrator of this hospital reads its unsigned notes.");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        return notes.findUnsignedAtHospitalCreatedBefore(hospitalId, now.minus(ADMIN_OVERDUE)).stream()
            .filter(EncounterNote::awaitsSignature)
            .map(note -> toOverdue(note, now))
            .toList();
    }

    private UnsignedNoteOverdueDTO toOverdue(EncounterNote note, LocalDateTime now) {
        Encounter encounter = note.getEncounter();
        Patient patient = note.getPatient() != null ? note.getPatient() : encounter.getPatient();
        String author = note.getAuthorName();
        if ((author == null || author.isBlank()) && note.getAuthor() != null) {
            author = note.getAuthor().getUsername();
        }
        return new UnsignedNoteOverdueDTO(note.getId(), encounter.getId(), author,
            patient != null ? patient.getFullName() : null,
            encounter.getEncounterDate(), note.getCreatedAt(),
            note.getCreatedAt() != null ? Duration.between(note.getCreatedAt().atZone(clock.getZone()),
                now.atZone(clock.getZone())).toHours() : 0L);
    }

    private static String visitDay(Encounter encounter) {
        return encounter.getEncounterDate() != null ? encounter.getEncounterDate().toLocalDate().toString() : "";
    }
}
