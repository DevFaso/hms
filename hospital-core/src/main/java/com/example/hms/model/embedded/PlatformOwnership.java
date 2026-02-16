package com.example.hms.model.embedded;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Embeddable
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlatformOwnership {

    @Column(length = 120)
    private String ownerTeam;

    @Column(length = 255)
    private String ownerContactEmail;

    @Column(length = 120)
    private String dataSteward;

    @Column(length = 60)
    private String serviceLevel;

    public static PlatformOwnership empty() {
        return PlatformOwnership.builder().build();
    }
}
