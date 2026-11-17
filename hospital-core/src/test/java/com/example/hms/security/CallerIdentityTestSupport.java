package com.example.hms.security;

import com.example.hms.model.Hospital;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

/** Unit-test support: make a staff record the caller, as {@link CallerIdentity} reads it. */
public final class CallerIdentityTestSupport {

    private CallerIdentityTestSupport() {
    }

    /** Sign in as {@code staff}'s user (created when missing), with {@code staff} placed at {@code hospital} when it has none. */
    public static void actAs(Staff staff, Hospital hospital) {
        if (staff.getUser() == null) {
            User user = new User();
            user.setId(UUID.randomUUID());
            staff.setUser(user);
        } else if (staff.getUser().getId() == null) {
            staff.getUser().setId(UUID.randomUUID());
        }
        if (staff.getHospital() == null && hospital != null) {
            staff.setHospital(hospital);
        }
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(staff.getUser(), null, List.of()));
    }

    public static void clear() {
        SecurityContextHolder.clearContext();
    }
}
