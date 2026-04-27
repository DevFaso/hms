package com.example.hms.service.pharmacy;

import com.example.hms.enums.PharmacySaleStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.pharmacy.PharmacySaleMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.model.pharmacy.PharmacySale;
import com.example.hms.model.pharmacy.SaleLine;
import com.example.hms.model.pharmacy.StockLot;
import com.example.hms.payload.dto.pharmacy.PharmacySaleRequestDTO;
import com.example.hms.payload.dto.pharmacy.PharmacySaleResponseDTO;
import com.example.hms.payload.dto.pharmacy.SaleLineRequestDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.MedicationCatalogItemRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.pharmacy.PharmacyRepository;
import com.example.hms.repository.pharmacy.PharmacySaleRepository;
import com.example.hms.repository.pharmacy.StockLotRepository;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * P-07: OTC walk-in pharmacy sales.
 *
 * <p>Tenant isolation is enforced at every entry point via
 * {@link RoleValidator#requireActiveHospitalId()}; reads filter by the active
 * hospital so a caller cannot enumerate sales from another hospital. The
 * authenticated user is captured as {@code soldByUser} — never trusted from
 * the request body.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class PharmacySaleServiceImpl implements PharmacySaleService {

    private final PharmacySaleRepository saleRepository;
    private final PharmacyRepository pharmacyRepository;
    private final HospitalRepository hospitalRepository;
    private final PatientRepository patientRepository;
    private final UserRepository userRepository;
    private final MedicationCatalogItemRepository catalogItemRepository;
    private final StockLotRepository stockLotRepository;
    private final PharmacySaleMapper mapper;
    private final RoleValidator roleValidator;

    @Override
    public PharmacySaleResponseDTO createSale(PharmacySaleRequestDTO dto) {
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        if (!activeHospitalId.equals(dto.getHospitalId())) {
            throw new BusinessException("Sale hospital does not match the active hospital context");
        }
        if (dto.getLines() == null || dto.getLines().isEmpty()) {
            throw new BusinessException("Sale must include at least one line item");
        }

        Hospital hospital = hospitalRepository.findById(dto.getHospitalId())
                .orElseThrow(() -> new ResourceNotFoundException("hospital.notfound"));

        Pharmacy pharmacy = pharmacyRepository.findByIdAndHospital_Id(dto.getPharmacyId(), activeHospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("pharmacy.notfound"));

        Patient patient = null;
        if (dto.getPatientId() != null) {
            patient = patientRepository.findById(dto.getPatientId())
                    .orElseThrow(() -> new ResourceNotFoundException("patient.notfound", dto.getPatientId()));
        }

        User soldBy = resolveCurrentUser();

        PharmacySale sale = PharmacySale.builder()
                .pharmacy(pharmacy)
                .hospital(hospital)
                .patient(patient)
                .soldByUser(soldBy)
                .saleDate(LocalDateTime.now())
                .paymentMethod(dto.getPaymentMethod())
                .currency(dto.getCurrency() != null ? dto.getCurrency() : "XOF")
                .referenceNumber(dto.getReferenceNumber())
                .status(PharmacySaleStatus.COMPLETED)
                .notes(dto.getNotes())
                .totalAmount(BigDecimal.ZERO)
                .build();

        BigDecimal runningTotal = BigDecimal.ZERO;
        for (SaleLineRequestDTO lineDto : dto.getLines()) {
            SaleLine line = buildLine(lineDto, pharmacy);
            sale.addLine(line);
            runningTotal = runningTotal.add(line.getLineTotal());
        }
        sale.setTotalAmount(runningTotal.setScale(2, RoundingMode.HALF_UP));

        PharmacySale saved = saleRepository.save(sale);
        log.info("Recorded pharmacy sale {} ({} lines, total {})",
                saved.getId(), saved.getLines().size(), saved.getTotalAmount());
        return mapper.toResponseDTO(saved);
    }

    private SaleLine buildLine(SaleLineRequestDTO lineDto, Pharmacy pharmacy) {
        if (lineDto.getQuantity() == null || lineDto.getQuantity().signum() <= 0) {
            throw new BusinessException("Sale line quantity must be greater than zero");
        }
        if (lineDto.getUnitPrice() == null || lineDto.getUnitPrice().signum() < 0) {
            throw new BusinessException("Sale line unit price cannot be negative");
        }

        MedicationCatalogItem item = catalogItemRepository.findById(lineDto.getMedicationCatalogItemId())
                .orElseThrow(() -> new ResourceNotFoundException("medication.catalog.notfound"));

        StockLot lot = null;
        if (lineDto.getStockLotId() != null) {
            lot = stockLotRepository.findById(lineDto.getStockLotId())
                    .orElseThrow(() -> new ResourceNotFoundException("stocklot.notfound"));
            // Lot must belong to the selling pharmacy — prevents cross-pharmacy traceability bugs.
            if (lot.getInventoryItem() == null
                    || lot.getInventoryItem().getPharmacy() == null
                    || !pharmacy.getId().equals(lot.getInventoryItem().getPharmacy().getId())) {
                throw new BusinessException("Stock lot does not belong to the selling pharmacy");
            }
        }

        BigDecimal lineTotal = lineDto.getQuantity()
                .multiply(lineDto.getUnitPrice())
                .setScale(2, RoundingMode.HALF_UP);

        return SaleLine.builder()
                .medicationCatalogItem(item)
                .stockLot(lot)
                .quantity(lineDto.getQuantity())
                .unitPrice(lineDto.getUnitPrice())
                .lineTotal(lineTotal)
                .notes(lineDto.getNotes())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public PharmacySaleResponseDTO getSale(UUID id) {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        PharmacySale sale = saleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("pharmacy.sale.notfound"));
        if (sale.getHospital() == null || !hospitalId.equals(sale.getHospital().getId())) {
            // Hide cross-tenant existence by returning not-found rather than forbidden.
            throw new ResourceNotFoundException("pharmacy.sale.notfound");
        }
        return mapper.toResponseDTO(sale);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PharmacySaleResponseDTO> listByHospital(UUID hospitalId, Pageable pageable) {
        UUID active = roleValidator.requireActiveHospitalId();
        if (!active.equals(hospitalId)) {
            throw new BusinessException("Hospital ID does not match the active hospital context");
        }
        return saleRepository.findByHospital_Id(active, pageable).map(mapper::toResponseDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PharmacySaleResponseDTO> listByPharmacy(UUID pharmacyId, Pageable pageable) {
        UUID active = roleValidator.requireActiveHospitalId();
        return saleRepository.findByPharmacy_IdAndHospital_Id(pharmacyId, active, pageable)
                .map(mapper::toResponseDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PharmacySaleResponseDTO> listByPatient(UUID patientId, Pageable pageable) {
        UUID active = roleValidator.requireActiveHospitalId();
        return saleRepository.findByPatient_IdAndHospital_Id(patientId, active, pageable)
                .map(mapper::toResponseDTO);
    }

    private User resolveCurrentUser() {
        UUID userId = roleValidator.getCurrentUserId();
        if (userId == null) {
            throw new BusinessException("Unable to determine current user");
        }
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("user.current.notfound"));
    }
}
