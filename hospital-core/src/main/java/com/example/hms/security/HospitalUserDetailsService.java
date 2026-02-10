package com.example.hms.security;

import org.springframework.security.core.userdetails.UserDetailsService;

/**
 * Specialization of {@link UserDetailsService} that guarantees {@link HospitalUserDetails} instances.
 */
public interface HospitalUserDetailsService extends UserDetailsService {

    @Override
    HospitalUserDetails loadUserByUsername(String username);
}
