package com.example.hms.repository.pharmacy;

import com.example.hms.model.pharmacy.SaleLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface SaleLineRepository extends JpaRepository<SaleLine, UUID> {
}
