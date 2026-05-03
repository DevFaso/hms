package com.example.hms.mapper;

import com.example.hms.enums.integration.IntegrationHealthStatus;
import com.example.hms.model.Organization;
import com.example.hms.model.integration.IntegrationHealthSnapshot;
import com.example.hms.payload.dto.superadmin.IntegrationHealthOrgEntryDTO;
import org.springframework.stereotype.Component;

@Component
public class IntegrationHealthSnapshotMapper {

    public IntegrationHealthOrgEntryDTO toDto(IntegrationHealthSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        Organization org = snapshot.getOrganization();
        return IntegrationHealthOrgEntryDTO.builder()
            .organizationId(org == null ? null : org.getId())
            .organizationName(org == null ? null : org.getName())
            .status(snapshot.getLastStatus() == null
                ? IntegrationHealthStatus.NO_HISTORY
                : snapshot.getLastStatus())
            .lastSuccessAt(snapshot.getLastSuccessAt())
            .lastFailureAt(snapshot.getLastFailureAt())
            .lastErrorMessage(snapshot.getLastErrorMessage())
            .successCount24h(snapshot.getSuccessCount24h() == null ? 0 : snapshot.getSuccessCount24h())
            .failureCount24h(snapshot.getFailureCount24h() == null ? 0 : snapshot.getFailureCount24h())
            .updatedAt(snapshot.getUpdatedAt())
            .build();
    }
}
