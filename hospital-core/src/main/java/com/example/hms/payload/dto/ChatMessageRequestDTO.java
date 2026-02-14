package com.example.hms.payload.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;


@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Data
public class ChatMessageRequestDTO {
    @NotBlank
    private String recipientEmail;

    @NotBlank
    private String hospitalName;

    @NotBlank
    private String content;

    private String roleCode;

}

