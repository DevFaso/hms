package com.example.hms.payload.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EncounterNoteResponseDTO extends EncounterNoteBaseDTO {
    private UUID id;

    /**
     * QA D3863: whose chart the note is in. The restricted-chart masking
     * attributes a record by its patientId; a note answered on its own
     * (a co-signature) used to carry no patient at all.
     */
    private UUID patientId;
    private LocalDateTime updatedAt;

    /* QA D831: who wrote this entry. A stay holds one entry per progress note
       and author, so each entry says whose it is. */
    private UUID authorUserId;
    private String authorName;
    private String authorCredentials;
    private List<EncounterNoteAddendumResponseDTO> addenda;
    private List<EncounterLinkedArtifactDTO> linkedArtifacts;

    /* Ceremony evidence (V125). signatureValue null on a signed note means
       "asserted before V125, unverifiable". */
    private UUID signedByUserId;
    private String signatureAlgorithm;
    private String signatureValue;
    private LocalDateTime cosignedAt;
    private String cosignedByName;
}
