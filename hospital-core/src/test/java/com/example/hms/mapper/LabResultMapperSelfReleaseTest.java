package com.example.hms.mapper;

import com.example.hms.model.LabResult;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.LabResultResponseDTO;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D58: a release by the person who entered the result is shown as a
 * self-release to every reader, so nobody takes it for a second person's check.
 */
class LabResultMapperSelfReleaseTest {

    private final LabResultMapper mapper = new LabResultMapper();

    private static LabResult enteredBy(UUID authorId) {
        User author = new User();
        author.setId(authorId);
        UserRoleHospitalAssignment assignment = new UserRoleHospitalAssignment();
        assignment.setUser(author);
        LabResult result = new LabResult();
        result.setId(UUID.randomUUID());
        result.setAssignment(assignment);
        return result;
    }

    @Test
    void releasedByItsAuthor_isASelfRelease() {
        UUID author = UUID.randomUUID();
        LabResult result = enteredBy(author);
        result.setReleased(true);
        result.setReleasedByUserId(author);

        LabResultResponseDTO dto = mapper.toResponseDTO(result);

        assertThat(dto.isSelfReleased()).isTrue();
        assertThat(dto.getEnteredByUserId()).isEqualTo(author.toString());
    }

    @Test
    void releasedBySomeoneElse_isNotASelfRelease() {
        LabResult result = enteredBy(UUID.randomUUID());
        result.setReleased(true);
        result.setReleasedByUserId(UUID.randomUUID());

        assertThat(mapper.toResponseDTO(result).isSelfReleased()).isFalse();
    }

    @Test
    void anUnreleasedResult_isNoSelfRelease() {
        UUID author = UUID.randomUUID();
        LabResult result = enteredBy(author);
        result.setReleasedByUserId(author);

        assertThat(mapper.toResponseDTO(result).isSelfReleased()).isFalse();
    }

    @Test
    void anAutoVerifiedResult_hasNoReleasingUser_andIsNoSelfRelease() {
        LabResult result = enteredBy(UUID.randomUUID());
        result.setReleased(true);

        assertThat(mapper.toResponseDTO(result).isSelfReleased()).isFalse();
    }
}
