package com.example.hms.repository.pharmacy;

import com.example.hms.model.pharmacy.MtmReview;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface MtmReviewRepository extends JpaRepository<MtmReview, UUID> {

    Page<MtmReview> findByHospital_Id(UUID hospitalId, Pageable pageable);

    Page<MtmReview> findByPatient_IdAndHospital_Id(UUID patientId, UUID hospitalId, Pageable pageable);
}
