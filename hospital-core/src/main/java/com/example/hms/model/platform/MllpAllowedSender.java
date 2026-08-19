package com.example.hms.model.platform;

import com.example.hms.model.BaseEntity;
import com.example.hms.model.Hospital;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(
    name = "mllp_allowed_senders",
    schema = "platform",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uq_mllp_sender_app_facility",
            columnNames = {"sending_application", "sending_facility"})
    },
    indexes = {
        @Index(name = "idx_mllp_sender_hospital", columnList = "hospital_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class MllpAllowedSender extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_mllp_sender_hospital"))
    private Hospital hospital;

    @NotBlank
    @Size(max = 180)
    @Column(name = "sending_application", nullable = false, length = 180)
    private String sendingApplication;

    @NotBlank
    @Size(max = 180)
    @Column(name = "sending_facility", nullable = false, length = 180)
    private String sendingFacility;

    @Size(max = 255)
    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private boolean active = true;
}
