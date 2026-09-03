package com.example.hms.mapper;

import com.example.hms.model.Staff;
import com.example.hms.payload.dto.StaffResponseDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The licence expiry has to survive the mapper, null included (Tier 2 item
 * 40).
 *
 * <p>Null is the ordinary case: clinicians here are credentialed on a
 * diploma, which has no expiry. It is carried to the client so the staff
 * screen can offer credentialing to exactly the practitioners the
 * expiry-alert list cannot contain — that query requires a non-null expiry.
 * A mapper that quietly dropped the field would put the credentialing form
 * back out of reach for them without failing anything else.
 */
class StaffMapperTest {

    private final StaffMapper mapper = new StaffMapper();

    @Test
    @DisplayName("a licence expiry is carried through to the DTO")
    void carriesTheExpiryThrough() {
        Staff staff = new Staff();
        staff.setId(UUID.randomUUID());
        staff.setLicenseNumber("MED-1234");
        staff.setLicenseExpiryDate(LocalDate.of(2027, 9, 30));

        StaffResponseDTO dto = mapper.toStaffDTO(staff);

        assertThat(dto.getLicenseNumber()).isEqualTo("MED-1234");
        assertThat(dto.getLicenseExpiryDate()).isEqualTo(LocalDate.of(2027, 9, 30));
    }

    @Test
    @DisplayName("no expiry maps to null rather than being dropped or defaulted")
    void carriesANullExpiryThrough() {
        Staff staff = new Staff();
        staff.setId(UUID.randomUUID());
        staff.setLicenseNumber("DIPLOMA-77");
        staff.setLicenseExpiryDate(null);

        StaffResponseDTO dto = mapper.toStaffDTO(staff);

        assertThat(dto.getLicenseNumber()).isEqualTo("DIPLOMA-77");
        assertThat(dto.getLicenseExpiryDate()).isNull();
    }

    @Test
    @DisplayName("a null staff member maps to null, not an empty DTO")
    void nullStaffMapsToNull() {
        assertThat(mapper.toStaffDTO(null)).isNull();
    }
}
