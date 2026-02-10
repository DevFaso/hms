package com.example.hms.model.highrisk;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Captures the multidisciplinary care team engaged for a high-risk pregnancy.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Embeddable
public class HighRiskCareTeamMember {

    @Size(max = 150)
    @Column(name = "member_name", length = 150)
    private String name;

    @Size(max = 120)
    @Column(name = "member_role", length = 120)
    private String role;

    @Size(max = 120)
    @Column(name = "contact", length = 120)
    private String contact;

    @Size(max = 300)
    @Column(name = "coverage_notes", length = 300)
    private String notes;
}
