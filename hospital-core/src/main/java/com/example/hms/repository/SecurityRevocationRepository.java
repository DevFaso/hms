package com.example.hms.repository;

import com.example.hms.model.SecurityRevocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SecurityRevocationRepository extends JpaRepository<SecurityRevocation, Integer> {
}
