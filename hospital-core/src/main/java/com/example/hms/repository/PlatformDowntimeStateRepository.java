package com.example.hms.repository;

import com.example.hms.model.platform.PlatformDowntimeState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PlatformDowntimeStateRepository extends JpaRepository<PlatformDowntimeState, Integer> {
}
