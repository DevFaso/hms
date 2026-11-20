package com.example.hms.payload.dto.billing;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Billing-money plan PR 10: the reason a session is reopened, or a supervisor's review note. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CashSessionNoteRequestDTO {
    @jakarta.validation.constraints.Size(max = 500)
    @com.example.hms.validation.NoCardNumber
    private String note;
}
