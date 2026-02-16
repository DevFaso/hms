package com.example.hms.model;

import com.example.hms.enums.MfaMethodType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@Entity
@Table(name = "user_mfa_enrollments", schema = "\"security\"")
public class UserMfaEnrollment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, length = 50)
    private MfaMethodType method;

    @Column(name = "channel", length = 120)
    private String channel;

    @Builder.Default
    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Builder.Default
    @Column(name = "primary_factor", nullable = false)
    private boolean primaryFactor = false;

    @Column(name = "enrolled_at")
    private LocalDateTime enrolledAt;

    @Column(name = "last_verified_at")
    private LocalDateTime lastVerifiedAt;

    @Column(name = "metadata_json")
    private String metadataJson;
}
