package com.example.hms.payload.dto;

import com.example.hms.enums.InvoiceStatus;
import jakarta.validation.constraints.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BillingInvoiceRequestDTO {


    private String id;

    @NotBlank
    private String patientEmail;

    @NotBlank
    private String hospitalName;

    private String encounterReference;

    @NotBlank
    @Size(max = 50)
    private String invoiceNumber;

    @NotNull
    private LocalDate invoiceDate;

    @NotNull
    private LocalDate dueDate;

    @NotNull
    @Digits(integer = 10, fraction = 2)
    @PositiveOrZero
    private BigDecimal totalAmount;

    @NotNull
    @Digits(integer = 10, fraction = 2)
    @PositiveOrZero
    private BigDecimal amountPaid;

    @NotNull
    private InvoiceStatus status;

    @Size(max = 2048)
    private String notes;
}
