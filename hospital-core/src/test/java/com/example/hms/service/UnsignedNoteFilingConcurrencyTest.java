package com.example.hms.service;

import com.example.hms.enums.EmploymentType;
import com.example.hms.enums.EncounterStatus;
import com.example.hms.enums.EncounterType;
import com.example.hms.enums.InBasketItemStatus;
import com.example.hms.enums.JobTitle;
import com.example.hms.enums.OrganizationType;
import com.example.hms.mapper.InBasketMapper;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import com.example.hms.model.Patient;
import com.example.hms.model.Role;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.model.encounter.EncounterNote;
import com.example.hms.repository.EncounterNoteRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.InBasketItemRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.security.EncryptionKeyHolder;
import com.example.hms.service.impl.InBasketServiceImpl;
import com.example.hms.utility.RoleValidator;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/** Real H2 transactions and the real task store; no network service or shared database. */
@DataJpaTest
@ActiveProfiles("test")
@Import(EncryptionKeyHolder.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class UnsignedNoteFilingConcurrencyTest {
    @Autowired private EntityManager em;
    @Autowired private EncounterNoteRepository notes;
    @Autowired private InBasketItemRepository items;
    @Autowired private UserRepository users;
    @Autowired private HospitalRepository hospitals;
    @Autowired private PatientRepository patients;
    @Autowired private EncounterRepository encounters;
    @Autowired private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;
    private UnsignedNoteFollowUp followUp;
    private InBasketService basket;
    private UUID encounterId;
    private UUID noteId;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        InBasketService realBasket = new InBasketServiceImpl(items, users, hospitals, patients, encounters,
            new InBasketMapper());
        basket = mock(InBasketService.class, delegatesTo(realBasket));
        followUp = new UnsignedNoteFollowUp(notes, basket, items, mock(RoleValidator.class),
            transactionManager, Clock.systemUTC());
        tx.executeWithoutResult(status -> createFixture());
    }

    @Test
    void filingFirstCommitsItsTaskBeforeSignatureCleanup() throws Exception {
        CountDownLatch filingRead = new CountDownLatch(1);
        CountDownLatch releaseFiling = new CountDownLatch(1);
        CountDownLatch signingStarted = new CountDownLatch(1);
        doAnswer(invocation -> {
            filingRead.countDown();
            await(releaseFiling);
            var filed = new InBasketServiceImpl(items, users, hospitals, patients, encounters,
                new InBasketMapper()).createItem(invocation.getArgument(0));
            assertThat(filed).isNotNull();
            return filed;
        }).when(basket).createItem(any());
        FutureTask<Void> filing = task(() -> followUp.fileForVisit(encounterId));
        FutureTask<Void> signing = task(() -> tx.executeWithoutResult(status -> {
            signingStarted.countDown();
            signAndFlush();
            followUp.noteSigned(noteId);
        }));
        Thread filingThread = new Thread(filing, "unsigned-note-filing");
        Thread signingThread = new Thread(signing, "unsigned-note-signing");
        try {
            filingThread.start();
            await(filingRead);
            signingThread.start();
            await(signingStarted);
            // The UPDATE must wait for the filing transaction, rather than deleting before insertion.
            assertThatThrownBy(() -> signing.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
        } finally {
            releaseFiling.countDown();
            filingThread.join(10000);
            signingThread.join(10000);
        }
        filing.get(10, TimeUnit.SECONDS);
        signing.get(10, TimeUnit.SECONDS);
        assertSignedWithoutTask();
    }

    @Test
    void signingFirstMakesTheFilingReadObserveTheSignature() throws Exception {
        CountDownLatch signatureFlushed = new CountDownLatch(1);
        CountDownLatch releaseSignature = new CountDownLatch(1);
        CountDownLatch filingStarted = new CountDownLatch(1);
        FutureTask<Void> signing = task(() -> tx.executeWithoutResult(status -> {
            signAndFlush();
            signatureFlushed.countDown();
            await(releaseSignature);
            followUp.noteSigned(noteId);
        }));
        FutureTask<Void> filing = task(() -> {
            filingStarted.countDown();
            followUp.fileForVisit(encounterId);
        });
        Thread signingThread = new Thread(signing, "unsigned-note-signing-first");
        Thread filingThread = new Thread(filing, "unsigned-note-filing-second");
        try {
            signingThread.start();
            await(signatureFlushed);
            filingThread.start();
            await(filingStarted);
            assertThatThrownBy(() -> filing.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
        } finally {
            releaseSignature.countDown();
            signingThread.join(10000);
            filingThread.join(10000);
        }
        signing.get(10, TimeUnit.SECONDS);
        filing.get(10, TimeUnit.SECONDS);
        assertSignedWithoutTask();
    }

    private void signAndFlush() {
        EncounterNote note = notes.findById(noteId).orElseThrow();
        note.setSignedAt(LocalDateTime.now());
        notes.saveAndFlush(note);
    }

    @Test
    void filingAgainAfterReminderAndAcknowledgementKeepsOneTask() {
        followUp.fileForVisit(encounterId);
        tx.executeWithoutResult(status -> {
            var filed = items.findAll().stream().filter(item -> noteId.equals(item.getReferenceId())).toList();
            assertThat(filed).hasSize(1);
            filed.getFirst().setTitleKey(UnsignedNoteFollowUp.REMINDER_TITLE_KEY);
            filed.getFirst().setStatus(InBasketItemStatus.ACKNOWLEDGED);
            items.saveAndFlush(filed.getFirst());
        });

        followUp.fileForVisit(encounterId);

        tx.executeWithoutResult(status -> assertThat(items.findAll().stream()
            .filter(item -> noteId.equals(item.getReferenceId())).toList()).singleElement()
            .satisfies(item -> {
                assertThat(item.getTitleKey()).isEqualTo(UnsignedNoteFollowUp.REMINDER_TITLE_KEY);
                assertThat(item.getStatus()).isEqualTo(InBasketItemStatus.ACKNOWLEDGED);
            }));
    }

    private void assertSignedWithoutTask() {
        tx.executeWithoutResult(status -> {
            assertThat(notes.findById(noteId).orElseThrow().isSigned()).isTrue();
            assertThat(items.findAll()).noneMatch(item -> noteId.equals(item.getReferenceId()));
        });
    }

    private static FutureTask<Void> task(Runnable action) {
        return new FutureTask<>(() -> {
            action.run();
            return null;
        });
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private void createFixture() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Organization organization = Organization.builder().name("Signature Network").code("ORG-" + suffix)
            .type(OrganizationType.HOSPITAL_CHAIN).active(true).build();
        em.persist(organization);
        Hospital hospital = Hospital.builder().name("Signature Hospital").code("H-" + suffix)
            .city("Test City").country("Test Country").address("Test address").phoneNumber("5550001")
            .email(suffix + "@hospital.test").organization(organization).build();
        em.persist(hospital);
        User author = User.builder().username("author-" + suffix).passwordHash("test-hash")
            .email(suffix + "@author.test").firstName("Test").lastName("Author")
            .phoneNumber("555" + suffix).isActive(true).build();
        em.persist(author);
        Role role = Role.builder().name("Doctor " + suffix).code("ROLE_DOCTOR_" + suffix).build();
        em.persist(role);
        UserRoleHospitalAssignment assignment = UserRoleHospitalAssignment.builder().assignmentCode("A-" + suffix)
            .user(author).hospital(hospital).role(role).startDate(LocalDate.now())
            .assignedAt(LocalDateTime.now()).active(true).build();
        em.persist(assignment);
        Staff staff = Staff.builder().user(author).hospital(hospital).assignment(assignment)
            .jobTitle(JobTitle.PHYSICIAN).employmentType(EmploymentType.FULL_TIME)
            .licenseNumber("LIC-" + suffix).name("Test Author").active(true).build();
        em.persist(staff);
        User patientUser = User.builder().username("patient-" + suffix).passwordHash("test-hash")
            .email(suffix + "@patient-user.test").firstName("Test").lastName("Patient").isActive(true).build();
        em.persist(patientUser);
        Patient patient = Patient.builder().firstName("Test").lastName("Patient")
            .dateOfBirth(LocalDate.of(1990, 1, 1)).gender("F").address("Test address")
            .city("Test City").country("Test Country").phoneNumberPrimary("5550002")
            .email(suffix + "@patient.test").organizationId(organization.getId()).hospitalId(hospital.getId())
            .user(patientUser).build();
        em.persist(patient);
        Encounter encounter = Encounter.builder().patient(patient).staff(staff).hospital(hospital)
            .assignment(assignment).encounterType(EncounterType.OUTPATIENT).encounterDate(LocalDateTime.now())
            .status(EncounterStatus.COMPLETED).code("ENC-" + suffix).build();
        em.persist(encounter);
        EncounterNote note = EncounterNote.builder().encounter(encounter).patient(patient).hospital(hospital)
            .author(author).authorName("Test Author").subjective("Synthetic draft")
            .documentedAt(LocalDateTime.now()).build();
        em.persist(note);
        em.flush();
        encounterId = encounter.getId();
        noteId = note.getId();
    }
}
