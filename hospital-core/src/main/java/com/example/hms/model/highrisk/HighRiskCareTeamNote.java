package com.example.hms.model.highrisk;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Records coordination updates shared between members of the care team.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Embeddable
public class HighRiskCareTeamNote {

    @Column(name = "note_id", nullable = false)
    @Builder.Default
    private UUID noteId = UUID.randomUUID();

    @Column(name = "logged_at", nullable = false)
    private LocalDateTime loggedAt;

    @Size(max = 120)
    @Column(name = "author", length = 120)
    private String author;

    @Size(max = 500)
    @Column(name = "summary", length = 500)
    private String summary;

    @Size(max = 500)
    @Column(name = "follow_up", length = 500)
    private String followUp;
}
