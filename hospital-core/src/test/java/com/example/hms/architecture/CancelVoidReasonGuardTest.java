package com.example.hms.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.SpringAsmInfo;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * QA D1069 (class guard, round 19): every path that cancels, voids, reverses,
 * rejects or undoes money or stock in pharmacy and billing asks for a reason,
 * on the server, and a test proves the blank reason is refused.
 *
 * <p>Round 18 fixed the invoice cancel and named the pharmacy dispense cancel
 * in its own class table, but {@code POST /pharmacy/dispense/{id}/cancel}
 * with an empty body still voided the payment and put the stock back. This
 * guard reads every write handler of the billing and pharmacy controllers;
 * one whose path names such an action (or that is a DELETE) must be listed
 * below, either with the request body it reads the reason from and the test
 * that proves a blank reason is refused, or with why it is not a void (a
 * draft that was never issued, a catalogue row). A new handler fails until it
 * is listed; a listed handler that disappears fails until its line goes.
 * The invoice cancel itself is the status move of {@code PUT
 * /billing-invoices/{id}}, listed with its proof as the last row.
 */
class CancelVoidReasonGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");
    private static final Path TEST = Path.of("src/test/java/com/example/hms");

    /** The controllers of money and stock: pharmacy, billing, invoices, payments, claims. */
    private static final Pattern MONEY_CONTROLLER = Pattern.compile("controller/(pharmacy/\\w++|\\w++)\\.java");
    private static final Pattern MONEY_NAME =
        Pattern.compile("Billing|Invoice|Payment|Pharmacy|Dispense|Claim|Copay|Cashier");

    /** A controller under controller/pharmacy/, or one whose name says money or stock. */
    static boolean isMoneyController(String rel) {
        Matcher m = MONEY_CONTROLLER.matcher(rel);
        return m.matches() && (m.group(1).startsWith("pharmacy/") || MONEY_NAME.matcher(m.group(1)).find());
    }

    /** A path that undoes something already done. */
    private static final Pattern VOID_PATH = Pattern.compile("(?i)(cancel|void|revers|reject|refund|undo|waive|annul)");

    /**
     * Every cancel / void / reverse / reject / undo handler of money and stock:
     * "METHOD path" -> the reason's source and the test proving a blank one is
     * refused ("Class#method"), or "NOT A VOID: why".
     */
    private static final Map<String, String> HANDLERS = new LinkedHashMap<>();

    static {
        HANDLERS.put("POST /pharmacy/dispense/{id}/cancel",
            "DispenseCancelRequestDTO.reason -> service/pharmacy/DispenseServiceImplTest#aCancelWithoutAReasonIsRefused");
        HANDLERS.put("POST /pharmacy/dispense/{id}/cancel-ready",
            "CancelReadyRequestDTO.reason (@NotNull) -> architecture/CancelVoidReasonGuardTest#cancelReadyNeedsAReason");
        HANDLERS.put("POST /pharmacy/claims/{id}/reject",
            "body.rejectionReason -> service/pharmacy/PharmacyClaimServiceImplTest#rejectRequiresReason");
        HANDLERS.put("POST /billing-invoices/{invoiceId}/payments/{paymentId}/reject",
            "PaymentReasonRequestDTO.reason (@NotBlank) -> service/BillingInvoiceServiceImplTest#rejectPayment_needsAReason_D1069");
        HANDLERS.put("POST /billing-invoices/{invoiceId}/payments/{paymentId}/reverse",
            "PaymentReasonRequestDTO.reason (@NotBlank) -> service/BillingInvoiceServiceImplTest#reversePayment_needsAReasonAndAConfirmedRow_D142");
        HANDLERS.put("POST /billing-invoices/{invoiceId}/adjustments/{adjustmentId}/reverse",
            "AdjustmentReversalRequestDTO.reason (@NotBlank) -> service/BillingInvoiceServiceImplTest#reverseAdjustment_needsAReasonALiveRowAndAStandingInvoice_D1105");
        HANDLERS.put("DELETE /billing-invoices/{id}",
            "NOT A VOID: only a DRAFT is deleted (billing.invoice.deleteOnlyDraft, QA D613); an issued invoice is cancelled with a reason");
        HANDLERS.put("DELETE /invoice-items/{id}",
            "NOT A VOID: a line is removed only from a DRAFT invoice (billing.invoice.issuedLinesLocked, QA D656); an issued line is credited with a reason");
        HANDLERS.put("DELETE /pharmacy/inventory/items/{id}",
            "NOT A VOID: deactivates a catalogue item, moves no stock and no money");
        HANDLERS.put("DELETE /pharmacy-registry/{id}",
            "NOT A VOID: deactivates a pharmacy registry entry, moves no stock and no money");
    }

    /** The cancel of an issued invoice is a status move on the update, not a path of its own. */
    private static final String INVOICE_CANCEL_PROOF =
        "service/BillingInvoiceServiceImplTest#updateInvoice_anIssuedInvoiceIsNotCancelledWithoutAReason_D1069";

    /** Also proven in each portal screen that offers the action (QA D1069 round 19, UI). */
    private static final List<String> UI_PROOFS = List.of(
        "hospital-portal/src/app/pharmacy/dispensing.spec.ts",
        "hospital-portal/src/app/billing/billing.spec.ts");

    @Test
    void everyCancelOrVoidOfMoneyOrStockIsListedWithItsReasonProof() throws Exception {
        List<String> found = new ArrayList<>();
        for (Path f : controllers()) {
            String rel = MAIN.relativize(f).toString().replace('\\', '/');
            if (!isMoneyController(rel)) {
                continue;
            }
            Class<?> type = Class.forName("com.example.hms." + rel.replace('/', '.').replace(".java", ""));
            String base = first(type.getAnnotation(RequestMapping.class) != null
                ? type.getAnnotation(RequestMapping.class).value() : new String[0]);
            for (Method m : type.getDeclaredMethods()) {
                String verb = null;
                String[] paths = null;
                if (m.getAnnotation(PostMapping.class) != null) {
                    verb = "POST";
                    paths = merge(m.getAnnotation(PostMapping.class).value(), m.getAnnotation(PostMapping.class).path());
                } else if (m.getAnnotation(PutMapping.class) != null) {
                    verb = "PUT";
                    paths = merge(m.getAnnotation(PutMapping.class).value(), m.getAnnotation(PutMapping.class).path());
                } else if (m.getAnnotation(PatchMapping.class) != null) {
                    verb = "PATCH";
                    paths = merge(m.getAnnotation(PatchMapping.class).value(), m.getAnnotation(PatchMapping.class).path());
                } else if (m.getAnnotation(DeleteMapping.class) != null) {
                    verb = "DELETE";
                    paths = merge(m.getAnnotation(DeleteMapping.class).value(), m.getAnnotation(DeleteMapping.class).path());
                }
                if (verb == null) {
                    continue;
                }
                String path = base + first(paths);
                if ("DELETE".equals(verb) || VOID_PATH.matcher(path).find()) {
                    found.add(verb + " " + path);
                }
            }
        }
        assertThat(found).as("cancel / void handlers found").hasSizeGreaterThanOrEqualTo(6);
        assertThat(new TreeSet<>(found))
            .as("a cancel / void / reverse / reject handler of money or stock that is not listed with the test "
                + "proving it refuses a blank reason (QA D1069), or a listed one that is gone")
            .isEqualTo(new TreeSet<>(HANDLERS.keySet()));
    }

    @Test
    void everyListedProofExists() throws IOException {
        List<String> proofs = new ArrayList<>();
        HANDLERS.values().stream().filter(v -> v.contains(" -> ")).map(v -> v.substring(v.indexOf(" -> ") + 4))
            .forEach(proofs::add);
        proofs.add(INVOICE_CANCEL_PROOF);
        for (String proof : proofs) {
            String[] parts = proof.split("#");
            Path file = TEST.resolve(parts[0] + ".java");
            assertThat(file).as(proof).exists();
            assertThat(Files.readString(file)).as(proof).containsPattern("void " + Pattern.quote(parts[1]) + "\\(");
        }
        for (String spec : UI_PROOFS) {
            Path file = Path.of("..").resolve(spec);
            assertThat(Files.readString(file)).as(spec + " proves the reason in the UI").contains("D1069");
        }
    }

    @Test
    void everyReasonBodyCarriesAReasonField() throws Exception {
        for (String v : HANDLERS.values()) {
            if (!v.contains(" -> ") || v.startsWith("body.")) {
                continue;
            }
            String dto = v.substring(0, v.indexOf('.'));
            Class<?> type = findPayload(dto);
            boolean hasReason = Arrays.stream(type.getDeclaredFields())
                .anyMatch(f -> f.getName().toLowerCase().contains("reason") && f.getType() != void.class);
            assertThat(hasReason).as(dto + " carries the reason").isTrue();
        }
    }

    /** QA D1069: the preparation cancel takes a reason from a closed list; none is refused. */
    @Test
    void cancelReadyNeedsAReason() {
        try (jakarta.validation.ValidatorFactory factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var violations = factory.getValidator().validate(new com.example.hms.payload.dto.pharmacy.CancelReadyRequestDTO());
            assertThat(violations).extracting(v -> v.getPropertyPath().toString()).contains("reason");
        }
    }

    @Test
    void theScanSeesAnUnlistedVoid() {
        assertThat(VOID_PATH.matcher("/pharmacy/sales/{id}/refund").find()).isTrue();
        assertThat(VOID_PATH.matcher("/billing-invoices/{id}/payments").find()).isFalse();
        assertThat(isMoneyController("controller/pharmacy/PharmacySaleController.java")).isTrue();
        assertThat(isMoneyController("controller/PatientController.java")).isFalse();
    }

    // ── QA D1126 / D1132 (round 20): appointments ─────────────────────────

    private static final String APPOINTMENT = "com/example/hms/model/Appointment";
    private static final String APPOINTMENT_BUILDER = APPOINTMENT + "$AppointmentBuilder";

    /**
     * QA D1126 / D1132 (round 20): every main-code method that writes an
     * appointment's status ({@code Appointment.setStatus} or the builder's
     * {@code status(...)}), found by a bytecode scan, is listed here: either
     * it can drop the visit (CANCELLED or NO_SHOW), with where the reason
     * comes from and the test that proves a blank one is refused, or why the
     * path carries its own reason ("OWN REASON"), or "NEVER DROPS: why".
     * Round 19's guard covered pharmacy and billing only, and
     * {@code PUT /appointments/{id}/status?action=CANCEL} with no body
     * answered 200.
     */
    private static final Map<String, String> APPOINTMENT_STATUS_WRITERS = new LinkedHashMap<>();

    static {
        APPOINTMENT_STATUS_WRITERS.put("AppointmentServiceImpl.confirmOrCancelAppointment",
            "AppointmentStatusChangeRequestDTO.reason -> service/AppointmentStatusTransitionTest#qaD1126_aCancelOrNoShowWithoutAReasonIsRefused");
        APPOINTMENT_STATUS_WRITERS.put("AppointmentMapper.updateAppointmentFromDto",
            "AppointmentRequestDTO.statusReason (checked in AppointmentServiceImpl.updateAppointment before the mapper runs)"
                + " -> service/AppointmentStatusTransitionTest#qaD1126_theEditFormDropsAVisitOnlyWithAReason");
        APPOINTMENT_STATUS_WRITERS.put("AppointmentServiceImpl.createAppointment",
            "AppointmentRequestDTO.statusReason (requireStatusReason on the requested status)"
                + " -> architecture/CancelVoidReasonGuardTest#aBookingWrittenAsCancelledNeedsAReason");
        APPOINTMENT_STATUS_WRITERS.put("EncounterServiceImpl.closeUnseen",
            "CloseUnseenVisitRequestDTO.reason (required, QA D788)"
                + " -> service/EncounterServiceImplTest#qaD1126_closeUnseenKeepsTheReasonOnTheAppointment");
        APPOINTMENT_STATUS_WRITERS.put("PatientPortalServiceImpl.cancelMyAppointment",
            "OWN REASON: the patient cancels their own not-yet-started booking; the reason is optional on purpose"
                + " (the shipped iOS app labels it 'Reason (optional)') and kept in status_reason when given");
        APPOINTMENT_STATUS_WRITERS.put("MortalityServiceImpl.cancelFutureAppointments",
            "OWN REASON: the death record cancels the patient's future bookings; the record is the reason (written"
                + " to the notes); only SCHEDULED, CONFIRMED and RESCHEDULED bookings after the date of death");
        APPOINTMENT_STATUS_WRITERS.put("AppointmentMapper.toAppointment",
            "NEVER DROPS: no main code calls it; createAppointment builds the row itself and checks the reason");
        APPOINTMENT_STATUS_WRITERS.put("DevSyntheticDataSeeder.createAppointment",
            "NEVER DROPS: dev seed data, written COMPLETED");
        APPOINTMENT_STATUS_WRITERS.put("EncounterServiceImpl.checkOut", "NEVER DROPS: the check-out completes the appointment");
        APPOINTMENT_STATUS_WRITERS.put("EncounterServiceImpl.createFollowUpAppointmentFromCheckout",
            "NEVER DROPS: the follow-up is booked PENDING");
        APPOINTMENT_STATUS_WRITERS.put("GeneralReferralServiceImpl.createAppointmentForReferral",
            "NEVER DROPS: the referral's appointment is booked SCHEDULED");
        APPOINTMENT_STATUS_WRITERS.put("PatientPortalServiceImpl.scheduleMyAppointment",
            "NEVER DROPS: the patient's booking is written SCHEDULED");
        APPOINTMENT_STATUS_WRITERS.put("PatientPortalServiceImpl.rescheduleMyAppointment",
            "NEVER DROPS: a moved booking stays PENDING or becomes SCHEDULED (movedStatus)");
        APPOINTMENT_STATUS_WRITERS.put("ReceptionServiceImpl.checkInPatient", "NEVER DROPS: the check-in writes CHECKED_IN");
        APPOINTMENT_STATUS_WRITERS.put("SlotInventoryServiceImpl.book", "NEVER DROPS: a slot booking is written SCHEDULED");
    }

    @Test
    void everyAppointmentStatusWriteIsListedWithItsReasonProof() throws IOException {
        Set<String> writers = appointmentStatusWriters();
        assertThat(writers).as("appointment status writers found").hasSizeGreaterThanOrEqualTo(4);
        assertThat(new TreeSet<>(writers))
            .as("a method that writes an appointment's status is not listed (QA D1126): say whether it can cancel"
                + " or no-show the visit and where the reason comes from, or a listed one is gone")
            .isEqualTo(new TreeSet<>(APPOINTMENT_STATUS_WRITERS.keySet()));
        for (Map.Entry<String, String> e : APPOINTMENT_STATUS_WRITERS.entrySet()) {
            String v = e.getValue();
            if (!v.contains(" -> ")) {
                assertThat(v).as(e.getKey()).matches("(NEVER DROPS|OWN REASON): .+");
                continue;
            }
            String[] parts = v.substring(v.indexOf(" -> ") + 4).split("#");
            Path file = TEST.resolve(parts[0] + ".java");
            assertThat(file).as(v).exists();
            assertThat(Files.readString(file)).as(v).containsPattern("void " + Pattern.quote(parts[1]) + "\\(");
        }
    }

    /** QA D1132 (UI): the desk dialog asks for the reason, proven by a click-level spec. */
    @Test
    void theDeskAppointmentDialogProvesTheReason() throws IOException {
        Path spec = Path.of("../hospital-portal/src/app/appointments/appointment-detail.spec.ts");
        assertThat(Files.readString(spec)).contains("D1126").contains("drop-confirm").contains("drop-reason-error");
    }

    /** QA D1126: a booking written straight as CANCELLED or NO_SHOW is held to the same reason. */
    @Test
    void aBookingWrittenAsCancelledNeedsAReason() throws IOException {
        for (com.example.hms.enums.AppointmentStatus dropped : List.of(
                com.example.hms.enums.AppointmentStatus.CANCELLED, com.example.hms.enums.AppointmentStatus.NO_SHOW)) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    com.example.hms.service.AppointmentServiceImpl.requireStatusReason(dropped, " "))
                .isInstanceOf(com.example.hms.exception.BusinessException.class)
                .hasFieldOrPropertyWithValue("messageKey", "appointment.status.reasonRequired");
        }
        assertThat(com.example.hms.service.AppointmentServiceImpl
            .requireStatusReason(com.example.hms.enums.AppointmentStatus.SCHEDULED, null)).isNull();
        assertThat(Files.readString(MAIN.resolve("service/AppointmentServiceImpl.java")))
            .as("createAppointment keeps the reason of the status it writes")
            .contains("appointment.setStatusReason(requireStatusReason(status, request.getStatusReason()))");
    }

    /** "OuterClass.method" of every main-code write of an appointment's status (a lambda counts as its method). */
    private static Set<String> appointmentStatusWriters() throws IOException {
        Set<String> writers = new TreeSet<>();
        Resource[] all = new PathMatchingResourcePatternResolver().getResources("classpath*:com/example/hms/**/*.class");
        for (Resource resource : all) {
            if (!resource.getURL().toString().contains("/main/")) {
                continue;
            }
            String[] owner = new String[1];
            try (java.io.InputStream in = resource.getInputStream()) {
                new ClassReader(in).accept(new ClassVisitor(SpringAsmInfo.ASM_VERSION) {
                    @Override
                    public void visit(int version, int access, String name, String signature,
                                      String superName, String[] interfaces) {
                        String simple = name.substring(name.lastIndexOf('/') + 1);
                        owner[0] = simple.contains("$") ? simple.substring(0, simple.indexOf('$')) : simple;
                    }

                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                     String signature, String[] exceptions) {
                        String caller = owner[0] + "." + enclosingMethod(name);
                        return new MethodVisitor(SpringAsmInfo.ASM_VERSION) {
                            @Override
                            public void visitMethodInsn(int opcode, String target, String method,
                                                        String desc, boolean isInterface) {
                                boolean setter = APPOINTMENT.equals(target) && "setStatus".equals(method);
                                boolean builder = APPOINTMENT_BUILDER.equals(target) && "status".equals(method);
                                if ((setter || builder) && !"Appointment".equals(owner[0])) {
                                    writers.add(caller);
                                }
                            }
                        };
                    }
                }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
        }
        return writers;
    }

    private static String enclosingMethod(String name) {
        if (name.startsWith("lambda$")) {
            int end = name.indexOf('$', "lambda$".length());
            return end < 0 ? name : name.substring("lambda$".length(), end);
        }
        return name;
    }

    // ── QA D1157 / D1159 / D1160 (round 21): clinical and front-office withdrawals ──

    /** A path of any other controller that withdraws, closes or refuses something already recorded. */
    private static final Pattern CLINICAL_VOID_PATH =
        Pattern.compile("(?i)(cancel|close|discontinu|withdraw|revoke|void|reject|refuse|annul|undo|hold)");

    /**
     * QA D1157 / D1160 (round 21): every such handler outside money and stock
     * is listed: with the reason's source and the test proving a blank one is
     * refused ("Class#method"), or "DTO.field (@NotBlank) -> VALIDATED" (the
     * validator is run below), or "OWN REASON: why", "NOT A WITHDRAWAL: why",
     * "NOT A CLINICAL RECORD: why", or "KNOWN GAP (Dnnnn class, standing debt): why".
     * Round 20's appointment guard did not read the waitlist, the recalls or
     * the discharge requests, which all closed with an empty body.
     */
    private static final Map<String, String> CLINICAL_HANDLERS = new LinkedHashMap<>();

    static {
        CLINICAL_HANDLERS.put("POST /reception/waitlist/{id}/close",
            "CloseReasonRequestDTO.reason -> service/ReceptionServiceImplTest#aCloseWithoutAReasonIsRefused_D1157");
        CLINICAL_HANDLERS.put("POST /recalls/{id}/cancel",
            "CloseReasonRequestDTO.reason -> service/scheduling/PatientRecallServiceImplTest#aCancelWithoutAReasonIsRefused_D1157");
        CLINICAL_HANDLERS.put("POST /recalls/{id}/close",
            "NOT A WITHDRAWAL: closes the recall because the visit took place (or the need lapsed with it)");
        CLINICAL_HANDLERS.put("POST /discharge-approvals/{id}/cancel",
            "@RequestParam reason -> service/DischargeApprovalServiceImplTest#cancelWithoutAReasonIsRefused_D1160");
        CLINICAL_HANDLERS.put("POST /discharge-approvals/{id}/reject",
            "DischargeApprovalDecisionDTO.rejectionReason -> service/DischargeApprovalServiceImplTest#rejectWithoutAReasonIsRefused_D1160");
        CLINICAL_HANDLERS.put("POST /consultations/{consultationId}/cancel",
            "CancelConsultationRequestDTO.cancellationReason -> service/impl/ConsultationServiceImplTest#cancelConsultation_refusesABlankReason_D1098");
        CLINICAL_HANDLERS.put("POST /signatures/{signatureId}/revoke",
            "SignatureRevocationRequestDTO.revocationReason (@NotBlank) -> VALIDATED");
        CLINICAL_HANDLERS.put("POST /encounters/{encounterId}/close-unseen",
            "CloseUnseenVisitRequestDTO.reason (@NotBlank) -> VALIDATED");
        CLINICAL_HANDLERS.put("POST /referrals/{referralId}/reject",
            "RejectReferralRequestDTO.reason -> controller/GeneralReferralControllerTest#rejectReferral_blankReason_returns400");
        CLINICAL_HANDLERS.put("POST /referrals/{referralId}/cancel",
            "@RequestParam reason -> service/impl/GeneralReferralServiceImplTest#cancelReferral_refusesABlankReason_D1098");
        CLINICAL_HANDLERS.put("POST /isolation/precautions/{precautionId}/discontinue",
            "DiscontinuePrecautionRequestDTO.discontinuationReason -> service/IsolationServiceImplTest#liftingAPrecautionNeedsAReason");
        CLINICAL_HANDLERS.put("POST /lab-orders/{id}/void",
            "VoidLabOrderRequestDTO.reason -> service/LabOrderVoidTest#aVoidStatesWhy");
        CLINICAL_HANDLERS.put("POST /lab-specimens/{id}/reject",
            "RejectSpecimenRequestDTO.reason -> service/LabSpecimenRejectionTest#aReasonIsRequired");
        CLINICAL_HANDLERS.put("POST /referrals/obgyn/{id}/cancel",
            "ObgynReferralCancelRequestDTO.reason -> service/impl/ObgynReferralServiceImplTest#cancelReferral_refusesABlankReason_D1098");
        CLINICAL_HANDLERS.put("POST /procedure-orders/{orderId}/cancel",
            "@RequestParam cancellationReason -> service/impl/ProcedureOrderServiceImplTest#cancelProcedureOrder_refusesABlankReason_D1098");
        CLINICAL_HANDLERS.put("POST /transfers/{orderId}/cancel",
            "TransferCancellationRequestDTO.cancellationReason -> service/TransferServiceImplTest#cancellingNeedsAReasonBecauseTheBedWasHeldOutOfCirculation");
        CLINICAL_HANDLERS.put("POST /transfusions/requests/{requestId}/cancel",
            "@RequestParam reason -> service/TransfusionServiceImplTest#cancellingRequiresAReason");
        CLINICAL_HANDLERS.put("POST /patients/{patientId}/treatment-consents/{consentId}/revoke",
            "@RequestParam reason -> service/impl/TreatmentConsentServiceImplTest#revokeDemandsAReason");
        CLINICAL_HANDLERS.put("POST /ultrasound/orders/{orderId}/cancel",
            "@RequestParam cancellationReason -> service/UltrasoundServiceImplTest#cancelOrder_refusesABlankReason_D1098");
        CLINICAL_HANDLERS.put("PUT /me/patient/appointments/cancel",
            "OWN REASON: the patient cancels their own booking; optional on purpose (QA D1126)");
        CLINICAL_HANDLERS.put("PUT /me/patient/refills/{refillId}/cancel",
            "OWN REASON: the patient withdraws their own refill request before anyone acted on it");
        CLINICAL_HANDLERS.put("POST /slots/{id}/hold",
            "NOT A WITHDRAWAL: a temporary reservation of a free slot");
        CLINICAL_HANDLERS.put("PATCH /staff/scheduling/leaves/{leaveId}/cancel",
            "NOT A CLINICAL RECORD: a staff member's own leave request");
        CLINICAL_HANDLERS.put("POST /break-glass/{sessionId}/revoke",
            "NOT A CLINICAL RECORD: ends the caller's own emergency access early; the session's opening reason is required");
        CLINICAL_HANDLERS.put("PUT /api-keys/{keyId}/revoke", "NOT A CLINICAL RECORD: platform integration key");
        CLINICAL_HANDLERS.put("PUT /webhook-endpoints/{endpointId}/revoke", "NOT A CLINICAL RECORD: platform integration endpoint");
        CLINICAL_HANDLERS.put("POST /super-admin/hospitals/{hospitalId}/cancel-purge",
            "NOT A CLINICAL RECORD: stops a scheduled tenant purge (keeps data)");
        CLINICAL_HANDLERS.put("POST /super-admin/organizations/{organizationId}/cancel-purge",
            "NOT A CLINICAL RECORD: stops a scheduled tenant purge (keeps data)");
        CLINICAL_HANDLERS.put("POST /super-admin/providers/{providerId}/reject",
            "NOT A CLINICAL RECORD: provider onboarding; ProviderDecisionRequestDTO.reason is @NotBlank");
        CLINICAL_HANDLERS.put("POST /super-admin/providers/{providerId}/revoke",
            "NOT A CLINICAL RECORD: provider onboarding; ProviderDecisionRequestDTO.reason is @NotBlank");
        CLINICAL_HANDLERS.put("PUT /refills/{refillId}/reject",
            "KNOWN GAP (D1157 class, standing debt): the refill denial's providerNotes are optional; the portal's"
                + " refill screen sends none, so requiring them needs its UI half first");
        CLINICAL_HANDLERS.put("PUT /roi-requests/{requestId}/cancel",
            "KNOWN GAP (D1157 class, standing debt): RoiDecisionDTO.note is optional on cancel (deny requires it);"
                + " the ROI worklist dialog has no field yet");
        CLINICAL_HANDLERS.put("PUT /advance-directives/{id}/revoke",
            "CloseReasonRequestDTO.reason -> service/impl/AdvanceDirectiveServiceImplTest#aRevokeWithoutAReasonIsRefused_D1148");
    }

    /** QA D1159: a dose held or refused is not a path of its own; its rule is on the one MAR writer. */
    private static final String MAR_WITHHELD_PROOF =
        "service/impl/EmptyClinicalWriteRulesTest#aHeldOrRefusedDoseSaysWhy_D1159";

    @Test
    void everyClinicalWithdrawalIsListedWithItsReasonProof() throws Exception {
        List<String> found = new ArrayList<>();
        for (Path f : controllers()) {
            String rel = MAIN.relativize(f).toString().replace('\\', '/');
            if (isMoneyController(rel)) {
                continue;
            }
            Class<?> type = Class.forName("com.example.hms." + rel.replace('/', '.').replace(".java", ""));
            RequestMapping mapping = type.getAnnotation(RequestMapping.class);
            String base = first(mapping != null ? merge(mapping.value(), mapping.path()) : new String[0]);
            for (Method m : type.getDeclaredMethods()) {
                String[] verbAndPath = writeMapping(m);
                if (verbAndPath == null) {
                    continue;
                }
                String path = base + verbAndPath[1];
                if (CLINICAL_VOID_PATH.matcher(path).find()) {
                    found.add(verbAndPath[0] + " " + path);
                }
            }
        }
        assertThat(new TreeSet<>(found))
            .as("a handler that withdraws, closes or refuses a clinical or front-office record is not listed with"
                + " the test proving it refuses a blank reason (QA D1157), or a listed one is gone")
            .isEqualTo(new TreeSet<>(CLINICAL_HANDLERS.keySet()));
        for (Map.Entry<String, String> e : CLINICAL_HANDLERS.entrySet()) {
            String v = e.getValue();
            if (v.endsWith("-> VALIDATED")) {
                assertBlankIsRefusedByValidation(e.getKey(), v);
            } else if (v.contains(" -> ")) {
                assertProofExists(v.substring(v.indexOf(" -> ") + 4));
            } else {
                assertThat(v).as(e.getKey())
                    .matches("(OWN REASON|NOT A WITHDRAWAL|NOT A CLINICAL RECORD|KNOWN GAP \\(D\\d+ class, standing debt\\)): .+");
            }
        }
        assertProofExists(MAR_WITHHELD_PROOF);
    }

    private static void assertBlankIsRefusedByValidation(String handler, String entry) throws Exception {
        String dto = entry.substring(0, entry.indexOf('.'));
        String field = entry.substring(entry.indexOf('.') + 1, entry.indexOf(' '));
        try (jakarta.validation.ValidatorFactory factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            Object empty = findPayload(dto).getDeclaredConstructor().newInstance();
            assertThat(factory.getValidator().validate(empty))
                .as(handler + ": a blank " + field + " is refused by validation")
                .extracting(cv -> cv.getPropertyPath().toString()).contains(field);
        }
    }

    private static void assertProofExists(String proof) throws IOException {
        String[] parts = proof.split("#");
        Path file = TEST.resolve(parts[0] + ".java");
        assertThat(file).as(proof).exists();
        assertThat(Files.readString(file)).as(proof).containsPattern("void " + Pattern.quote(parts[1]) + "\\(");
    }

    /** {verb, first path} of a POST, PUT or PATCH mapping, or null. */
    private static String[] writeMapping(Method m) {
        PostMapping post = m.getAnnotation(PostMapping.class);
        if (post != null) {
            return new String[] {"POST", first(merge(post.value(), post.path()))};
        }
        PutMapping put = m.getAnnotation(PutMapping.class);
        if (put != null) {
            return new String[] {"PUT", first(merge(put.value(), put.path()))};
        }
        PatchMapping patch = m.getAnnotation(PatchMapping.class);
        if (patch != null) {
            return new String[] {"PATCH", first(merge(patch.value(), patch.path()))};
        }
        return null;
    }

    private static Class<?> findPayload(String simpleName) throws Exception {
        try (Stream<Path> files = Files.walk(MAIN.resolve("payload"))) {
            Path f = files.filter(p -> p.getFileName().toString().equals(simpleName + ".java")).findFirst()
                .orElseThrow(() -> new AssertionError("no payload class " + simpleName));
            String rel = MAIN.relativize(f).toString().replace('\\', '/').replace(".java", "");
            return Class.forName("com.example.hms." + rel.replace('/', '.'));
        }
    }

    private static List<Path> controllers() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN.resolve("controller"))) {
            return files.filter(f -> f.toString().endsWith(".java")).toList();
        }
    }

    private static String[] merge(String[] a, String[] b) {
        return a.length > 0 ? a : b;
    }

    private static String first(String[] values) {
        return values == null || values.length == 0 ? "" : values[0];
    }
}
