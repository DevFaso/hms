package com.example.hms.service;

import com.example.hms.exception.BusinessRuleException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.LabInventoryItemMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabInventoryItem;
import com.example.hms.payload.dto.LabInventoryItemRequestDTO;
import com.example.hms.payload.dto.LabInventoryItemResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.LabInventoryItemRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.support.HospitalScopeUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LabInventoryServiceImpl implements LabInventoryService {

    private static final String INVENTORY_NOT_FOUND_KEY = "inventory.notFound";

    private final LabInventoryItemRepository inventoryRepository;
    private final HospitalRepository hospitalRepository;
    private final LabInventoryItemMapper mapper;
    private final MessageSource messageSource;

    @Override
    public Page<LabInventoryItemResponseDTO> getByHospital(UUID hospitalId, Pageable pageable, Locale locale) {
        requireHospitalScope(hospitalId, locale);
        return inventoryRepository.findByHospitalIdAndActiveTrue(hospitalId, pageable)
            .map(mapper::toDto);
    }

    @Override
    public LabInventoryItemResponseDTO getById(UUID id, Locale locale) {
        LabInventoryItem item = inventoryRepository.findByIdAndActiveTrue(id)
            .orElseThrow(() -> new ResourceNotFoundException(
                getLocalizedMessage(INVENTORY_NOT_FOUND_KEY, new Object[]{id}, locale)));
        requireHospitalScope(item.getHospital().getId(), locale);
        return mapper.toDto(item);
    }

    @Override
    @Transactional
    public LabInventoryItemResponseDTO create(UUID hospitalId, LabInventoryItemRequestDTO dto, Locale locale) {
        requireHospitalScope(hospitalId, locale);

        Hospital hospital = hospitalRepository.findById(hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException(
                getLocalizedMessage("hospital.notfound", new Object[]{hospitalId}, locale)));

        if (inventoryRepository.existsByHospitalIdAndItemCode(hospitalId, dto.getItemCode())) {
            throw new BusinessRuleException(
                getLocalizedMessage("inventory.duplicate.code", new Object[]{dto.getItemCode()}, locale));
        }

        if (dto.getQuantity() != null && dto.getQuantity() < 0) {
            throw new BusinessRuleException(
                getLocalizedMessage("inventory.quantity.negative", null, locale));
        }

        LabInventoryItem item = mapper.toEntity(dto, hospital);
        item = inventoryRepository.save(item);
        return mapper.toDto(item);
    }

    @Override
    @Transactional
    public LabInventoryItemResponseDTO update(UUID id, LabInventoryItemRequestDTO dto, Locale locale) {
        LabInventoryItem item = inventoryRepository.findByIdAndActiveTrue(id)
            .orElseThrow(() -> new ResourceNotFoundException(
                getLocalizedMessage(INVENTORY_NOT_FOUND_KEY, new Object[]{id}, locale)));
        requireHospitalScope(item.getHospital().getId(), locale);

        // Check item code uniqueness if changed
        if (dto.getItemCode() != null
                && !dto.getItemCode().equals(item.getItemCode())
                && inventoryRepository.existsByHospitalIdAndItemCode(
                    item.getHospital().getId(), dto.getItemCode())) {
            throw new BusinessRuleException(
                getLocalizedMessage("inventory.duplicate.code", new Object[]{dto.getItemCode()}, locale));
        }

        if (dto.getQuantity() != null && dto.getQuantity() < 0) {
            throw new BusinessRuleException(
                getLocalizedMessage("inventory.quantity.negative", null, locale));
        }

        mapper.updateEntity(item, dto);
        item = inventoryRepository.save(item);
        return mapper.toDto(item);
    }

    @Override
    @Transactional
    public void deactivate(UUID id, Locale locale) {
        LabInventoryItem item = inventoryRepository.findByIdAndActiveTrue(id)
            .orElseThrow(() -> new ResourceNotFoundException(
                getLocalizedMessage(INVENTORY_NOT_FOUND_KEY, new Object[]{id}, locale)));
        requireHospitalScope(item.getHospital().getId(), locale);
        item.setActive(false);
        inventoryRepository.save(item);
    }

    @Override
    public List<LabInventoryItemResponseDTO> getLowStockItems(UUID hospitalId, Locale locale) {
        requireHospitalScope(hospitalId, locale);
        return inventoryRepository.findLowStockItems(hospitalId).stream()
            .map(mapper::toDto)
            .toList();
    }

    // ── helpers ───────────────────────────────────────────────────

    private boolean hasHospitalAccess(UUID hospitalId) {
        if (hospitalId == null) return false;
        HospitalContext context = HospitalContextHolder.getContextOrEmpty();
        if (context.isSuperAdmin()) return true;
        return HospitalScopeUtils.resolveScope(context).contains(hospitalId);
    }

    private void requireHospitalScope(UUID hospitalId, Locale locale) {
        if (!hasHospitalAccess(hospitalId)) {
            throw new AccessDeniedException(
                messageSource.getMessage("access.denied", null, "Access denied", locale));
        }
    }

    private String getLocalizedMessage(String key, Object[] args, Locale locale) {
        return messageSource.getMessage(key, args, key, locale != null ? locale : LocaleContextHolder.getLocale());
    }
}
