package com.example.hms.service.billing;

import com.example.hms.exception.BusinessException;
import com.example.hms.model.BillingInvoice;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientGuarantor;
import com.example.hms.payload.dto.EmailInvoiceRequest;
import com.example.hms.repository.PatientGuarantorRepository;
import com.example.hms.utility.ActivationDeliveryTracker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Scorecard K4: who an invoice may be mailed to. An invoice names the patient, the dates of care and the amounts,
 * so it goes to an address the hospital holds for it: the patient's own (checked as verified by the message gate)
 * or an active guarantor's at the invoice's hospital. Any other address needs the clerk's reason, which is kept in
 * the audit trail with the masked address. One send reaches at most {@link EmailInvoiceRequest#MAX_RECIPIENTS}
 * addresses, all lists together.
 */
@Component
@RequiredArgsConstructor
public class InvoiceMailRecipients {

    /** Where the address comes from. */
    public enum Kind { PATIENT, GUARANTOR, OTHER }

    /** One address of the send. {@code guarantorId} only for a guarantor's. The address is never printed. */
    public record Recipient(String address, Kind kind, UUID guarantorId) {
        public String masked() {
            return ActivationDeliveryTracker.maskEmail(address);
        }

        @Override
        public String toString() {
            return "Recipient[" + kind + "]";
        }
    }

    private final PatientGuarantorRepository guarantorRepository;

    /**
     * The send's recipients, each with its source. Refuses (nothing is sent) more than the cap, and an address
     * that is on no file when no reason was given.
     */
    public List<Recipient> classify(BillingInvoice invoice, EmailInvoiceRequest request) {
        Map<String, String> typed = new LinkedHashMap<>();
        Stream.of(request.to(), request.cc(), request.bcc())
            .filter(Objects::nonNull)
            .flatMap(List::stream)
            .filter(StringUtils::hasText)
            .forEach(address -> typed.putIfAbsent(normalised(address), address.trim()));
        if (typed.size() > EmailInvoiceRequest.MAX_RECIPIENTS) {
            throw new BusinessException("invoiceMail.recipient.tooMany", EmailInvoiceRequest.MAX_RECIPIENTS);
        }
        Patient patient = invoice.getPatient();
        List<String> own = patient == null ? List.of()
            : Stream.of(patient.getEmail(), patient.getUser() != null ? patient.getUser().getEmail() : null)
                .filter(StringUtils::hasText).map(InvoiceMailRecipients::normalised).toList();
        Map<String, UUID> guarantors = guarantorAddresses(invoice);
        boolean reasonGiven = StringUtils.hasText(request.reason());
        List<Recipient> out = new ArrayList<>();
        for (Map.Entry<String, String> entry : typed.entrySet()) {
            if (own.contains(entry.getKey())) {
                out.add(new Recipient(entry.getValue(), Kind.PATIENT, null));
            } else if (guarantors.containsKey(entry.getKey())) {
                out.add(new Recipient(entry.getValue(), Kind.GUARANTOR, guarantors.get(entry.getKey())));
            } else if (reasonGiven) {
                out.add(new Recipient(entry.getValue(), Kind.OTHER, null));
            } else {
                throw new BusinessException("invoiceMail.recipient.notOnFile",
                    ActivationDeliveryTracker.maskEmail(entry.getValue()));
            }
        }
        return List.copyOf(out);
    }

    /** The active guarantors of the invoice's patient at the invoice's hospital, by normalised address. */
    private Map<String, UUID> guarantorAddresses(BillingInvoice invoice) {
        Map<String, UUID> out = new LinkedHashMap<>();
        if (invoice.getPatient() == null || invoice.getPatient().getId() == null
            || invoice.getHospital() == null || invoice.getHospital().getId() == null) {
            return out;
        }
        for (PatientGuarantor guarantor : guarantorRepository.findByPatient_IdAndHospital_IdAndActiveTrue(
            invoice.getPatient().getId(), invoice.getHospital().getId())) {
            if (StringUtils.hasText(guarantor.getEmail())) {
                out.putIfAbsent(normalised(guarantor.getEmail()), guarantor.getId());
            }
        }
        return out;
    }

    private static String normalised(String address) {
        return address.trim().toLowerCase(Locale.ROOT);
    }
}
