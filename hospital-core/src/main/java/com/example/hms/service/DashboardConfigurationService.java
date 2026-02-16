package com.example.hms.service;

import com.example.hms.payload.dto.dashboard.DashboardConfigResponseDTO;

public interface DashboardConfigurationService {

    DashboardConfigResponseDTO getDashboardForCurrentUser();
}
