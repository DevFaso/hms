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
 * Documents patient education touchpoints tailored for high-risk pregnancies.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Embeddable
public class HighRiskEducationTopic {

    @Size(max = 150)
    @Column(name = "topic", length = 150)
    private String topic;

    @Size(max = 500)
    @Column(name = "guidance", length = 500)
    private String guidance;

    @Size(max = 500)
    @Column(name = "materials", length = 500)
    private String materials;
}
