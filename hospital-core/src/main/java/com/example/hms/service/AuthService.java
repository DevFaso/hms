package com.example.hms.service;

import java.util.UUID;

public interface AuthService {
    UUID getCurrentUserId();

    String getCurrentUserToken();

    boolean hasRole(String role);
}

