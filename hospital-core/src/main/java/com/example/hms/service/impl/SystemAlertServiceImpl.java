package com.example.hms.service.impl;

import com.example.hms.model.platform.SystemAlert;
import com.example.hms.payload.dto.monitoring.SystemAlertDTO;
import com.example.hms.repository.platform.SystemAlertRepository;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.service.SystemAlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SystemAlertServiceImpl implements SystemAlertService {

    private final SystemAlertRepository repository;

    @Override
    public Page<SystemAlertDTO> listAlerts(String severity, Pageable pageable) {
        Page<SystemAlert> page = StringUtils.hasText(severity)
            ? repository.findBySeverityIgnoreCaseOrderByCreatedAtDesc(severity, pageable)
            : repository.findAllByOrderByCreatedAtDesc(pageable);
        return page.map(this::toDto);
    }

    @Override
    @Transactional
    public SystemAlertDTO createAlert(String alertType, String severity, String title,
                                       String description, String source) {
        SystemAlert alert = SystemAlert.builder()
            .alertType(alertType)
            .severity(severity)
            .title(title)
            .description(description)
            .source(source)
            .build();
        repository.save(alert);
        log.info("System alert created type={} severity={} source={}", alertType, severity, source);
        return toDto(alert);
    }

    @Override
    @Transactional
    public SystemAlertDTO acknowledgeAlert(UUID alertId, String acknowledgedBy) {
        SystemAlert alert = repository.findById(alertId)
            .orElseThrow(() -> new ResourceNotFoundException("SystemAlert", "id", alertId));
        alert.setAcknowledged(true);
        alert.setAcknowledgedBy(acknowledgedBy);
        alert.setAcknowledgedAt(LocalDateTime.now());
        repository.save(alert);
        log.info("System alert acknowledged id={} by={}", alertId, acknowledgedBy);
        return toDto(alert);
    }

    @Override
    @Transactional
    public SystemAlertDTO resolveAlert(UUID alertId) {
        SystemAlert alert = repository.findById(alertId)
            .orElseThrow(() -> new ResourceNotFoundException("SystemAlert", "id", alertId));
        alert.setResolved(true);
        alert.setResolvedAt(LocalDateTime.now());
        repository.save(alert);
        log.info("System alert resolved id={}", alertId);
        return toDto(alert);
    }

    @Override
    public Map<String, Long> getAlertSummary() {
        Map<String, Long> summary = new LinkedHashMap<>();
        summary.put("total", repository.count());
        summary.put("unacknowledged", repository.countByAcknowledgedFalse());
        summary.put("critical", repository.countBySeverityIgnoreCase("CRITICAL"));
        summary.put("high", repository.countBySeverityIgnoreCase("HIGH"));
        summary.put("medium", repository.countBySeverityIgnoreCase("MEDIUM"));
        summary.put("low", repository.countBySeverityIgnoreCase("LOW"));
        summary.put("last24h", repository.countByCreatedAtAfter(LocalDateTime.now().minusHours(24)));
        return summary;
    }

    private SystemAlertDTO toDto(SystemAlert entity) {
        return SystemAlertDTO.builder()
            .id(entity.getId())
            .alertType(entity.getAlertType())
            .severity(entity.getSeverity())
            .title(entity.getTitle())
            .description(entity.getDescription())
            .source(entity.getSource())
            .acknowledged(entity.isAcknowledged())
            .acknowledgedBy(entity.getAcknowledgedBy())
            .acknowledgedAt(entity.getAcknowledgedAt())
            .resolved(entity.isResolved())
            .resolvedAt(entity.getResolvedAt())
            .createdAt(entity.getCreatedAt())
            .updatedAt(entity.getUpdatedAt())
            .build();
    }
}
