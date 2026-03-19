package com.example.hms.repository;

import com.example.hms.model.Ward;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface WardRepository extends JpaRepository<Ward, UUID> {

    List<Ward> findByHospital_Id(UUID hospitalId);

    List<Ward> findByHospital_IdAndActiveTrue(UUID hospitalId);
}
