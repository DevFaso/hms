package com.example.hms.patient.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;

public class PatientInsuranceDto {
    private UUID id;

    @NotBlank
    @Size(max = 180)
    private String providerName;

    @Size(max = 120)
    private String policyNumber;

    @Size(max = 120)
    private String memberId;

    @Size(max = 120)
    private String groupNumber;

    private LocalDate coverageStart;
    private LocalDate coverageEnd;
    private boolean primaryPlan;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getProviderName() {
        return providerName;
    }

    public void setProviderName(String providerName) {
        this.providerName = providerName;
    }

    public String getPolicyNumber() {
        return policyNumber;
    }

    public void setPolicyNumber(String policyNumber) {
        this.policyNumber = policyNumber;
    }

    public String getMemberId() {
        return memberId;
    }

    public void setMemberId(String memberId) {
        this.memberId = memberId;
    }

    public String getGroupNumber() {
        return groupNumber;
    }

    public void setGroupNumber(String groupNumber) {
        this.groupNumber = groupNumber;
    }

    public LocalDate getCoverageStart() {
        return coverageStart;
    }

    public void setCoverageStart(LocalDate coverageStart) {
        this.coverageStart = coverageStart;
    }

    public LocalDate getCoverageEnd() {
        return coverageEnd;
    }

    public void setCoverageEnd(LocalDate coverageEnd) {
        this.coverageEnd = coverageEnd;
    }

    public boolean isPrimaryPlan() {
        return primaryPlan;
    }

    public void setPrimaryPlan(boolean primaryPlan) {
        this.primaryPlan = primaryPlan;
    }
}
