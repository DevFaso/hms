package com.example.hms.repository.scheduling;

import com.example.hms.model.scheduling.SessionTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SessionTemplateRepository extends JpaRepository<SessionTemplate, UUID> {

    List<SessionTemplate> findByHospital_IdAndActiveTrue(UUID hospitalId);

    /** QA r65 D2731 (onboarding checklist): how many active rota templates one hospital has. */
    long countByHospital_IdAndActiveTrue(UUID hospitalId);

    List<SessionTemplate> findByStaff_IdAndActiveTrue(UUID staffId);

    /** Admin list including retired rows, in rota order. */
    List<SessionTemplate> findByHospital_IdOrderByDayOfWeekAscStartTimeAsc(UUID hospitalId);
}
