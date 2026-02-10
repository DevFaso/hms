package com.example.hms.patient.controller;

import com.example.hms.patient.dto.PatientRequest;
import com.example.hms.patient.dto.PatientResponse;
import com.example.hms.patient.service.PatientService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/patients")
@Validated
public class PatientController {
    private final PatientService patientService;

    public PatientController(PatientService patientService) {
        this.patientService = patientService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PatientResponse create(@Valid @RequestBody PatientRequest request) {
        return patientService.create(request);
    }

    @GetMapping("/{id}")
    public PatientResponse get(@PathVariable UUID id) {
        return patientService.getById(id);
    }

    @GetMapping
    public List<PatientResponse> search(@RequestParam(name = "q", required = false) String query) {
        return patientService.search(query);
    }

    @PutMapping("/{id}")
    public PatientResponse update(@PathVariable UUID id, @Valid @RequestBody PatientRequest request) {
        return patientService.update(id, request);
    }
}
