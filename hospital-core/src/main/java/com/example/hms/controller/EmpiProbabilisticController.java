package com.example.hms.controller;

import com.example.hms.empi.probabilistic.EmpiCandidateMatchDTO;
import com.example.hms.empi.probabilistic.EmpiCandidateQueryDTO;
import com.example.hms.empi.probabilistic.EmpiProbabilisticMatcher;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Receptionist-facing endpoint for the probabilistic candidate-match
 * workflow (roadmap row 25, v1.1 / Patient Identity).
 *
 * <p>Gated by {@code app.empi.probabilistic.enabled}. Flag off ⇒ 404
 * so the endpoint shape stays hidden until the scorer ships in the
 * row-25 follow-on. Authentication enforced ahead of the flag check;
 * the role allowlist mirrors the registration desk roles that already
 * resolve identity in the deterministic path.
 */
@RestController
@RequestMapping("/empi")
public class EmpiProbabilisticController {

    private final EmpiProbabilisticMatcher matcher;

    public EmpiProbabilisticController(EmpiProbabilisticMatcher matcher) {
        this.matcher = matcher;
    }

    @PostMapping("/candidates")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','HOSPITAL_ADMIN','RECEPTIONIST','NURSE','DOCTOR')")
    public ResponseEntity<List<EmpiCandidateMatchDTO>> candidates(
        @RequestBody EmpiCandidateQueryDTO query
    ) {
        if (!matcher.isEnabled()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(matcher.findCandidates(query));
    }
}
