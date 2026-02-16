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
 * Describes community and emotional support offerings surfaced to patients.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Embeddable
public class HighRiskSupportResource {

    @Size(max = 150)
    @Column(name = "resource_name", length = 150)
    private String name;

    @Size(max = 120)
    @Column(name = "resource_type", length = 120)
    private String type;

    @Size(max = 240)
    @Column(name = "contact_details", length = 240)
    private String contact;

    @Size(max = 240)
    @Column(name = "resource_url", length = 240)
    private String url;

    @Size(max = 300)
    @Column(name = "resource_notes", length = 300)
    private String notes;
}
