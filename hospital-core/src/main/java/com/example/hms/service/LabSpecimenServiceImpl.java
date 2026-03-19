package com.example.hms.service;

import com.example.hms.enums.LabSpecimenStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.LabSpecimenMapper;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabSpecimen;
import com.example.hms.payload.dto.LabSpecimenRequestDTO;
import com.example.hms.payload.dto.LabSpecimenResponseDTO;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.LabSpecimenRepository;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LabSpecimenServiceImpl implements LabSpecimenService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final java.security.SecureRandom SECURE_RNG = new java.security.SecureRandom();

    private final LabSpecimenRepository labSpecimenRepository;
    private final LabOrderRepository labOrderRepository;
    private final LabSpecimenMapper labSpecimenMapper;
    private final RoleValidator roleValidator;
    private final InstrumentOutboxService instrumentOutboxService;

    @Override
    @Transactional
    public LabSpecimenResponseDTO createSpecimen(LabSpecimenRequestDTO request, Locale locale) {
        if (request.getLabOrderId() == null) {
            throw new BusinessException("labOrderId is required when creating a specimen.");
        }
        LabOrder labOrder = labOrderRepository.findById(request.getLabOrderId())
            .orElseThrow(() -> new ResourceNotFoundException("laborder.notfound"));

        // Hospital scope check
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId != null && labOrder.getHospital() != null
                && !labOrder.getHospital().getId().equals(hospitalId)) {
            throw new ResourceNotFoundException("laborder.notfound");
        }

        String accessionNumber = generateAccessionNumber();
        String barcodeValue = "LAB-" + accessionNumber;

        LabSpecimen specimen = LabSpecimen.builder()
            .labOrder(labOrder)
            .accessionNumber(accessionNumber)
            .barcodeValue(barcodeValue)
            .specimenType(request.getSpecimenType())
            .currentLocation(request.getCurrentLocation())
            .collectedAt(request.getCollectedAt() != null ? request.getCollectedAt() : LocalDateTime.now())
            .collectedById(roleValidator.getCurrentUserId())
            .status(LabSpecimenStatus.COLLECTED)
            .notes(request.getNotes())
            .build();

        return labSpecimenMapper.toResponseDTO(labSpecimenRepository.save(specimen));
    }

    @Override
    @Transactional(readOnly = true)
    public LabSpecimenResponseDTO getSpecimenById(UUID specimenId, Locale locale) {
        LabSpecimen specimen = labSpecimenRepository.findById(specimenId)
            .orElseThrow(() -> new ResourceNotFoundException("labspecimen.notfound"));

        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId != null && specimen.getLabOrder().getHospital() != null
                && !specimen.getLabOrder().getHospital().getId().equals(hospitalId)) {
            throw new ResourceNotFoundException("labspecimen.notfound");
        }
        return labSpecimenMapper.toResponseDTO(specimen);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LabSpecimenResponseDTO> getSpecimensByLabOrder(UUID labOrderId, Locale locale) {
        LabOrder labOrder = labOrderRepository.findById(labOrderId)
            .orElseThrow(() -> new ResourceNotFoundException("laborder.notfound"));

        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId != null && labOrder.getHospital() != null
                && !labOrder.getHospital().getId().equals(hospitalId)) {
            throw new ResourceNotFoundException("laborder.notfound");
        }
        return labSpecimenRepository.findByLabOrder_Id(labOrderId)
            .stream()
            .map(labSpecimenMapper::toResponseDTO)
            .toList();
    }

    @Override
    @Transactional
    public LabSpecimenResponseDTO receiveSpecimen(UUID specimenId, Locale locale) {
        LabSpecimen specimen = labSpecimenRepository.findById(specimenId)
            .orElseThrow(() -> new ResourceNotFoundException("labspecimen.notfound"));

        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId != null && specimen.getLabOrder().getHospital() != null
                && !specimen.getLabOrder().getHospital().getId().equals(hospitalId)) {
            throw new ResourceNotFoundException("labspecimen.notfound");
        }

        if (specimen.getStatus() != LabSpecimenStatus.COLLECTED
                && specimen.getStatus() != LabSpecimenStatus.IN_TRANSIT) {
            throw new BusinessException(
                "Specimen cannot be received from status: " + specimen.getStatus().name());
        }

        specimen.setReceivedAt(LocalDateTime.now());
        specimen.setReceivedById(roleValidator.getCurrentUserId());
        specimen.setStatus(LabSpecimenStatus.RECEIVED);
        LabSpecimen saved = labSpecimenRepository.save(specimen);
        instrumentOutboxService.enqueueSpecimenReceived(saved);
        return labSpecimenMapper.toResponseDTO(saved);
    }

    // ── Accession number generation ───────────────────────────────────────────
    private String generateAccessionNumber() {
        String datePart = LocalDateTime.now().format(DATE_FMT);
        for (int attempt = 0; attempt < 10; attempt++) {
            // 5 random uppercase alphanumeric characters
            String suffix = generateRandomSuffix(5);
            String accession = "ACC-" + datePart + "-" + suffix;
            if (!labSpecimenRepository.existsByAccessionNumber(accession)) {
                return accession;
            }
        }
        throw new BusinessException("Unable to generate a unique accession number; please retry.");
    }

    private static String generateRandomSuffix(int length) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        var sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(SECURE_RNG.nextInt(chars.length())));
        }
        return sb.toString();
    }
}
