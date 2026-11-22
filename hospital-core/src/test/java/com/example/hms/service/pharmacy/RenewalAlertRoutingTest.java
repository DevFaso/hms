package com.example.hms.service.pharmacy;

import com.example.hms.model.Department;
import com.example.hms.model.Hospital;
import com.example.hms.model.Prescription;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * QA D3301 (r70): a renewal request is announced to one person, the prescriber; to the designated cover (the head
 * of the prescriber's department) only when the prescriber no longer answers there; to nobody otherwise. Never to
 * the department.
 */
@DisplayName("D3301: who is told of a renewal request (provisoire)")
class RenewalAlertRoutingTest {

    private final RoleValidator roles = mock(RoleValidator.class);
    private final RenewalAlertRouting routing = new RenewalAlertRouting(roles);

    private Hospital hospital;
    private Staff prescriber;
    private Staff head;
    private Prescription prescription;

    @BeforeEach
    void setUp() {
        hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        Department department = new Department();
        department.setId(UUID.randomUUID());
        head = staff(hospital, department);
        department.setHeadOfDepartment(head);
        prescriber = staff(hospital, department);
        prescription = new Prescription();
        prescription.setId(UUID.randomUUID());
        prescription.setStaff(prescriber);
        prescription.setHospital(hospital);
        mayRenew(head, true);
        mayRenew(prescriber, true);
    }

    private void mayRenew(Staff staff, boolean allowed) {
        when(roles.canApproveRenewal(staff.getUser().getId(), hospital.getId(), false)).thenReturn(allowed);
    }

    private static Staff staff(Hospital at, Department in) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername("u-" + user.getId());
        Staff staff = new Staff();
        staff.setId(UUID.randomUUID());
        staff.setUser(user);
        staff.setHospital(at);
        staff.setDepartment(in);
        return staff;
    }

    @Test
    @DisplayName("a prescriber who is active and may still renew there answers their own requests; the head is no cover while they do")
    void theActivePrescriberAnswers() {
        assertThat(routing.answererOf(prescription)).containsSame(prescriber);
        assertThat(routing.answers(prescriber, hospital.getId())).isTrue();
        assertThat(routing.isCoverOf(head.getId(), prescription)).isFalse();
        assertThat(RenewalAlertRouting.isPrescriber(prescriber, prescription)).isTrue();
        assertThat(RenewalAlertRouting.isPrescriber(head, prescription)).isFalse();
    }

    @Test
    @DisplayName("the prescriber's staff record or account is switched off: the head of their department is the cover")
    void theHeadCoversAPrescriberWhoLeft() {
        prescriber.setActive(false);
        assertThat(routing.answererOf(prescription)).containsSame(head);
        assertThat(routing.isCoverOf(head.getId(), prescription)).isTrue();
        assertThat(routing.isCoverOf(prescriber.getId(), prescription)).isFalse();
        assertThat(routing.isCoverOf(UUID.randomUUID(), prescription)).isFalse();

        prescriber.setActive(true);
        prescriber.getUser().setActive(false);
        assertThat(routing.answererOf(prescription)).as("account disabled").containsSame(head);

        prescriber.getUser().setActive(true);
        prescriber.getUser().setDeleted(true);
        assertThat(routing.answererOf(prescription)).as("account deleted").containsSame(head);
    }

    @Test
    @DisplayName("review of #1290: an assignment deactivated leaves the staff row and the account active; a prescriber who may no longer renew there is covered")
    void aPrescriberWhoMayNoLongerRenewIsCovered() {
        mayRenew(prescriber, false);

        assertThat(routing.answers(prescriber, hospital.getId())).isFalse();
        assertThat(routing.answererOf(prescription)).containsSame(head);
        assertThat(routing.isCoverOf(head.getId(), prescription)).isTrue();
    }

    @Test
    @DisplayName("no cover, nobody: a head who may not renew there, is inactive, belongs to another hospital or is the prescriber is not one")
    void withoutAUsableCoverNobodyIsTold() {
        prescriber.setActive(false);

        mayRenew(head, false);
        assertThat(routing.answererOf(prescription)).as("the head may not approve a renewal here").isEmpty();
        mayRenew(head, true);

        head.setActive(false);
        assertThat(routing.answererOf(prescription)).as("the head is inactive too").isEmpty();
        head.setActive(true);

        Hospital elsewhere = new Hospital();
        elsewhere.setId(UUID.randomUUID());
        head.setHospital(elsewhere);
        assertThat(routing.answererOf(prescription)).as("a head of another hospital").isEmpty();
        head.setHospital(hospital);

        prescriber.getDepartment().setHeadOfDepartment(prescriber);
        assertThat(routing.answererOf(prescription)).as("the prescriber heads the department").isEmpty();

        prescriber.setDepartment(null);
        assertThat(routing.answererOf(prescription)).as("no department").isEmpty();
    }

    @Test
    @DisplayName("what a cover lists: the members of the departments they head who no longer answer there, by the same rule")
    void whatACoverLists() {
        Staff colleague = staff(hospital, prescriber.getDepartment());
        mayRenew(colleague, true);
        Staff ofAnotherDepartment = staff(hospital, new Department());
        ofAnotherDepartment.setActive(false);
        List<Staff> members = List.of(prescriber, colleague, head, ofAnotherDepartment);

        assertThat(routing.noLongerAnswering(head, members, hospital.getId())).as("everybody answers").isEmpty();

        prescriber.setActive(false);
        mayRenew(colleague, false);
        assertThat(routing.noLongerAnswering(head, members, hospital.getId()))
            .as("the one who left and the one who may no longer renew; never the cover, never another department")
            .containsExactlyInAnyOrder(prescriber.getId(), colleague.getId());

        mayRenew(head, false);
        assertThat(routing.noLongerAnswering(head, members, hospital.getId()))
            .as("a head who may not renew here covers nobody").isEmpty();
        mayRenew(head, true);
        assertThat(routing.noLongerAnswering(head, members, UUID.randomUUID()))
            .as("not at another hospital").isEmpty();
        assertThat(routing.noLongerAnswering(head, null, hospital.getId())).isEmpty();
    }

    @Test
    @DisplayName("nothing to route: no prescription, no prescriber, no hospital")
    void nothingToRoute() {
        assertThat(routing.answererOf(null)).isEmpty();
        prescription.setHospital(null);
        assertThat(routing.answererOf(prescription)).as("no hospital to hold a right at").isEmpty();
        prescription.setHospital(hospital);
        prescription.setStaff(null);
        assertThat(routing.answererOf(prescription)).isEmpty();
        assertThat(routing.isCoverOf(head.getId(), prescription)).isFalse();
        assertThat(routing.answers(null, hospital.getId())).isFalse();
    }
}
