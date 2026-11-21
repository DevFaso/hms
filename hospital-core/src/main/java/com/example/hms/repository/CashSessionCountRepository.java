package com.example.hms.repository;

import com.example.hms.model.CashSessionCount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Billing-money plan PR 10 (V309): the counts of each close round of a session. */
public interface CashSessionCountRepository extends JpaRepository<CashSessionCount, UUID> {

    List<CashSessionCount> findBySessionIdOrderByCloseRoundAscMethodAsc(UUID sessionId);

    List<CashSessionCount> findBySessionIdIn(Collection<UUID> sessionIds);
}
