package com.example.hms.payload.dto.permission;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PermissionMatrixRowDTO {

    @NotBlank(message = "permission.matrix.domain.required")
    @Size(max = 255, message = "permission.matrix.domain.size")
    private String domain;

    @NotEmpty(message = "permission.matrix.actions.required")
    private List<@NotBlank(message = "permission.matrix.action.required") String> actions;

    @NotEmpty(message = "permission.matrix.owners.required")
    private List<@NotBlank(message = "permission.matrix.owner.required") String> owners;
}