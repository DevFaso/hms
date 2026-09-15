package com.example.hms.payload.dto.empi;

import com.example.hms.enums.empi.EmpiAliasType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class EmpiAliasRequestDTO {

    @NotNull(message = "{empiAlias.aliasType.required}")
    private EmpiAliasType aliasType;

    @NotBlank(message = "{empiAlias.aliasValue.required}")
    @Size(max = 255)
    private String aliasValue;

    @Size(max = 100)
    private String sourceSystem;
}
