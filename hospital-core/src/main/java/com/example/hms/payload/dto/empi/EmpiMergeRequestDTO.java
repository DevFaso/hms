package com.example.hms.payload.dto.empi;

import com.example.hms.enums.empi.EmpiMergeType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.UUID;

@Data
public class EmpiMergeRequestDTO {

    @NotNull(message = "{empiMerge.secondaryIdentityId.required}")
    private UUID secondaryIdentityId;

    @NotNull(message = "{empiMerge.mergeType.required}")
    private EmpiMergeType mergeType;

    @Size(max = 50)
    private String resolution;

    private String notes;
}
