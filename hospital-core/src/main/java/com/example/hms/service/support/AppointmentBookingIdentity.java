package com.example.hms.service.support;

import com.example.hms.enums.AppointmentStatus;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.Appointment;
import com.example.hms.payload.dto.AppointmentRequestDTO;
import com.example.hms.payload.dto.portal.PortalBookAppointmentRequestDTO;
import com.example.hms.repository.AppointmentRepository;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/** Request identity, separate from clinical data. No raw patient text is persisted here. */
public record AppointmentBookingIdentity(String key, String fingerprint, boolean explicit,
                                         UUID patientId, UUID hospitalId) {

    public static AppointmentBookingIdentity staff(UUID actor, UUID patient, UUID hospital,
                                                   AppointmentRequestDTO r) {
        return identity("staff", actor, patient, hospital, r.getBookingRequestId(),
            hash(r.getAppointmentDate(), r.getStartTime(), r.getStaffId(), r.getStaffEmail(),
                r.getStaffUsername(), r.getDepartmentId(), r.getDepartmentName(), r.getDepartmentCode(),
                r.getRecallId(), r.getStatus(), r.getReason(), r.getNotes(),
                r.getStatusReason(), r.getOverbookReason()));
    }

    public static AppointmentBookingIdentity portal(UUID actor, UUID patient, PortalBookAppointmentRequestDTO r) {
        return identity("portal", actor, patient, r.getHospitalId(), r.getBookingRequestId(),
            hash(r.getDepartmentId(), r.getStaffId(), r.getDate(), r.getStartTime(), r.getReason(), r.getNotes()));
    }

    private static AppointmentBookingIdentity identity(String route, UUID actor, UUID patient, UUID hospital,
                                                        UUID requestId, String payload) {
        String fingerprint = hash(patient, payload);
        return new AppointmentBookingIdentity(hash(route, actor, hospital,
            requestId == null ? fingerprint : requestId), fingerprint, requestId != null, patient, hospital);
    }

    public Optional<Appointment> replay(AppointmentRepository repository) {
        return repository.findAllByBookingRequestKey(key).stream()
            .filter(a -> a.isBookingRequestDurable() || AppointmentStatus.holdsSlot(a.getStatus()))
            .findFirst()
            .map(a -> {
                // An edit can transfer a visit to another patient or hospital. The old
                // request must never disclose the transferred record or create a replacement.
                if (!fingerprint.equals(a.getBookingRequestFingerprint())
                        || a.getPatient() == null || !patientId.equals(a.getPatient().getId())
                        || a.getHospital() == null || !hospitalId.equals(a.getHospital().getId())) {
                    throw new FieldBusinessException("bookingRequestId", "appointment.booking.requestChanged");
                }
                return a;
            });
    }

    public void stamp(Appointment appointment) {
        appointment.setBookingRequestKey(key);
        appointment.setBookingRequestFingerprint(fingerprint);
        // A legacy request initially creating an already-closed row must also
        // deduplicate forever; cancellation of a formerly-live legacy booking may rebook.
        appointment.setBookingRequestDurable(explicit || !AppointmentStatus.holdsSlot(appointment.getStatus()));
    }

    private static String hash(Object... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object value : values) {
                byte[] bytes = value == null ? new byte[0] : value.toString().getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value == null ? -1 : bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("error.unexpected", ex);
        }
    }
}
