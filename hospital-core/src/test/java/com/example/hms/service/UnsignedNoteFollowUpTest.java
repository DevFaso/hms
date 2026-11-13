package com.example.hms.service;

import com.example.hms.enums.EncounterStatus;
import com.example.hms.enums.InBasketItemStatus;
import com.example.hms.enums.InBasketItemType;
import com.example.hms.enums.InBasketPriority;
import com.example.hms.exception.BusinessException;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.InBasketItem;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
import com.example.hms.model.encounter.EncounterNote;
import com.example.hms.payload.dto.clinical.CreateInBasketItemRequest;
import com.example.hms.payload.dto.clinical.UnsignedNoteOverdueDTO;
import com.example.hms.repository.EncounterNoteRepository;
import com.example.hms.repository.InBasketItemRepository;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Provisional platform rule (unsigned-note workflow): a closed visit's unsigned note goes to its author's
 * in-basket, the item goes when it is signed, it comes back once as a reminder after 24 h, and the hospital
 * administrator reads the notes unsigned for more than 72 h at their own hospital only.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(com.example.hms.i18n.EnglishMessageBundles.class)
class UnsignedNoteFollowUpTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 12, 10, 0);

    @Mock private EncounterNoteRepository notes;
    @Mock private InBasketService inBasket;
    @Mock private InBasketItemRepository items;
    @Mock private RoleValidator roleValidator;
    @Mock private PlatformTransactionManager transactionManager;

    private UnsignedNoteFollowUp followUp;
    private Hospital hospital;
    private Patient patient;
    private Encounter encounter;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
        followUp = new UnsignedNoteFollowUp(notes, inBasket, items, roleValidator, transactionManager, clock);
        hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        patient = Patient.builder().firstName("Awa").lastName("Kaboré").build();
        patient.setId(UUID.randomUUID());
        encounter = new Encounter();
        encounter.setId(UUID.randomUUID());
        encounter.setHospital(hospital);
        encounter.setPatient(patient);
        encounter.setEncounterDate(NOW.minusDays(1));
        encounter.setStatus(EncounterStatus.COMPLETED);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        SecurityContextHolder.clearContext();
    }

    private EncounterNote note(User author, String assessment, LocalDateTime signedAt) {
        EncounterNote note = EncounterNote.builder().encounter(encounter).patient(patient).hospital(hospital)
            .author(author).authorName(author.getFirstName()).assessment(assessment).signedAt(signedAt)
            .documentedAt(NOW).build();
        note.setId(UUID.randomUUID());
        note.setCreatedAt(NOW.minusHours(1));
        return note;
    }

    private static User user(String name) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setFirstName(name);
        user.setUsername(name.toLowerCase());
        return user;
    }

    /* ── at close ─────────────────────────────────────────────────────── */

    @Test
    void atCloseAnUnsignedNoteFilesOneItemForItsAuthorOnly() {
        User author = user("Issa");
        User signer = user("Mariam");
        User blank = user("Ali");
        EncounterNote unsigned = note(author, "Paludisme simple", null);
        when(notes.findByEncounter_IdOrderByCreatedAtAscIdAsc(encounter.getId())).thenReturn(List.of(
            unsigned, note(signer, "Signed", NOW.minusMinutes(5)), note(blank, null, null)));

        followUp.visitClosed(encounter);

        ArgumentCaptor<CreateInBasketItemRequest> filed = ArgumentCaptor.forClass(CreateInBasketItemRequest.class);
        verify(inBasket, times(1)).createItem(filed.capture());
        CreateInBasketItemRequest item = filed.getValue();
        assertThat(item.getRecipientUserId()).isEqualTo(author.getId());
        assertThat(item.getHospitalId()).isEqualTo(hospital.getId());
        assertThat(item.getReferenceId()).isEqualTo(unsigned.getId());
        assertThat(item.getReferenceType()).isEqualTo(UnsignedNoteFollowUp.REFERENCE_TYPE);
        assertThat(item.getEncounterId()).isEqualTo(encounter.getId());
        assertThat(item.getItemType()).isEqualTo(InBasketItemType.TASK);
        assertThat(item.getTitleKey()).isEqualTo(UnsignedNoteFollowUp.TITLE_KEY);
        assertThat(item.getTitle()).isEqualTo("Note à signer : Awa Kaboré");
    }

    @Test
    void anOpenVisitFilesNothing() {
        encounter.setStatus(EncounterStatus.IN_PROGRESS);
        when(notes.findByEncounter_IdOrderByCreatedAtAscIdAsc(encounter.getId()))
            .thenReturn(List.of(note(user("Issa"), "Draft", null)));

        followUp.visitClosed(encounter);

        verify(inBasket, never()).createItem(any());
    }

    @Test
    void insideATransactionTheItemIsFiledOnlyAfterTheCloseCommits() {
        when(notes.findByEncounter_IdOrderByCreatedAtAscIdAsc(encounter.getId()))
            .thenReturn(List.of(note(user("Issa"), "Draft", null)));
        TransactionSynchronizationManager.initSynchronization();

        followUp.visitClosed(encounter);
        verify(inBasket, never()).createItem(any());

        List<TransactionSynchronization> registered = new ArrayList<>(
            TransactionSynchronizationManager.getSynchronizations());
        registered.forEach(TransactionSynchronization::afterCommit);
        verify(inBasket, times(1)).createItem(any());
    }

    @Test
    void aFailureToFileNeverUndoesTheClose() {
        when(notes.findByEncounter_IdOrderByCreatedAtAscIdAsc(encounter.getId()))
            .thenReturn(List.of(note(user("Issa"), "Draft", null)));
        when(inBasket.createItem(any())).thenThrow(new IllegalStateException("store down"));

        followUp.visitClosed(encounter);

        verify(inBasket).createItem(any());
    }

    /* ── signing ──────────────────────────────────────────────────────── */

    @Test
    void signingClearsTheItem() {
        UUID noteId = UUID.randomUUID();

        followUp.noteSigned(noteId);

        verify(inBasket).removeAllItemsForReference(noteId, UnsignedNoteFollowUp.REFERENCE_TYPE);
    }

    /* ── reminder after 24 h ──────────────────────────────────────────── */

    private InBasketItem filedItem(EncounterNote note) {
        InBasketItem item = InBasketItem.builder().itemType(InBasketItemType.TASK)
            .status(InBasketItemStatus.ACKNOWLEDGED).priority(InBasketPriority.NORMAL)
            .titleKey(UnsignedNoteFollowUp.TITLE_KEY).titleArgs(List.of("Awa Kaboré")).title("Note to sign: Awa Kaboré")
            .referenceId(note.getId()).referenceType(UnsignedNoteFollowUp.REFERENCE_TYPE)
            .readAt(NOW.minusHours(20)).acknowledgedAt(NOW.minusHours(20)).build();
        item.setId(UUID.randomUUID());
        return item;
    }

    @Test
    void after24HoursTheSameItemBecomesAReminderAndNoSecondItemIsFiled() {
        EncounterNote unsigned = note(user("Issa"), "Draft", null);
        InBasketItem item = filedItem(unsigned);
        when(items.findByReferenceTypeAndTitleKeyAndCreatedAtBefore(
            eq(UnsignedNoteFollowUp.REFERENCE_TYPE), eq(UnsignedNoteFollowUp.TITLE_KEY), any()))
            .thenReturn(List.of(item));
        when(notes.findById(unsigned.getId())).thenReturn(Optional.of(unsigned));

        assertThat(followUp.remindOverdue()).isEqualTo(1);

        ArgumentCaptor<LocalDateTime> before = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(items).findByReferenceTypeAndTitleKeyAndCreatedAtBefore(any(), any(), before.capture());
        assertThat(before.getValue()).isEqualTo(NOW.minusHours(24));
        verify(items).save(item);
        assertThat(item.getTitleKey()).isEqualTo(UnsignedNoteFollowUp.REMINDER_TITLE_KEY);
        assertThat(item.getTitle()).isEqualTo("Rappel, note toujours non signée après 24 h : Awa Kaboré");
        assertThat(item.getStatus()).isEqualTo(InBasketItemStatus.UNREAD);
        assertThat(item.getPriority()).isEqualTo(InBasketPriority.URGENT);
        assertThat(item.getAcknowledgedAt()).isNull();
        verify(inBasket, never()).createItem(any());
        verify(inBasket, never()).createItemInNewTransaction(any());
    }

    @Test
    void aReminderIsWrittenOnceTheSweepOnlyReadsFirstNotices() {
        // The sweep asks for items still carrying the first notice's key: a reminder is never reminded again.
        when(items.findByReferenceTypeAndTitleKeyAndCreatedAtBefore(any(), any(), any())).thenReturn(List.of());

        assertThat(followUp.remindOverdue()).isZero();

        verify(items).findByReferenceTypeAndTitleKeyAndCreatedAtBefore(
            eq(UnsignedNoteFollowUp.REFERENCE_TYPE), eq(UnsignedNoteFollowUp.TITLE_KEY), any());
        verify(items, never()).save(any());
    }

    @Test
    void anItemWhoseNoteWasSignedIsRemovedNotReminded() {
        EncounterNote signed = note(user("Issa"), "Done", NOW.minusHours(2));
        InBasketItem item = filedItem(signed);
        when(items.findByReferenceTypeAndTitleKeyAndCreatedAtBefore(any(), any(), any())).thenReturn(List.of(item));
        when(notes.findById(signed.getId())).thenReturn(Optional.of(signed));

        assertThat(followUp.remindOverdue()).isZero();

        verify(items).delete(item);
        verify(items, never()).save(any());
    }

    /* ── the administrator's 72 h list ────────────────────────────────── */

    @Test
    void theAdminListIsScopedToTheAdministratorsHospital() {
        UUID adminId = UUID.randomUUID();
        when(roleValidator.getCurrentUserId()).thenReturn(adminId);
        when(roleValidator.isHospitalAdmin(adminId, hospital.getId())).thenReturn(true);
        EncounterNote old = note(user("Issa"), "Draft", null);
        old.setCreatedAt(NOW.minusHours(80));
        EncounterNote blank = note(user("Ali"), null, null);
        blank.setCreatedAt(NOW.minusHours(90));
        when(notes.findUnsignedAtHospitalCreatedBefore(eq(hospital.getId()), any())).thenReturn(List.of(blank, old));

        List<UnsignedNoteOverdueDTO> rows = followUp.overdueAtHospital(hospital.getId());

        ArgumentCaptor<LocalDateTime> before = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(notes).findUnsignedAtHospitalCreatedBefore(eq(hospital.getId()), before.capture());
        assertThat(before.getValue()).isEqualTo(NOW.minusHours(72));
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.noteId()).isEqualTo(old.getId());
            assertThat(row.authorName()).isEqualTo("Issa");
            assertThat(row.patientName()).isEqualTo("Awa Kaboré");
            assertThat(row.visitDate()).isEqualTo(encounter.getEncounterDate());
            assertThat(row.ageHours()).isEqualTo(80);
        });
    }

    @Test
    void anAdministratorOfAnotherHospitalIsRefused() {
        UUID adminId = UUID.randomUUID();
        UUID otherHospital = UUID.randomUUID();
        when(roleValidator.getCurrentUserId()).thenReturn(adminId);
        when(roleValidator.isHospitalAdmin(adminId, hospital.getId())).thenReturn(true);
        when(roleValidator.isHospitalAdmin(adminId, otherHospital)).thenReturn(false);

        assertThatThrownBy(() -> followUp.overdueAtHospital(otherHospital))
            .isInstanceOf(AccessDeniedException.class);
        verify(notes, never()).findUnsignedAtHospitalCreatedBefore(any(), any());
    }

    @Test
    void noHospitalIsRefused() {
        assertThatThrownBy(() -> followUp.overdueAtHospital(null)).isInstanceOf(BusinessException.class);
        verify(notes, never()).findUnsignedAtHospitalCreatedBefore(any(), any());
    }
}
