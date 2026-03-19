package com.example.hms.repository;

import com.example.hms.enums.InstrumentOutboxStatus;
import com.example.hms.model.InstrumentOutbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface InstrumentOutboxRepository extends JpaRepository<InstrumentOutbox, UUID> {

    List<InstrumentOutbox> findByStatus(InstrumentOutboxStatus status);

    List<InstrumentOutbox> findByLabOrder_Id(UUID labOrderId);
}
