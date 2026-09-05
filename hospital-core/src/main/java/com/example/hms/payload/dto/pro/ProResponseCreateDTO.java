package com.example.hms.payload.dto.pro;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * One administration of an instrument: which option the respondent chose
 * for each item. Scores are never sent — the server looks them up.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProResponseCreateDTO {

    @NotBlank
    @Size(max = 40)
    private String instrumentCode;

    /** Language the items were presented in. */
    @Size(max = 8)
    private String language;

    /** itemNo → optionNo. An item left out is unanswered; the total then is a lower bound. */
    @NotEmpty
    private Map<Integer, Integer> answers;

    /** Defaults to now. */
    private LocalDateTime administeredAt;

    @Size(max = 4000)
    private String notes;

    /** Staff surface only; the /me surface ignores it. */
    private UUID hospitalId;
}
