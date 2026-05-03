package com.example.hms.service;

import com.example.hms.payload.dto.superadmin.EmergencyActionResponseDTO;
import com.example.hms.payload.dto.superadmin.EmergencyBroadcastRequestDTO;
import com.example.hms.payload.dto.superadmin.EmergencyForceLogoutRequestDTO;
import com.example.hms.payload.dto.superadmin.EmergencyForceMfaRequestDTO;
import com.example.hms.payload.dto.superadmin.EmergencyKillFeatureRequestDTO;

/**
 * MVP-7: Cross-platform emergency controls available to super admins.
 * Every operation emits a {@code SECURITY_ALERT_TRIGGERED} audit event so
 * the action is permanently traceable to the human who took it.
 */
public interface EmergencyControlService {

    EmergencyActionResponseDTO forceLogoutAll(EmergencyForceLogoutRequestDTO request);

    EmergencyActionResponseDTO killFeature(EmergencyKillFeatureRequestDTO request);

    EmergencyActionResponseDTO forceMfaReenrol(EmergencyForceMfaRequestDTO request);

    EmergencyActionResponseDTO broadcast(EmergencyBroadcastRequestDTO request);
}
