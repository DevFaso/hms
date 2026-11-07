package com.example.hms.payload.dto.review;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/** A held order replaced: why, and by which new order, written and signed the normal way first. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderReviewReplacementDTO {

    @NotBlank
    @Size(max = 1000)
    private String reason;

    @NotNull
    private UUID replacementOrderId;
}
