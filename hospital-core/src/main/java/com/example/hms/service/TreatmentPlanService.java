package com.example.hms.service;

import com.example.hms.enums.TreatmentPlanStatus;
import com.example.hms.payload.dto.clinical.treatment.TreatmentPlanFollowUpDTO;
import com.example.hms.payload.dto.clinical.treatment.TreatmentPlanFollowUpRequestDTO;
import com.example.hms.payload.dto.clinical.treatment.TreatmentPlanRequestDTO;
import com.example.hms.payload.dto.clinical.treatment.TreatmentPlanResponseDTO;
import com.example.hms.payload.dto.clinical.treatment.TreatmentPlanReviewDTO;
import com.example.hms.payload.dto.clinical.treatment.TreatmentPlanReviewRequestDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface TreatmentPlanService {

    TreatmentPlanResponseDTO create(TreatmentPlanRequestDTO requestDTO);

    TreatmentPlanResponseDTO update(UUID id, TreatmentPlanRequestDTO requestDTO);

    TreatmentPlanResponseDTO getById(UUID id);

    Page<TreatmentPlanResponseDTO> listByPatient(UUID patientId, Pageable pageable);

    Page<TreatmentPlanResponseDTO> listByHospital(UUID hospitalId, TreatmentPlanStatus status, Pageable pageable);

    Page<TreatmentPlanResponseDTO> listAll(TreatmentPlanStatus status, Pageable pageable);

    TreatmentPlanFollowUpDTO addFollowUp(UUID planId, TreatmentPlanFollowUpRequestDTO requestDTO);

    TreatmentPlanFollowUpDTO updateFollowUp(UUID planId, UUID followUpId, TreatmentPlanFollowUpRequestDTO requestDTO);

    TreatmentPlanReviewDTO addReview(UUID planId, TreatmentPlanReviewRequestDTO requestDTO);
}
