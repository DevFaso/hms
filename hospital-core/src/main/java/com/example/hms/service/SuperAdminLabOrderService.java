package com.example.hms.service;

import com.example.hms.payload.dto.LabOrderResponseDTO;
import com.example.hms.payload.dto.superadmin.SuperAdminLabOrderCreateRequestDTO;

import java.util.Locale;

public interface SuperAdminLabOrderService {

    LabOrderResponseDTO createLabOrder(SuperAdminLabOrderCreateRequestDTO request, Locale locale);
}
