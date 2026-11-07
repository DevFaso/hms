package com.example.hms.payload.dto;

import com.example.hms.enums.EncounterNoteTemplate;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EncounterNoteRequestDTO extends EncounterNoteBaseDTO {

    @NotNull
    private EncounterNoteTemplate template;

    private UUID authorStaffId;
    private UUID authorUserId;
    private String authorName;
    private String authorCredentials;

    @Size(max = 4096)
    private String chiefComplaint;

    // QA D1369 class (round 31): a link without its type or id is refused with the note, not at flush.
    private List<@jakarta.validation.Valid EncounterNoteLinkRequestDTO> customLinks;
    private Set<UUID> linkedLabOrderIds;
    private Set<UUID> linkedPrescriptionIds;
    private Set<UUID> linkedReferralIds;
}
