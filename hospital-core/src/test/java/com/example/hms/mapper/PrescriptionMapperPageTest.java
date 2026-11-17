package com.example.hms.mapper;

import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Prescription;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.payload.dto.PrescriptionResponseDTO;
import com.example.hms.repository.MedicationCatalogItemRepository;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * QA D1314: a page of orders is mapped at once. Every row must say what the
 * one-row mapping says, while the catalogue is read once for the page and an
 * unsigned order's prescriber once per prescriber.
 */
@DisplayName("D1314: a page of orders, mapped at once")
class PrescriptionMapperPageTest {

    private final UUID hospitalId = UUID.randomUUID();
    private final UUID otherHospitalId = UUID.randomUUID();
    private final UUID controlledItem = UUID.randomUUID();
    private final UUID plainItem = UUID.randomUUID();
    private final Hospital hospital = hospital(hospitalId);

    private MedicationCatalogItemRepository catalog;
    private RoleValidator roles;
    private PrescriptionMapper mapper;

    private static Hospital hospital(UUID id) {
        Hospital h = new Hospital();
        h.setId(id);
        h.setName("H");
        return h;
    }

    private static MedicationCatalogItem controlledLine() {
        MedicationCatalogItem item = new MedicationCatalogItem();
        item.setControlled(true);
        return item;
    }

    @BeforeEach
    void setUp() {
        catalog = mock(MedicationCatalogItemRepository.class);
        roles = mock(RoleValidator.class);
        mapper = new PrescriptionMapper();
        mapper.setCatalogRepository(catalog);
        mapper.setRoleValidator(roles);
        // The catalogue, answered both ways: one line at a time and in bulk.
        lenient().when(catalog.findByIdAndHospital_Id(controlledItem, hospitalId)).thenReturn(Optional.of(controlledLine()));
        lenient().when(catalog.findByIdAndHospital_Id(plainItem, hospitalId)).thenReturn(Optional.empty());
        lenient().when(catalog.findByHospitalIdAndCode(hospitalId, "MORPH")).thenReturn(Optional.of(controlledLine()));
        lenient().when(catalog.findByHospitalIdAndCode(otherHospitalId, "MORPH")).thenReturn(Optional.empty());
        List<Object[]> controlled = new ArrayList<>();
        controlled.add(new Object[] {controlledItem, hospitalId, "CTRL"});
        controlled.add(new Object[] {UUID.randomUUID(), hospitalId, "MORPH"});
        lenient().when(catalog.findControlledAmong(any(), any(), any())).thenReturn(controlled);
    }

    private Prescription order(Hospital at, UUID item, String code, boolean ticked) {
        Prescription rx = new Prescription();
        rx.setId(UUID.randomUUID());
        rx.setHospital(at);
        rx.setEncounter(new Encounter());
        rx.setMedicationName("Drug");
        rx.setMedicationCatalogItemId(item);
        rx.setMedicationCode(code);
        rx.setControlledSubstance(ticked);
        rx.setStatus(PrescriptionStatus.SIGNED);
        return rx;
    }

    @Test
    @DisplayName("every combination of tick, item and code says what the one-row mapping says")
    void pageAgreesWithTheOneRowMapping() {
        Hospital other = hospital(otherHospitalId);
        List<Prescription> rows = List.of(
            order(hospital, controlledItem, null, false),
            order(hospital, plainItem, null, false),
            // The item decides, whatever the code.
            order(hospital, plainItem, "MORPH", false),
            order(hospital, null, "MORPH", false),
            order(hospital, null, "  MORPH ", false),
            order(hospital, null, "OTHER", false),
            order(hospital, null, "  ", false),
            order(hospital, null, null, false),
            order(hospital, null, null, true),
            order(other, null, "MORPH", false),
            order(other, controlledItem, null, false));

        List<PrescriptionResponseDTO> page = mapper.toResponseDTOs(rows);

        assertThat(page).hasSameSizeAs(rows);
        for (int i = 0; i < rows.size(); i++) {
            assertThat(page.get(i)).as("row %d", i).usingRecursiveComparison().isEqualTo(mapper.toResponseDTO(rows.get(i)));
        }
        assertThat(page).extracting(PrescriptionResponseDTO::isControlledSubstance)
            .containsExactly(true, false, false, true, true, false, false, false, true, false, false);
    }

    @Test
    @DisplayName("the catalogue is read once for the page")
    void catalogueReadOnce() {
        mapper.toResponseDTOs(List.of(order(hospital, controlledItem, null, false), order(hospital, null, "MORPH", false),
            order(hospital, plainItem, null, false)));

        verify(catalog, times(1)).findControlledAmong(any(), any(), any());
        verify(catalog, never()).findByIdAndHospital_Id(any(), any());
        verify(catalog, never()).findByHospitalIdAndCode(any(), any());
    }

    @Test
    @DisplayName("a page that names no catalogue line asks nothing")
    void nothingToAsk() {
        mapper.toResponseDTOs(List.of(order(hospital, null, null, false), order(hospital, null, null, true)));

        verify(catalog, never()).findControlledAmong(any(), any(), any());
    }

    @Test
    @DisplayName("an unsigned order's prescriber is looked up once, and each row keeps the answer")
    void prescriberLookedUpOnce() {
        User user = new User();
        user.setId(UUID.randomUUID());
        Staff staff = new Staff();
        staff.setId(UUID.randomUUID());
        staff.setUser(user);
        lenient().when(roles.draftsForPhysicianSignature(user.getId(), hospitalId)).thenReturn(true);
        Prescription first = order(hospital, null, null, false);
        first.setStatus(PrescriptionStatus.DRAFT);
        first.setStaff(staff);
        Prescription second = order(hospital, null, null, false);
        second.setStatus(PrescriptionStatus.PENDING_SIGNATURE);
        second.setStaff(staff);
        Prescription signed = order(hospital, null, null, false);
        signed.setStaff(staff);

        List<PrescriptionResponseDTO> page = mapper.toResponseDTOs(List.of(first, second, signed));

        assertThat(page).extracting(PrescriptionResponseDTO::isAwaitingPhysicianSignature).containsExactly(true, true, false);
        verify(roles, times(1)).draftsForPhysicianSignature(user.getId(), hospitalId);
    }
}
