package com.example.hms.service.imaging;

import com.example.hms.enums.ImagingModality;
import com.example.hms.enums.ImagingOrderPriority;
import com.example.hms.enums.InBasketPriority;
import com.example.hms.model.Department;
import com.example.hms.model.Hospital;
import com.example.hms.model.ImagingOrder;
import com.example.hms.model.Role;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.clinical.CreateInBasketItemRequest;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.service.InBasketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** QA D4140 review (r81): a request that waits for a physician is told to the physicians, urgent when STAT. */
@DisplayName("QA D4140: the physicians are told a request waits for them")
class ImagingAuthorisationRequestFollowUpTest {

    private final InBasketService inBasket = mock(InBasketService.class);
    private final UserRoleHospitalAssignmentRepository assignments = mock(UserRoleHospitalAssignmentRepository.class);
    private final StaffRepository staff = mock(StaffRepository.class);
    private final ImagingAuthorisationRequestFollowUp followUp =
        new ImagingAuthorisationRequestFollowUp(inBasket, assignments, staff);

    private final UUID nurse = UUID.randomUUID();
    private Hospital hospital;
    private ImagingOrder order;

    @BeforeEach
    void setUp() {
        hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        order = ImagingOrder.builder().hospital(hospital).modality(ImagingModality.CT).studyType("Scanner abdominal").build();
        order.setId(UUID.randomUUID());
        order.setRequestedByUserId(nurse);
        order.setRequestedByName("Awa Infirmière");
    }

    private static User user() {
        User user = new User();
        user.setId(UUID.randomUUID());
        return user;
    }

    private static Staff staffOf(User user, String roleCode) {
        Role role = new Role();
        role.setCode(roleCode);
        UserRoleHospitalAssignment assignment = new UserRoleHospitalAssignment();
        assignment.setRole(role);
        Staff s = new Staff();
        s.setUser(user);
        s.setAssignment(assignment);
        return s;
    }

    private List<CreateInBasketItemRequest> filed(int count) {
        ArgumentCaptor<CreateInBasketItemRequest> items = ArgumentCaptor.forClass(CreateInBasketItemRequest.class);
        verify(inBasket, times(count)).createItemInNewTransaction(items.capture());
        return items.getAllValues();
    }

    @Test
    void theRequestersDepartmentsPhysiciansFirst_andAStatRequestIsUrgent() {
        Department department = new Department();
        department.setId(UUID.randomUUID());
        Staff requester = new Staff();
        requester.setDepartment(department);
        when(staff.findByUserIdAndHospitalId(nurse, hospital.getId())).thenReturn(Optional.of(requester));
        User doctor = user();
        when(staff.findActiveProvidersByHospitalAndDepartment(hospital.getId(), department.getId()))
            .thenReturn(List.of(staffOf(doctor, "ROLE_DOCTOR"), staffOf(user(), "ROLE_NURSE")));
        order.setPriority(ImagingOrderPriority.STAT);

        followUp.requestPlaced(order);

        CreateInBasketItemRequest item = filed(1).get(0);
        assertThat(item.getRecipientUserId()).isEqualTo(doctor.getId());
        assertThat(item.getPriority()).isEqualTo(InBasketPriority.URGENT);
        assertThat(item.getTitleKey()).isEqualTo(ImagingAuthorisationRequestFollowUp.URGENT_TITLE_KEY);
        assertThat(item.getReferenceType()).isEqualTo(ImagingAuthorisationRequestFollowUp.REFERENCE_TYPE);
        assertThat(item.getReferenceId()).isEqualTo(order.getId());
    }

    @Test
    void withNoPhysicianInTheDepartment_everyPhysicianOfTheHospital_andARoutineRequestIsNormal() {
        when(staff.findByUserIdAndHospitalId(nurse, hospital.getId())).thenReturn(Optional.empty());
        User first = user();
        User second = user();
        when(assignments.findLiveUsersAtHospitalWithRoleCodes(eq(hospital.getId()), any()))
            .thenReturn(List.of(first, second));
        order.setPriority(ImagingOrderPriority.ROUTINE);

        followUp.requestPlaced(order);

        List<CreateInBasketItemRequest> items = filed(2);
        assertThat(items).extracting(CreateInBasketItemRequest::getRecipientUserId)
            .containsExactlyInAnyOrder(first.getId(), second.getId());
        assertThat(items).allSatisfy(item -> assertThat(item.getPriority()).isEqualTo(InBasketPriority.NORMAL));
    }

    @Test
    void aSettledRequestLeavesTheInBaskets() {
        followUp.requestSettled(order);

        verify(inBasket).removeOpenItemsForReference(order.getId(), ImagingAuthorisationRequestFollowUp.REFERENCE_TYPE);
    }
}
