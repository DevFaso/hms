package com.example.hms.repository.scheduling;

import com.example.hms.model.scheduling.SessionTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SessionTemplateRepository extends JpaRepository<SessionTemplate, UUID> {

    List<SessionTemplate> findByHospital_IdAndActiveTrue(UUID hospitalId);

    List<SessionTemplate> findByStaff_IdAndActiveTrue(UUID staffId);
}
