package com.example.hms.service;

import com.example.hms.payload.dto.monitoring.SystemAlertDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Map;
import java.util.UUID;

public interface SystemAlertService {

    Page<SystemAlertDTO> listAlerts(String severity, Pageable pageable);

    SystemAlertDTO createAlert(String alertType, String severity, String title,
                                String description, String source);

    SystemAlertDTO acknowledgeAlert(UUID alertId, String acknowledgedBy);

    SystemAlertDTO resolveAlert(UUID alertId);

    Map<String, Long> getAlertSummary();
}
