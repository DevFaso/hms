package com.example.hms.service.pharmacy;

import com.example.hms.enums.StockTransactionType;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.payload.dto.pharmacy.PharmacyConsumptionDTO;
import com.example.hms.repository.MedicationCatalogItemRepository;
import com.example.hms.repository.pharmacy.PharmacyRepository;
import com.example.hms.repository.pharmacy.StockTransactionRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PharmacyConsumptionService {
    private final StockCountService access;
    private final PharmacyRepository pharmacies;
    private final StockTransactionRepository movements;
    private final MedicationCatalogItemRepository catalogue;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PharmacyConsumptionDTO report(UUID pharmacyId, LocalDate from, LocalDate to) {
        UUID hospitalId = access.readerHospital();
        pharmacies.findByIdAndHospital_Id(pharmacyId, hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException("pharmacy.notFound", pharmacyId));
        LocalDate last = to == null ? LocalDate.now(clock) : to;
        LocalDate first;
        List<Object[]> rows;
        try {
            first = from == null ? last.minusDays(29) : from;
            long days = ChronoUnit.DAYS.between(first, last);
            if (days < 0 || days > 365) throw new FieldBusinessException("from", "stockReport.periodInvalid");
            rows = movements.consumptionByRecordedUnit(pharmacyId, first.atStartOfDay(), last.plusDays(1).atStartOfDay(),
                Set.of(StockTransactionType.DISPENSE, StockTransactionType.SALE),
                StockTransactionType.RETURN, StockTransactionType.TRANSFER);
        } catch (DateTimeException invalid) {
            FieldBusinessException refusal = new FieldBusinessException("to", "stockReport.periodInvalid");
            refusal.initCause(invalid);
            throw refusal;
        }
        Set<UUID> medicationIds = rows.stream().map(row -> (UUID) row[1]).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, MedicationCatalogItem> names = catalogue.findAllById(medicationIds).stream()
            .collect(Collectors.toMap(MedicationCatalogItem::getId, Function.identity()));
        List<PharmacyConsumptionDTO.StockConsumptionLine> lines = rows.stream()
            .filter(row -> decimal(row[3]).signum() != 0 || decimal(row[4]).signum() != 0)
            .map(row -> {
                String unit = (String) row[2];
                boolean comparable = unit != null && !unit.isBlank();
                MedicationCatalogItem medicine = names.get((UUID) row[1]);
                // QA D4192 (r81): a movement older than the unit snapshot (V398) keeps its quantities; only its
                // unit is unknown (null, never guessed from today's unit), and the line says so. The line was blank.
                return new PharmacyConsumptionDTO.StockConsumptionLine((UUID) row[0], StockProductLabel.nameOf(medicine),
                    StockProductLabel.strengthOf(medicine), StockProductLabel.formOf(medicine), comparable ? unit : null,
                    decimal(row[3]), decimal(row[4]), comparable, ((Number) row[5]).longValue());
            })
            .sorted(Comparator.comparing(PharmacyConsumptionDTO.StockConsumptionLine::medicationName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
            .toList();
        return new PharmacyConsumptionDTO(first, last, lines);
    }

    private static BigDecimal decimal(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
    }
}
