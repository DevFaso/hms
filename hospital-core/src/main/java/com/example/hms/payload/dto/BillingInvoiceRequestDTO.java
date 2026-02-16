package com.example.hms.payload.dto;

import com.example.hms.enums.InvoiceStatus;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

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
