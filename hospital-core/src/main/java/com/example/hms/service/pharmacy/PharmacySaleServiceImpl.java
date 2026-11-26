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
import com.example.hms.repository.pharmacy.InventoryItemRepository;
import com.example.hms.repository.pharmacy.StockLotRepository;
import com.example.hms.repository.pharmacy.StockTransactionRepository;
import com.example.hms.enums.StockTransactionType;
import com.example.hms.model.pharmacy.InventoryItem;
import com.example.hms.model.pharmacy.StockTransaction;
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

    /** SaleLine.notes column length; the line's own note and a FEFO reason share it. */
    static final int LINE_NOTES_MAX = 500;
    /** The marker of a FEFO override in a line's notes, as on a fill. */
    static final String FEFO_NOTE_PREFIX = "FEFO: ";
    private static final String FIELD_ITEM = "medicationCatalogItemId";
    private static final String FIELD_UNIT_PRICE = "unitPrice";

    private final PharmacySaleRepository saleRepository;
    private final PharmacyRepository pharmacyRepository;
    private final HospitalRepository hospitalRepository;
    private final PatientRepository patientRepository;
    private final UserRepository userRepository;
    private final MedicationCatalogItemRepository catalogItemRepository;
    private final StockLotRepository stockLotRepository;
    private final PharmacySaleMapper mapper;
    private final RoleValidator roleValidator;
    /** QA D964: a counter sale from a lot follows first expired, first out too. */
    private final FefoLotPolicy fefoLotPolicy;
    /** QA D1129: a non-cash sale's reference is recorded once across every till. */
    private final com.example.hms.service.payment.PaymentReferenceRegistry referenceRegistry;
    /** QA D2671 (r65): the list price of every line; QA D2670: the stock the sale takes. */
    private final InventoryItemRepository inventoryItemRepository;
    /** QA D2670 (r65): the SALE movement each line writes. */
    private final StockTransactionRepository stockTransactionRepository;

    @Override
    public PharmacySaleResponseDTO createSale(PharmacySaleRequestDTO dto) {
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        if (!activeHospitalId.equals(dto.getHospitalId())) {
            throw new com.example.hms.exception.FieldBusinessException("hospitalId", "pharmacy.sale.hospitalMismatch");
        }
        if (dto.getLines() == null || dto.getLines().isEmpty()) {
            throw new com.example.hms.exception.FieldBusinessException("lines", "pharmacy.sale.linesRequired");
        }
        // QA D1611: a counter sale is paid at the till; an insurer's share is never counted as paid.
        com.example.hms.service.payment.TillPaymentMethods.requireReceivedAtTill(dto.getPaymentMethod(), "paymentMethod");
        // Billing plan PR 9: Orange Money and Moov Money are totalled apart at the close.
        com.example.hms.enums.MobileMoneyOperator operator = com.example.hms.service.payment.MobileMoneyOperators
                .require(dto.getPaymentMethod(), dto.getMobileMoneyOperator(), "mobileMoneyOperator");
        // QA D1129: the same mobile-money transaction is not counted at two tills. A keyed sale is checked once its
        // key is known to be new (below): its own retry carries the reference it already recorded.
        boolean mobileMoney = dto.getPaymentMethod() == com.example.hms.enums.PharmacyPaymentMethod.MOBILE_MONEY;
        if (mobileMoney && (dto.getReferenceNumber() == null || dto.getReferenceNumber().isBlank())) {
            throw new com.example.hms.exception.FieldBusinessException("referenceNumber", "pharmacy.payment.mobileMoneyReferenceRequired");
        }
        if (mobileMoney && dto.getClientRequestId() == null) {
            referenceRegistry.requireUnused(activeHospitalId, dto.getReferenceNumber(), "referenceNumber");
        }

        Hospital hospital = hospitalRepository.findById(dto.getHospitalId())
                .orElseThrow(() -> new ResourceNotFoundException("hospital.notfound", dto.getHospitalId()));

        // Billing plan PR 11 (QA D2430): a keyed sale locks its pharmacy row, so a second submit with the same
        // key waits for the first and answers with it. Without a key nothing changes.
        Pharmacy pharmacy = (dto.getClientRequestId() == null
                ? pharmacyRepository.findByIdAndHospital_Id(dto.getPharmacyId(), activeHospitalId)
                : pharmacyRepository.findLockedByIdAndHospitalId(dto.getPharmacyId(), activeHospitalId))
                .orElseThrow(() -> new ResourceNotFoundException("pharmacy.notfound"));
        PharmacySale keyed = findKeyedSale(activeHospitalId, pharmacy, dto);
        if (keyed != null) {
            return mapper.toResponseDTO(keyed);
        }
        if (mobileMoney && dto.getClientRequestId() != null) {
            referenceRegistry.requireUnused(activeHospitalId, dto.getReferenceNumber(), "referenceNumber");
        }

        Patient patient = null;
        if (dto.getPatientId() != null) {
            patient = patientRepository.findById(dto.getPatientId())
                    .orElseThrow(() -> new ResourceNotFoundException("patient.notfound", dto.getPatientId()));
        }

        com.example.hms.service.support.DeceasedPatientGuard.refuseNewCare(patient);
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
                .mobileMoneyOperator(operator)
                .status(PharmacySaleStatus.COMPLETED)
                .notes(dto.getNotes())
                .clientRequestId(dto.getClientRequestId())
                .totalAmount(BigDecimal.ZERO)
                .build();

        // QA D2671 / D2674 (r65): every line is priced and counted by the server first (list price, pack
        // conversion), so FEFO and the stock check below judge the units that will really leave the shelf.
        java.util.List<PricedLine> priced = new java.util.ArrayList<>();
        for (SaleLineRequestDTO lineDto : dto.getLines()) {
            priced.add(price(lineDto, pharmacy));
        }
        BigDecimal runningTotal = BigDecimal.ZERO;
        // What the sale's lines take off each lot (nothing moves until the
        // sale is saved), so FEFO judges each line on what the others leave,
        // whatever order the lines were entered in (review of #975).
        java.util.Map<java.util.UUID, BigDecimal> takenByLot = quantityTakenByLot(priced);
        for (PricedLine line : priced) {
            SaleLine saleLine = buildLine(line, takenByLot);
            sale.addLine(saleLine);
            runningTotal = runningTotal.add(saleLine.getLineTotal());
        }
        sale.setTotalAmount(runningTotal.setScale(2, RoundingMode.HALF_UP));
        // Billing plan PR 10 (QA D2672): a counter sale is money in the pharmacist's till today; a closed till
        // refuses it and nothing is written.
        sale.setCashSessionId(stampPharmacyTill(activeHospitalId, soldBy != null ? soldBy.getId() : null));

        PharmacySale saved = saveKeyed(sale);
        // QA D2670 (r65): the sale takes its stock in the same transaction; a lot emptied meanwhile refuses the
        // whole sale (it rolls back), never records one the shelf cannot give.
        moveStock(saved, priced, soldBy);
        log.info("Recorded pharmacy sale {} ({} lines, total {})",
                saved.getId(), saved.getLines().size(), saved.getTotalAmount());
        return mapper.toResponseDTO(saved);
    }

    /** One requested line as the server prices and counts it. */
    record PricedLine(SaleLineRequestDTO request, MedicationCatalogItem item, InventoryItem stocked,
                      BigDecimal units, BigDecimal unitPrice, StockLot lot) {
    }

    /**
     * QA D2671 (r65): a line's price is the pharmacy's list price for the medication (its inventory item's selling
     * price), never the client's. A price sent with the line is accepted only when it is that price. QA D2674: a
     * quantity in packs is converted to the units the stock is counted in. QA D2670: a lot is required when the
     * item is kept in lots, and it must be a lot of that same item at the selling pharmacy.
     */
    private PricedLine price(SaleLineRequestDTO lineDto, Pharmacy pharmacy) {
        if (lineDto.getQuantity() == null || lineDto.getQuantity().signum() <= 0) {
            throw new com.example.hms.exception.FieldBusinessException("quantity", "pharmacy.sale.lineQuantityPositive");
        }
        MedicationCatalogItem item = catalogItemRepository.findById(lineDto.getMedicationCatalogItemId())
                .orElseThrow(() -> new ResourceNotFoundException("medication.catalog.notfound"));
        InventoryItem stocked = inventoryItemRepository
                .findByPharmacyIdAndMedicationCatalogItemId(pharmacy.getId(), item.getId())
                .filter(InventoryItem::isActive)
                .orElseThrow(() -> new com.example.hms.exception.FieldBusinessException(FIELD_ITEM,
                        "pharmacy.sale.notStocked"));
        // A controlled substance leaves the pharmacy against a signed prescription and in the register, never
        // over the counter (the register has no counter-sale movement).
        if (ControlledStockWitness.isControlled(stocked)) {
            throw new com.example.hms.exception.FieldBusinessException(FIELD_ITEM,
                    "pharmacy.sale.controlledNeedsPrescription");
        }
        BigDecimal listPrice = PharmacyListPrice.of(stocked)
                .orElseThrow(() -> new com.example.hms.exception.FieldBusinessException(FIELD_UNIT_PRICE,
                        "pharmacy.sale.noListPrice"));
        if (lineDto.getUnitPrice() != null && lineDto.getUnitPrice().compareTo(listPrice) != 0) {
            throw new com.example.hms.exception.FieldBusinessException(FIELD_UNIT_PRICE, "pharmacy.sale.priceNotList",
                    com.example.hms.utility.DisplayNumbers.quantity(listPrice));
        }
        BigDecimal units = PackQuantity.toUnits(stocked, lineDto.getQuantity(), lineDto.getInPacks(), "quantity");
        StockLot lot = lineDto.getStockLotId() == null ? null : sellingLot(lineDto, pharmacy, stocked);
        if (lot == null && stockLotRepository.existsByInventoryItemId(stocked.getId())) {
            throw new com.example.hms.exception.FieldBusinessException("stockLotId", "pharmacy.sale.lotRequired");
        }
        // QA D779: half a tablet of a counted item is refused here too.
        WholeUnitQuantity.require(stocked, units);
        return new PricedLine(lineDto, item, stocked, units, listPrice, lot);
    }

    /**
     * QA D2670 (r65): each line takes its units off its lot (when kept in lots) and off the item, with the atomic
     * UPDATEs every stock writer uses, and writes a SALE movement naming the sale. 0 rows = not enough left at this
     * instant: the sale is refused with the lot and the quantity, and nothing of it is kept.
     */
    private void moveStock(PharmacySale sale, java.util.List<PricedLine> lines, User soldBy) {
        LocalDateTime now = LocalDateTime.now(java.time.ZoneId.systemDefault());
        for (PricedLine line : lines) {
            String quantity = com.example.hms.utility.DisplayNumbers.quantity(line.units());
            if (line.lot() != null
                    && stockLotRepository.decrementRemaining(line.lot().getId(), line.units(), now) == 0) {
                throw new com.example.hms.exception.FieldBusinessException("stockLotId", "stock.lot.insufficient",
                        line.lot().getLotNumber(), quantity);
            }
            if (inventoryItemRepository.decrementOnHand(line.stocked().getId(), line.units(), now) == 0) {
                throw new com.example.hms.exception.FieldBusinessException("quantity",
                        "pharmacy.sale.stockInsufficient", quantity);
            }
            stockTransactionRepository.save(StockTransaction.builder()
                    .inventoryItem(line.stocked())
                    .stockLot(line.lot())
                    .transactionType(StockTransactionType.SALE)
                    .quantity(line.units())
                    .referenceId(sale.getId())
                    .performedByUser(soldBy)
                    .build());
        }
    }

    /**
     * Billing plan PR 11 (QA D2430): the sale this request's key already recorded at this hospital, when it is
     * the same sale (same pharmacy, payment method, patient and number of lines). The key on another sale is
     * refused, never answered with the sale it first named.
     */
    private PharmacySale findKeyedSale(UUID hospitalId, Pharmacy pharmacy, PharmacySaleRequestDTO dto) {
        if (dto.getClientRequestId() == null) {
            return null;
        }
        PharmacySale prior = saleRepository.findByHospital_IdAndClientRequestId(hospitalId, dto.getClientRequestId())
                .orElse(null);
        if (prior == null) {
            return null;
        }
        UUID priorPatient = prior.getPatient() != null ? prior.getPatient().getId() : null;
        boolean same = prior.getPharmacy() != null && pharmacy.getId().equals(prior.getPharmacy().getId())
                && prior.getPaymentMethod() == dto.getPaymentMethod()
                && java.util.Objects.equals(priorPatient, dto.getPatientId())
                && prior.getLines().size() == dto.getLines().size()
                && sameBasket(prior, dto, pharmacy);
        if (!same) {
            throw new com.example.hms.exception.ConflictException(
                    com.example.hms.utility.MessageUtil.resolve("billing.payment.clientRequestReused"));
        }
        return prior;
    }

    /**
     * QA D3402 (r70): a retry with the same key and a different basket (3 SRO instead of 1) answered 201 with the
     * first sale. The basket is the same when each requested line matches a recorded line, one for one: the same
     * medication, the same lot when one was named, and the same quantity in units (a quantity in packs is converted
     * as the sale converts it; review of #1284).
     */
    private boolean sameBasket(PharmacySale prior, PharmacySaleRequestDTO dto, Pharmacy pharmacy) {
        java.util.List<SaleLine> left = new java.util.ArrayList<>(prior.getLines());
        for (SaleLineRequestDTO wanted : dto.getLines()) {
            BigDecimal units = unitsOf(wanted, pharmacy);
            SaleLine match = left.stream().filter(line -> sameLine(line, wanted, units)).findFirst().orElse(null);
            if (match == null) {
                return false;
            }
            left.remove(match);
        }
        return left.isEmpty();
    }

    /** The units a requested line stands for: its quantity, converted from packs as the sale converts it. */
    private BigDecimal unitsOf(SaleLineRequestDTO wanted, Pharmacy pharmacy) {
        if (!Boolean.TRUE.equals(wanted.getInPacks()) || wanted.getQuantity() == null
                || wanted.getMedicationCatalogItemId() == null) {
            return wanted.getQuantity();
        }
        return inventoryItemRepository.findByPharmacyIdAndMedicationCatalogItemId(pharmacy.getId(),
                        wanted.getMedicationCatalogItemId())
                .map(item -> PackQuantity.toUnits(item, wanted.getQuantity(), true, "quantity"))
                .orElse(null);
    }

    private static boolean sameLine(SaleLine recorded, SaleLineRequestDTO wanted, BigDecimal units) {
        UUID item = recorded.getMedicationCatalogItem() != null ? recorded.getMedicationCatalogItem().getId() : null;
        UUID lot = recorded.getStockLot() != null ? recorded.getStockLot().getId() : null;
        if (!java.util.Objects.equals(item, wanted.getMedicationCatalogItemId())) {
            return false;
        }
        if (wanted.getStockLotId() != null && !wanted.getStockLotId().equals(lot)) {
            return false;
        }
        return recorded.getQuantity() != null && units != null && recorded.getQuantity().compareTo(units) == 0;
    }

    /** Saves the sale; a keyed one is flushed so a key another sale took is refused here (V310). */
    private PharmacySale saveKeyed(PharmacySale sale) {
        if (sale.getClientRequestId() == null) {
            return saleRepository.save(sale);
        }
        try {
            return saleRepository.saveAndFlush(sale);
        } catch (org.springframework.dao.DataIntegrityViolationException refused) {
            if (String.valueOf(refused.getMostSpecificCause().getMessage()).contains("uk_psale_hospital_client_request")) {
                throw new com.example.hms.exception.ConflictException(
                        com.example.hms.utility.MessageUtil.resolve("billing.payment.clientRequestReused"));
            }
            throw refused;
        }
    }

    /** The units each lot gives up across all the lines of a sale. */
    private static java.util.Map<java.util.UUID, BigDecimal> quantityTakenByLot(java.util.List<PricedLine> lines) {
        java.util.Map<java.util.UUID, BigDecimal> taken = new java.util.HashMap<>();
        for (PricedLine line : lines) {
            if (line.lot() != null) {
                taken.merge(line.lot().getId(), line.units(), BigDecimal::add);
            }
        }
        return java.util.Map.copyOf(taken);
    }

    private SaleLine buildLine(PricedLine line, java.util.Map<java.util.UUID, BigDecimal> takenByLot) {
        SaleLineRequestDTO lineDto = line.request();
        StockLot lot = line.lot();
        if (lot != null) {
            // Review of #975: the sale's lines together never take more than
            // the lot holds - an oversold lot would also hand later lines a
            // FEFO credit for stock that is not there.
            BigDecimal taken = takenByLot.getOrDefault(lot.getId(), line.units());
            if (lot.getRemainingQuantity() != null && taken.compareTo(lot.getRemainingQuantity()) > 0) {
                throw new com.example.hms.exception.FieldBusinessException("stockLotId", "stock.lot.insufficient", lot.getLotNumber(),
                        com.example.hms.utility.DisplayNumbers.quantity(taken));
            }
        } else if (line.stocked().getQuantityOnHand() != null
                && line.units().compareTo(line.stocked().getQuantityOnHand()) > 0) {
            throw new com.example.hms.exception.FieldBusinessException("quantity", "pharmacy.sale.stockInsufficient",
                    com.example.hms.utility.DisplayNumbers.quantity(line.units()));
        }
        String lineNotes = lot == null ? lineDto.getNotes() : notesWithFefoReason(lineDto, lot, takenByLot);

        return SaleLine.builder()
                .medicationCatalogItem(line.item())
                .stockLot(lot)
                .quantity(line.units())
                .unitPrice(line.unitPrice())
                .lineTotal(PharmacyListPrice.amount(line.unitPrice(), line.units()).setScale(2, RoundingMode.HALF_UP))
                .notes(lineNotes)
                .build();
    }

    /**
     * The lot a line is sold from: of the selling pharmacy, of the line's own medication (QA D2670: a lot of
     * another drug would take the wrong shelf down), and not expired (review of #1239).
     */
    private StockLot sellingLot(SaleLineRequestDTO lineDto, Pharmacy pharmacy, InventoryItem stocked) {
        StockLot lot = stockLotRepository.findById(lineDto.getStockLotId())
                .orElseThrow(() -> new ResourceNotFoundException("stocklot.notfound"));
        // Lot must belong to the selling pharmacy — prevents cross-pharmacy traceability bugs.
        if (lot.getInventoryItem() == null
                || lot.getInventoryItem().getPharmacy() == null
                || !pharmacy.getId().equals(lot.getInventoryItem().getPharmacy().getId())) {
            throw new com.example.hms.exception.FieldBusinessException("stockLotId", "pharmacy.sale.lotOtherPharmacy");
        }
        if (!stocked.getId().equals(lot.getInventoryItem().getId())) {
            throw new com.example.hms.exception.FieldBusinessException("stockLotId", "pharmacy.sale.lotOtherMedication");
        }
        // Review of #1239: an expired lot is never sold, as it is never dispensed. Expired ON its expiry date
        // (QA D710, the rule of DispenseVerificationService.verifyExpiry and of the goods receipt).
        java.time.LocalDate expiry = lot.getExpiryDate();
        if (expiry == null || !expiry.isAfter(java.time.LocalDate.now(java.time.ZoneId.systemDefault()))) {
            throw new com.example.hms.exception.FieldBusinessException("stockLotId", "pharmacy.sale.lotExpired",
                    lot.getLotNumber(), expiry == null ? "" : com.example.hms.utility.DisplayDates.date(expiry));
        }
        return lot;
    }

    /**
     * QA D964: a later-dated lot while a shorter-dated one could serve the
     * line needs a reason, kept in the line's notes after the line's own note.
     */
    private String notesWithFefoReason(SaleLineRequestDTO lineDto, StockLot lot,
                                       java.util.Map<java.util.UUID, BigDecimal> takenByLot) {
        String lineNotes = lineDto.getNotes();
        String fefoReason = fefoLotPolicy.requireFefoOrReason(lot,
                lineDto.getFefoOverrideReason(), takenByLot);
        if (fefoReason == null) {
            return lineNotes;
        }
        String existing = lineNotes == null || lineNotes.isBlank() ? null : lineNotes.trim();
        String prefix = existing == null ? "" : existing + "\n";
        String combined = prefix + FEFO_NOTE_PREFIX + fefoReason;
        // Review of #975: SaleLine.notes is 500 characters; refused up
        // front with what still fits, as the dispense path does, instead
        // of a constraint violation at flush.
        if (combined.length() > LINE_NOTES_MAX) {
            throw new BusinessException("pharmacy.fefo.reasonTooLong",
                    String.valueOf(Math.max(0, LINE_NOTES_MAX - prefix.length() - FEFO_NOTE_PREFIX.length())));
        }
        return combined;
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
            throw new BusinessException("pharmacy.sale.hospitalMismatch");
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
            throw new BusinessException("pharmacy.currentUserUnknown");
        }
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("user.current.notfound"));
    }

    /** Billing plan PR 10 (V309, QA D2672): pharmacy money lands in the pharmacist's PHARMACY cash session. */
    private com.example.hms.service.billing.cash.CashSessionService cashSessions;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setCashSessionService(com.example.hms.service.billing.cash.CashSessionService service) {
        this.cashSessions = service;
    }

    private java.util.UUID stampPharmacyTill(java.util.UUID hospitalId, java.util.UUID cashierId) {
        return cashSessions == null ? null
                : cashSessions.stamp(com.example.hms.enums.CashTill.PHARMACY, hospitalId, cashierId);
    }
}
