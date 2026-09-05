package com.example.hms.payload.dto.superadmin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmergencyForceMfaRequestDTO {

    /** Optional. When null/empty, every user with an enrolment is reset. */
    private List<UUID> userIds;

    /**
     * MVP-7b: optional hospital scope. When set, the reset only fires
     * for users with an active assignment to this hospital. Combines
     * with {@link #userIds} as an intersection — only the named users
     * *and* members of the named hospital are touched. When
     * {@link #userIds} is empty, the reset narrows to "every enrolled
     * user at this hospital".
     */
    private UUID hospitalId;

    /**
     * Must be {@code true} when {@link #userIds} is empty. An empty list
     * used to mean "everyone" silently — one blank field away from
     * resetting every enrolled user on the platform (the actor included).
     * The portal now asks for a typed confirmation and sends this flag;
     * the service refuses an empty list without it.
     */
    private Boolean resetAll;

    @NotBlank
    @Size(min = 5, max = 1000)
    private String reason;
}
