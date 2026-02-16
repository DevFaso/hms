package com.example.hms.model;

import com.example.hms.model.platform.DepartmentPlatformServiceLink;
import com.example.hms.model.platform.HospitalPlatformServiceLink;
import com.example.hms.model.platform.OrganizationPlatformService;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformDomainModelTest {

    @Test
    void addingHospitalLinkSetsBidirectionalRelation() {
        OrganizationPlatformService service = OrganizationPlatformService.builder().build();
        Hospital hospital = Hospital.builder().build();
        hospital.setId(UUID.randomUUID());

        HospitalPlatformServiceLink link = HospitalPlatformServiceLink.builder()
            .hospital(hospital)
            .build();

        service.addHospitalLink(link);

        assertThat(service.getHospitalLinks()).containsExactly(link);
        assertThat(link.getOrganizationService()).isSameAs(service);
    }

    @Test
    void removingHospitalLinkBreaksBidirectionalRelation() {
        OrganizationPlatformService service = OrganizationPlatformService.builder().build();
        Hospital hospital = Hospital.builder().build();
        hospital.setId(UUID.randomUUID());
        HospitalPlatformServiceLink link = HospitalPlatformServiceLink.builder()
            .hospital(hospital)
            .organizationService(service)
            .build();
        service.getHospitalLinks().add(link);

        service.removeHospitalLink(link);

        assertThat(service.getHospitalLinks()).isEmpty();
        assertThat(link.getOrganizationService()).isNull();
    }

    @Test
    void isLinkedToReturnsTrueForLinkedHospital() {
        OrganizationPlatformService service = OrganizationPlatformService.builder().build();
        Hospital hospital = Hospital.builder().build();
        UUID hospitalId = UUID.randomUUID();
        hospital.setId(hospitalId);

        HospitalPlatformServiceLink link = HospitalPlatformServiceLink.builder()
            .hospital(hospital)
            .build();
        service.addHospitalLink(link);

        assertThat(service.isLinkedTo(hospital)).isTrue();
    }

    @Test
    void addingDepartmentLinkSetsBidirectionalRelation() {
        OrganizationPlatformService service = OrganizationPlatformService.builder().build();
        Department department = Department.builder().build();
        department.setId(UUID.randomUUID());

        DepartmentPlatformServiceLink link = DepartmentPlatformServiceLink.builder()
            .department(department)
            .build();

        service.addDepartmentLink(link);

        assertThat(service.getDepartmentLinks()).containsExactly(link);
        assertThat(link.getOrganizationService()).isSameAs(service);
    }

    @Test
    void removingDepartmentLinkBreaksBidirectionalRelation() {
        OrganizationPlatformService service = OrganizationPlatformService.builder().build();
        Department department = Department.builder().build();
        department.setId(UUID.randomUUID());
        DepartmentPlatformServiceLink link = DepartmentPlatformServiceLink.builder()
            .department(department)
            .organizationService(service)
            .build();
        service.getDepartmentLinks().add(link);

        service.removeDepartmentLink(link);

        assertThat(service.getDepartmentLinks()).isEmpty();
        assertThat(link.getOrganizationService()).isNull();
    }

    @Test
    void hospitalHelpersMaintainBidirectionalConsistency() {
        Hospital hospital = Hospital.builder().build();
        OrganizationPlatformService service = OrganizationPlatformService.builder().build();
        HospitalPlatformServiceLink link = HospitalPlatformServiceLink.builder()
            .organizationService(service)
            .build();

        hospital.addPlatformServiceLink(link);

        assertThat(hospital.getPlatformServiceLinks()).contains(link);
        assertThat(link.getHospital()).isSameAs(hospital);

        hospital.removePlatformServiceLink(link);

        assertThat(hospital.getPlatformServiceLinks()).doesNotContain(link);
        assertThat(link.getHospital()).isNull();
    }

    @Test
    void departmentHelpersMaintainBidirectionalConsistency() {
        Department department = Department.builder().build();
        OrganizationPlatformService service = OrganizationPlatformService.builder().build();
        DepartmentPlatformServiceLink link = DepartmentPlatformServiceLink.builder()
            .organizationService(service)
            .build();

        department.addPlatformServiceLink(link);

        assertThat(department.getPlatformServiceLinks()).contains(link);
        assertThat(link.getDepartment()).isSameAs(department);

        department.removePlatformServiceLink(link);

        assertThat(department.getPlatformServiceLinks()).doesNotContain(link);
        assertThat(link.getDepartment()).isNull();
    }
}
