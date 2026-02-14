package com.example.hms.payload.dto;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class JwtResponse {
    @Builder.Default private String tokenType = "Bearer";
    private String accessToken;
    private String refreshToken;

    private long issuedAt;
    private long accessTokenExpiresAt;
    private long refreshTokenExpiresAt;

    private UUID id;
    private String username;
    private String email;
    private String firstName;
    private String lastName;
    private String phoneNumber;
    private LocalDate dateOfBirth;
    private String gender;
    private List<String> roles;
    private String profileType;
    private String licenseNumber;
    private UUID patientId;
    private UUID staffId;
    private String roleName;
    private boolean active;
    private String profilePictureUrl;

}
