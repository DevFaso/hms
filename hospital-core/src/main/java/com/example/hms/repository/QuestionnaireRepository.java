package com.example.hms.repository;

import com.example.hms.model.Questionnaire;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface QuestionnaireRepository extends JpaRepository<Questionnaire, UUID> {

    List<Questionnaire> findByHospital_IdAndActiveTrue(UUID hospitalId);

    List<Questionnaire> findByHospital_IdAndDepartment_IdAndActiveTrue(UUID hospitalId, UUID departmentId);
}
