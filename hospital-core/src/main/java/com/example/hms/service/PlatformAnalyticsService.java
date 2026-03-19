package com.example.hms.service;

import com.example.hms.payload.dto.analytics.PlatformAnalyticsDTO;

public interface PlatformAnalyticsService {
    PlatformAnalyticsDTO getAnalytics(int trendDays);
}
