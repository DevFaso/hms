package com.example.hms.service.billing.claim;

import com.example.hms.enums.PriceListCategory;
import com.example.hms.model.Admission;
import com.example.hms.model.BillingInvoice;
import com.example.hms.model.InvoiceItem;
import com.example.hms.model.InvoicePayerShare;
import com.example.hms.model.InvoicePayerShareLine;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientInsurance;
import com.example.hms.model.PayerPlan;
import com.example.hms.model.PriceListItem;
import com.example.hms.repository.AdmissionRepository;
import com.example.hms.repository.BillingInvoiceRepository;
import com.example.hms.repository.InvoiceItemRepository;
import com.example.hms.repository.InvoicePayerShareLineRepository;
import com.example.hms.repository.PatientInsuranceRepository;
import com.example.hms.repository.PayerPlanRepository;
import com.example.hms.repository.PriceListItemRepository;
import com.example.hms.service.billing.BillingTextRenderer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Insurer claims plan PR 2 (US-6, US-7): what a bordereau says of a payer share, read live from the invoice, its
 * lines, the coverage and the plan, all at one hospital. Used to propose shares for a draft, to show a draft, and
 * once, at submission, to write each line's snapshot.
 *
 * <p>The date that places a share in a period (owner decision of 2026-11-26, Q10): the service date (the invoice
 * date) for outpatient care; for a hospital stay, the day the patient left, so a stay is claimed whole, once. A stay
 * the patient has not left yet is not claimable.
 */
@Component
@RequiredArgsConstructor
public class InsurerClaimLineReader {

    /** Acts a payer usually approves beforehand: a line of these categories with no number is flagged, never held. */
    private static final Set<PriceListCategory> AUTHORISATION_EXPECTED =
        Set.of(PriceListCategory.IMAGING, PriceListCategory.BED_DAY);

    static final String WARN_POLICY = "MISSING_POLICY_NUMBER";
    static final String WARN_UNVERIFIED = "COVERAGE_NOT_VERIFIED";
    static final String WARN_EXPIRED = "COVERAGE_EXPIRED";
    static final String WARN_AUTHORISATION = "MISSING_PRE_AUTHORISATION";
    static final String WARN_PERIOD = "OUT_OF_PERIOD";

    private final BillingInvoiceRepository invoiceRepository;
    private final InvoiceItemRepository itemRepository;
    private final InvoicePayerShareLineRepository shareLineRepository;
    private final PatientInsuranceRepository insuranceRepository;
    private final PayerPlanRepository planRepository;
    private final PriceListItemRepository priceListItemRepository;
    private final AdmissionRepository admissionRepository;

    /** One act a share claims: the invoice line and what the share claims of it. */
    public record Act(UUID invoiceItemId, String actCode, String actLabel, String category, Integer quantity,
                      BigDecimal grossAmount, BigDecimal claimedAmount, String preAuthorisationNumber,
                      boolean authorisationExpected) {

        /** The patient's part of the act: what was billed less what the payer is asked. Never below zero. */
        public BigDecimal patientAmount() {
            return grossAmount.subtract(claimedAmount).max(BigDecimal.ZERO);
        }
    }

    /**
     * One share as a bordereau reads it.
     *
     * @param dischargeDate the day the patient left, for a hospital stay; null for outpatient care
     * @param stayOpen      a hospital stay the patient has not left: not claimable yet
     */
    public record ShareFacts(InvoicePayerShare share, BillingInvoice invoice, String patientName, String patientMrn,
                             PatientInsurance coverage, PayerPlan plan, LocalDate serviceDate,
                             LocalDate dischargeDate, boolean stayOpen, List<Act> acts) {

        /** The date that places the share in a period. */
        public LocalDate claimDate() {
            return dischargeDate != null ? dischargeDate : serviceDate;
        }

        /** The acts' claimed amounts make up the share exactly: the share can go on a bordereau. */
        public boolean complete() {
            BigDecimal sum = acts.stream().map(Act::claimedAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            return !acts.isEmpty() && sum.compareTo(share.getAmount()) == 0;
        }

        /** What to check before sending, for one act of the share on a bordereau of [from, to]. */
        public List<String> warnings(Act act, LocalDate periodFrom, LocalDate periodTo) {
            List<String> warnings = new ArrayList<>();
            String policy = coverage != null ? coverage.getPolicyNumber() : null;
            if (policy == null || policy.isBlank()) {
                warnings.add(WARN_POLICY);
            }
            if (coverage == null || coverage.getVerifiedAt() == null) {
                warnings.add(WARN_UNVERIFIED);
            }
            if (coverage != null && coverage.getExpirationDate() != null && serviceDate != null
                    && coverage.getExpirationDate().isBefore(serviceDate)) {
                warnings.add(WARN_EXPIRED);
            }
            String number = act.preAuthorisationNumber();
            if (act.authorisationExpected() && (number == null || number.isBlank())) {
                warnings.add(WARN_AUTHORISATION);
            }
            LocalDate day = claimDate();
            if (day != null && periodFrom != null && periodTo != null
                    && (day.isBefore(periodFrom) || day.isAfter(periodTo))) {
                warnings.add(WARN_PERIOD);
            }
            return warnings;
        }
    }

    /**
     * The facts of these shares at one hospital, keyed by share id. A share whose invoice is not of the hospital is
     * left out. Act labels the server wrote are rendered in {@code labelLocale}.
     */
    public Map<UUID, ShareFacts> read(Collection<InvoicePayerShare> shares, UUID hospitalId, Locale labelLocale) {
        Map<UUID, ShareFacts> out = new LinkedHashMap<>();
        if (shares == null || shares.isEmpty()) {
            return out;
        }
        Set<UUID> shareIds = shares.stream().map(InvoicePayerShare::getId).collect(Collectors.toSet());
        Set<UUID> invoiceIds = shares.stream().map(InvoicePayerShare::getInvoiceId).collect(Collectors.toSet());
        Map<UUID, Map<UUID, BigDecimal>> claimedByShare = shareLineRepository
            .findByShareIdInAndHospitalId(shareIds, hospitalId).stream()
            .collect(Collectors.groupingBy(InvoicePayerShareLine::getShareId,
                Collectors.toMap(InvoicePayerShareLine::getInvoiceItemId, InvoicePayerShareLine::getAmount,
                    (a, b) -> a)));
        Map<UUID, List<InvoiceItem>> itemsByInvoice = itemRepository
            .findByBillingInvoice_IdInAndBillingInvoice_Hospital_Id(invoiceIds, hospitalId).stream()
            .filter(item -> item.getBillingInvoice() != null)
            .collect(Collectors.groupingBy(item -> item.getBillingInvoice().getId()));
        Set<UUID> priceItemIds = itemsByInvoice.values().stream().flatMap(List::stream)
            .map(InvoiceItem::getPriceListItemId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, String> actCodes = priceItemIds.isEmpty() ? Map.of()
            : priceListItemRepository.findAllById(priceItemIds).stream()
                .filter(item -> hospitalId.equals(item.getHospitalId()) && item.getCode() != null)
                .collect(Collectors.toMap(PriceListItem::getId, PriceListItem::getCode, (a, b) -> a));
        Map<UUID, Optional<BillingInvoice>> invoices = new HashMap<>();
        Map<UUID, Optional<PatientInsurance>> coverages = new HashMap<>();
        Map<UUID, Optional<PayerPlan>> plans = new HashMap<>();
        for (InvoicePayerShare share : shares) {
            BillingInvoice invoice = invoices.computeIfAbsent(share.getInvoiceId(),
                id -> invoiceAtHospital(id, hospitalId)).orElse(null);
            if (invoice == null) {
                continue;
            }
            PatientInsurance coverage = coverages.computeIfAbsent(share.getPatientInsuranceId(),
                id -> insuranceRepository.findByIdAndAssignment_Hospital_Id(id, hospitalId)).orElse(null);
            PayerPlan plan = plans.computeIfAbsent(share.getPayerPlanId(),
                id -> planRepository.findByIdAndHospitalId(id, hospitalId)).orElse(null);
            Patient patient = invoice.getPatient();
            Stay stay = stayOf(invoice, hospitalId);
            out.put(share.getId(), new ShareFacts(share, invoice,
                patient != null ? patient.getFullName() : null,
                patient != null ? patient.getMrnForHospital(hospitalId) : null,
                coverage, plan, invoice.getInvoiceDate(), stay.dischargeDate(), stay.open(),
                acts(itemsByInvoice.getOrDefault(invoice.getId(), List.of()), claimedByShare.get(share.getId()),
                    actCodes, labelLocale)));
        }
        return out;
    }

    /** The invoice when it is one of the hospital; another hospital's is not found. */
    private Optional<BillingInvoice> invoiceAtHospital(UUID invoiceId, UUID hospitalId) {
        return invoiceRepository.findById(invoiceId)
            .filter(found -> found.getHospital() != null && hospitalId.equals(found.getHospital().getId()));
    }

    private record Stay(LocalDate dischargeDate, boolean open) {
    }

    /** The hospital stay the invoice bills, when its visit belongs to an admission of the same hospital. */
    private Stay stayOf(BillingInvoice invoice, UUID hospitalId) {
        UUID admissionId = invoice.getEncounter() != null ? invoice.getEncounter().getAdmissionId() : null;
        if (admissionId == null) {
            return new Stay(null, false);
        }
        Admission admission = admissionRepository.findById(admissionId)
            .filter(found -> found.getHospital() != null && hospitalId.equals(found.getHospital().getId()))
            .orElse(null);
        if (admission == null) {
            return new Stay(null, false);
        }
        if (admission.getActualDischargeDateTime() == null) {
            return new Stay(null, true);
        }
        return new Stay(admission.getActualDischargeDateTime().toLocalDate(), false);
    }

    /**
     * The acts one share claims, in posting order: from the share's own rows (V383) when it has any, else from the
     * live split on the invoice lines (a share written before V383).
     */
    private static List<Act> acts(List<InvoiceItem> items, Map<UUID, BigDecimal> own, Map<UUID, String> actCodes,
                                  Locale labelLocale) {
        return items.stream()
            .sorted(Comparator.comparing(InvoiceItem::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(item -> String.valueOf(item.getId())))
            .map(item -> act(item, claimedOf(item, own), actCodes, labelLocale))
            .filter(act -> act.claimedAmount().signum() > 0)
            .toList();
    }

    private static BigDecimal claimedOf(InvoiceItem item, Map<UUID, BigDecimal> own) {
        if (own != null) {
            return own.getOrDefault(item.getId(), BigDecimal.ZERO);
        }
        return item.getPayerShareAmount() != null ? item.getPayerShareAmount() : BigDecimal.ZERO;
    }

    private static Act act(InvoiceItem item, BigDecimal claimed, Map<UUID, String> actCodes, Locale labelLocale) {
        PriceListCategory coverageCategory = item.getCoverageCategory();
        String category;
        if (coverageCategory != null) {
            category = coverageCategory.name();
        } else {
            category = item.getItemCategory() != null ? item.getItemCategory().name() : null;
        }
        String label = BillingTextRenderer.render(labelLocale, item.getTextCode(), item.getTextArgs(),
            item.getItemDescription());
        return new Act(item.getId(),
            item.getPriceListItemId() != null ? actCodes.get(item.getPriceListItemId()) : null,
            label, category, item.getQuantity(),
            item.getTotalPrice() != null ? item.getTotalPrice() : BigDecimal.ZERO, claimed,
            item.getPreAuthorisationNumber(),
            coverageCategory != null && AUTHORISATION_EXPECTED.contains(coverageCategory));
    }
}
