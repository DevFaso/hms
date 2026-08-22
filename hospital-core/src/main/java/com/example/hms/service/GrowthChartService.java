package com.example.hms.service;

import com.example.hms.payload.dto.GrowthChartDTO;

import java.util.UUID;

public interface GrowthChartService {

    /**
     * Assemble the anthropometric series (weight / height / head circumference
     * over age) for one patient, seeded with the delivery-record birth weight
     * where a single-infant delivery is linked.
     *
     * @param hospitalId resolved caller scope; null means an unscoped
     *                   (super-admin) read across hospitals
     */
    GrowthChartDTO getGrowthChart(UUID patientId, UUID hospitalId);
}
