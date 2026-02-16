package com.example.hms.model;

import com.example.hms.enums.RecoveryContactType;
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
@Table(name = "user_recovery_contacts", schema = "\"security\"")
public class UserRecoveryContact extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "contact_type", nullable = false, length = 50)
    private RecoveryContactType contactType;

    @Column(name = "contact_value", nullable = false, length = 255)
    private String contactValue;

    @Builder.Default
    @Column(name = "verified", nullable = false)
    private boolean verified = false;

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    @Builder.Default
    @Column(name = "primary_contact", nullable = false)
    private boolean primaryContact = false;

    @Column(name = "notes", length = 255)
    private String notes;
}
