package com.example.hms.service.maternity;

import com.example.hms.enums.AdmissionType;
import com.example.hms.enums.InBasketItemType;
import com.example.hms.enums.InBasketPriority;
import com.example.hms.enums.WardType;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.Admission;
import com.example.hms.model.Bed;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.Ward;
import com.example.hms.model.labor.DeliveryNewborn;
import com.example.hms.model.labor.DeliveryRecord;
import com.example.hms.model.labor.LaborEpisode;
import com.example.hms.payload.dto.clinical.CreateInBasketItemRequest;
import com.example.hms.repository.BedRepository;
import com.example.hms.repository.DeliveryNewbornRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.service.InBasketService;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Policy rule 2 (2026-11-20, provisional): the midwife's newborn admission and maternity-ward beds. */
@org.junit.jupiter.api.extension.ExtendWith(com.example.hms.i18n.EnglishMessageBundles.class)
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MidwifeNewbornCareTest {

    private static final UUID HOSPITAL = UUID.randomUUID();
    private static final UUID MIDWIFE = UUID.randomUUID();

    @Mock private DeliveryNewbornRepository deliveryNewbornRepository;
    @Mock private BedRepository bedRepository;
    @Mock private RoleValidator roleValidator;
    @Mock private InBasketService inBasketService;
    @Mock private UserRoleHospitalAssignmentRepository assignmentRepository;

    private MidwifeNewbornCare care;
    private Hospital hospital;
    private Patient baby;

    @BeforeEach
    void setUp() {
        care = new MidwifeNewbornCare(deliveryNewbornRepository, bedRepository, roleValidator, inBasketService,
            assignmentRepository);
        hospital = Hospital.builder().build();
        hospital.setId(HOSPITAL);
        baby = new Patient();
        baby.setId(UUID.randomUUID());
        baby.setFirstName("Bébé");
        baby.setLastName("Ouedraogo");
        when(roleValidator.getCurrentUserId()).thenReturn(MIDWIFE);
        when(roleValidator.isMidwife(MIDWIFE, HOSPITAL)).thenReturn(true);
    }

    private static User user(UUID id) {
        User user = new User();
        user.setId(id);
        return user;
    }

    private static Staff staff(UUID userId) {
        Staff staff = new Staff();
        staff.setId(UUID.randomUUID());
        staff.setUser(user(userId));
        return staff;
    }

    private void linkBabyTo(DeliveryRecord delivery, Hospital at) {
        when(deliveryNewbornRepository.findByNewbornPatient_Id(baby.getId())).thenReturn(Optional.of(
            DeliveryNewborn.builder().deliveryRecord(delivery).newbornPatient(baby).hospital(at).birthOrder(1).build()));
    }

    // ── The newborn she may admit ────────────────────────────────────────

    @Test
    void sheAdmitsTheNewbornSheDelivered() {
        linkBabyTo(DeliveryRecord.builder().deliveredByStaff(staff(MIDWIFE)).build(), hospital);
        assertThatCode(() -> care.requireNewbornOfHerDelivery(baby, HOSPITAL, MIDWIFE)).doesNotThrowAnyException();
    }

    @Test
    void sheAdmitsTheNewbornOfADeliverySheIsAssignedTo() {
        // She documented the delivery, opened the labour episode, or documented it.
        linkBabyTo(DeliveryRecord.builder().documentedBy(user(MIDWIFE)).build(), hospital);
        assertThatCode(() -> care.requireNewbornOfHerDelivery(baby, HOSPITAL, MIDWIFE)).doesNotThrowAnyException();

        linkBabyTo(DeliveryRecord.builder()
            .episode(LaborEpisode.builder().admittedByStaff(staff(MIDWIFE)).build()).build(), hospital);
        assertThatCode(() -> care.requireNewbornOfHerDelivery(baby, HOSPITAL, MIDWIFE)).doesNotThrowAnyException();

        linkBabyTo(DeliveryRecord.builder()
            .episode(LaborEpisode.builder().documentedBy(user(MIDWIFE)).build()).build(), hospital);
        assertThatCode(() -> care.requireNewbornOfHerDelivery(baby, HOSPITAL, MIDWIFE)).doesNotThrowAnyException();
    }

    @Test
    void sheIsRefusedAColleaguesNewborn() {
        linkBabyTo(DeliveryRecord.builder().deliveredByStaff(staff(UUID.randomUUID()))
            .documentedBy(user(UUID.randomUUID())).build(), hospital);
        assertThatThrownBy(() -> care.requireNewbornOfHerDelivery(baby, HOSPITAL, MIDWIFE))
            .isInstanceOf(FieldBusinessException.class)
            .hasMessageContaining("a delivery she attended");
    }

    @Test
    void sheIsRefusedAPatientNoDeliveryRegisteredAndADeliveryOfAnotherHospital() {
        when(deliveryNewbornRepository.findByNewbornPatient_Id(baby.getId())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> care.requireNewbornOfHerDelivery(baby, HOSPITAL, MIDWIFE))
            .isInstanceOf(FieldBusinessException.class);

        Hospital elsewhere = Hospital.builder().build();
        elsewhere.setId(UUID.randomUUID());
        linkBabyTo(DeliveryRecord.builder().deliveredByStaff(staff(MIDWIFE)).build(), elsewhere);
        assertThatThrownBy(() -> care.requireNewbornOfHerDelivery(baby, HOSPITAL, MIDWIFE))
            .isInstanceOf(FieldBusinessException.class);
    }

    // ── The beds she may assign and release ──────────────────────────────

    private Admission stay(AdmissionType type) {
        Admission admission = new Admission();
        admission.setId(UUID.randomUUID());
        admission.setHospital(hospital);
        admission.setPatient(baby);
        admission.setAdmissionType(type);
        return admission;
    }

    private Bed bedIn(WardType type) {
        Ward ward = Ward.builder().hospital(hospital).name(type.name()).code(type.name()).wardType(type).build();
        Bed bed = Bed.builder().ward(ward).bedNumber("B1").build();
        bed.setId(UUID.randomUUID());
        when(bedRepository.findByIdAndWard_Hospital_Id(bed.getId(), HOSPITAL)).thenReturn(Optional.of(bed));
        return bed;
    }

    @Test
    void sheAssignsAMaternityBedOrCotWithNoTask() {
        assertThat(care.requireBedAMidwifeMayAssign(stay(AdmissionType.LABOR_DELIVERY), bedIn(WardType.MATERNITY).getId()))
            .isFalse();
        assertThat(care.requireBedAMidwifeMayAssign(stay(AdmissionType.NEWBORN), bedIn(WardType.MATERNITY).getId()))
            .isFalse();
    }

    @Test
    void sheNeverMovesAPatientOutOfAnotherWardsBed_theMoveWouldFreeABedThatIsNotHers() {
        // Rex review of #1243 (P2): assigning a bed releases the one the stay holds.
        Admission inSurgery = stay(AdmissionType.LABOR_DELIVERY);
        inSurgery.setBed(bedIn(WardType.SURGICAL));
        UUID maternityBed = bedIn(WardType.MATERNITY).getId();
        assertThatThrownBy(() -> care.requireBedAMidwifeMayAssign(inSurgery, maternityBed))
            .isInstanceOf(FieldBusinessException.class)
            .hasMessageContaining("maternity wards only");

        // From one maternity bed to another: hers on both sides.
        Admission inMaternity = stay(AdmissionType.LABOR_DELIVERY);
        inMaternity.setBed(bedIn(WardType.MATERNITY));
        assertThat(care.requireBedAMidwifeMayAssign(inMaternity, bedIn(WardType.MATERNITY).getId())).isFalse();

        // The same bed again changes nothing and frees nothing.
        Admission same = stay(AdmissionType.LABOR_DELIVERY);
        Bed held = bedIn(WardType.MATERNITY);
        same.setBed(held);
        assertThat(care.requireBedAMidwifeMayAssign(same, held.getId())).isFalse();
    }

    @Test
    void aNeonatalCotForANewbornIsAssignedAndAPhysicianIsAsked() {
        assertThat(care.requireBedAMidwifeMayAssign(stay(AdmissionType.NEWBORN), bedIn(WardType.NICU).getId()))
            .isTrue();
    }

    @Test
    void sheIsRefusedEveryOtherWard() {
        for (WardType type : WardType.values()) {
            if (type == WardType.MATERNITY) {
                continue;
            }
            UUID bedId = bedIn(type).getId();
            // A NICU cot is hers for a newborn stay only.
            Admission labour = stay(AdmissionType.LABOR_DELIVERY);
            assertThatThrownBy(() -> care.requireBedAMidwifeMayAssign(labour, bedId))
                .as("ward " + type)
                .isInstanceOf(FieldBusinessException.class)
                .hasMessageContaining("maternity wards only");
        }
    }

    @Test
    void aNurseOrAPhysicianWhoIsAlsoAMidwifeKeepsHerReach_andANonMidwifeIsUntouched() {
        UUID generalBed = bedIn(WardType.GENERAL).getId();
        when(roleValidator.isNurse(MIDWIFE, HOSPITAL)).thenReturn(true);
        assertThat(care.requireBedAMidwifeMayAssign(stay(AdmissionType.ELECTIVE), generalBed)).isFalse();

        when(roleValidator.isNurse(MIDWIFE, HOSPITAL)).thenReturn(false);
        when(roleValidator.isDoctor(MIDWIFE, HOSPITAL)).thenReturn(true);
        assertThat(care.requireBedAMidwifeMayAssign(stay(AdmissionType.ELECTIVE), generalBed)).isFalse();

        when(roleValidator.isDoctor(MIDWIFE, HOSPITAL)).thenReturn(false);
        when(roleValidator.isMidwife(MIDWIFE, HOSPITAL)).thenReturn(false);
        // Not a midwife here, admitted on a role the endpoint took before this rule: left as before.
        signIn("ROLE_SUPER_ADMIN");
        assertThat(care.requireBedAMidwifeMayAssign(stay(AdmissionType.ELECTIVE), generalBed)).isFalse();
    }

    @Test
    void aMidwifeRoleHeldAtAnotherHospitalAssignsAndReleasesNoBedHere() {
        // Her token carries ROLE_MIDWIFE (held at another hospital); here she is neither a midwife,
        // a nurse nor a physician, so the role that got her through the endpoint counts for nothing.
        when(roleValidator.isMidwife(MIDWIFE, HOSPITAL)).thenReturn(false);
        signIn("ROLE_MIDWIFE", "ROLE_RECEPTIONIST");
        UUID maternityBed = bedIn(WardType.MATERNITY).getId();
        Admission labour = stay(AdmissionType.LABOR_DELIVERY);
        assertThatThrownBy(() -> care.requireBedAMidwifeMayAssign(labour, maternityBed))
            .isInstanceOf(FieldBusinessException.class);

        Admission inMaternity = stay(AdmissionType.LABOR_DELIVERY);
        inMaternity.setBed(bedIn(WardType.MATERNITY));
        assertThatThrownBy(() -> care.requireBedAMidwifeMayRelease(inMaternity)).isInstanceOf(BusinessException.class);
    }

    @Test
    void aMidwifeHereIsNarrowedEvenWhenHerTokenCarriesANursesRoleFromElsewhere() {
        signIn("ROLE_MIDWIFE", "ROLE_NURSE");
        UUID generalBed = bedIn(WardType.GENERAL).getId();
        Admission labour = stay(AdmissionType.LABOR_DELIVERY);
        assertThatThrownBy(() -> care.requireBedAMidwifeMayAssign(labour, generalBed))
            .isInstanceOf(FieldBusinessException.class);
    }

    private static void signIn(String... authorities) {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
            new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("u", "n/a",
                java.util.Arrays.stream(authorities)
                    .map(org.springframework.security.core.authority.SimpleGrantedAuthority::new).toList()));
    }

    @org.junit.jupiter.api.AfterEach
    void clearSession() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    void sheReleasesAMaternityBedAndIsRefusedAnotherWards() {
        Admission inMaternity = stay(AdmissionType.LABOR_DELIVERY);
        inMaternity.setBed(bedIn(WardType.MATERNITY));
        assertThatCode(() -> care.requireBedAMidwifeMayRelease(inMaternity)).doesNotThrowAnyException();

        Admission inSurgery = stay(AdmissionType.ELECTIVE);
        inSurgery.setBed(bedIn(WardType.SURGICAL));
        assertThatThrownBy(() -> care.requireBedAMidwifeMayRelease(inSurgery)).isInstanceOf(BusinessException.class);

        // No bed: nothing of hers to refuse (the release itself answers).
        assertThatCode(() -> care.requireBedAMidwifeMayRelease(stay(AdmissionType.ELECTIVE))).doesNotThrowAnyException();
    }

    // ── The physician's task ─────────────────────────────────────────────

    @Test
    void thePhysiciansOfTheHospitalGetAnUrgentTaskEach() {
        User drA = user(UUID.randomUUID());
        User drB = user(UUID.randomUUID());
        when(assignmentRepository.findLiveUsersAtHospitalWithRoleCodes(HOSPITAL, MidwifeNewbornCare.PHYSICIAN_ROLE_CODES))
            .thenReturn(List.of(drA, drB));
        Admission admission = stay(AdmissionType.NEWBORN);
        admission.setBed(bedIn(WardType.NICU));

        care.askPhysicianAfterCommit(admission);

        ArgumentCaptor<CreateInBasketItemRequest> filed = ArgumentCaptor.forClass(CreateInBasketItemRequest.class);
        verify(inBasketService, times(2)).createItemInNewTransaction(filed.capture());
        CreateInBasketItemRequest item = filed.getAllValues().get(0);
        assertThat(item.getRecipientUserId()).isEqualTo(drA.getId());
        assertThat(item.getHospitalId()).isEqualTo(HOSPITAL);
        assertThat(item.getItemType()).isEqualTo(InBasketItemType.TASK);
        assertThat(item.getPriority()).isEqualTo(InBasketPriority.URGENT);
        assertThat(item.getReferenceId()).isEqualTo(admission.getId());
        assertThat(item.getReferenceType()).isEqualTo(MidwifeNewbornCare.REFERENCE_TYPE);
        assertThat(item.getPatientId()).isEqualTo(baby.getId());
        assertThat(item.getTitleKey()).isEqualTo(MidwifeNewbornCare.TITLE_KEY);
        assertThat(item.getBodyKey()).isEqualTo(MidwifeNewbornCare.BODY_KEY);
    }

    @Test
    void aFailureToFileTheTaskNeverThrows_andNoPhysicianFilesNothing() {
        when(assignmentRepository.findLiveUsersAtHospitalWithRoleCodes(any(), any())).thenReturn(List.of());
        Admission admission = stay(AdmissionType.NEWBORN);
        care.askPhysicianAfterCommit(admission);
        verify(inBasketService, never()).createItemInNewTransaction(any());

        when(assignmentRepository.findLiveUsersAtHospitalWithRoleCodes(any(), any()))
            .thenThrow(new IllegalStateException("database down"));
        assertThatCode(() -> care.askPhysicianAfterCommit(admission)).doesNotThrowAnyException();
    }
}
