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
public class PlatformServiceMetadata {

    @Column(length = 120)
    private String ehrSystem;

    @Column(length = 120)
    private String billingSystem;

    @Column(length = 120)
    private String inventorySystem;

    @Column(length = 255)
    private String integrationNotes;

    public static PlatformServiceMetadata empty() {
        return PlatformServiceMetadata.builder().build();
    }
}
