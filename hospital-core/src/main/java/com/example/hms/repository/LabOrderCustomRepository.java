package com.example.hms.repository;

import com.example.hms.model.LabOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.UUID;

public interface LabOrderCustomRepository {
    Page<LabOrder> search(UUID patientId, LocalDateTime from, LocalDateTime to, Pageable pageable);

}
