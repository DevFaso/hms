package com.example.hms.model;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import jakarta.persistence.MapsId;

@Entity
@Table(name = "user_roles", schema = "\"security\"",
    uniqueConstraints = @UniqueConstraint(columnNames = {"user_id","role_id"}))
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
@ToString(exclude = {"user","role"})
public class UserRole {

    @EmbeddedId
    private UserRoleId id;

    @MapsId("userId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_user_role_user"))
    private User user;

    @MapsId("roleId")
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "role_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_user_role_role"))
    private Role role;
}



