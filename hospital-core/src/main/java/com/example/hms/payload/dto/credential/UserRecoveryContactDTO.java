package com.example.hms.payload.dto.credential;

import com.example.hms.enums.RecoveryContactType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserRecoveryContactDTO {

    private UUID id;
    private RecoveryContactType contactType;
    private String contactValue;
    private boolean verified;
    private LocalDateTime verifiedAt;
    private boolean primaryContact;
    private String notes;
}
