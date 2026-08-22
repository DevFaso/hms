package com.example.hms.service;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.LabSpecimen;
import com.example.hms.model.Patient;
import com.example.hms.repository.LabSpecimenRepository;
import com.example.hms.repository.PatientRepository;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Wristband + specimen label PDFs (P3 #23b). PDFBox + zxing were already
 * on the classpath (QR-only until now); no new dependency.
 *
 * <p>HARD CONSTRAINT: the wristband QR encodes the BARE patient UUID —
 * the eMAR five-rights patient check does {@code UUID.fromString} on the
 * raw scan and compares to {@code patient.getId()}
 * (FiveRightsVerificationService), so any prefix (the specimens' "LAB-"
 * convention included) would break bedside verification. The MRN is
 * printed human-readable only. QR is also the right symbology: the only
 * in-app camera scanner decodes qr_code/code_128, and a 36-char payload
 * as Code 128 would be impractically wide.
 */
@Service
@RequiredArgsConstructor
public class WristbandPdfService {

    /** 3.5in x 1.1in label media, in PDF points. */
    private static final float LABEL_WIDTH = 252f;
    private static final float LABEL_HEIGHT = 79f;

    private final PatientRepository patientRepository;
    private final LabSpecimenRepository specimenRepository;

    @Transactional(readOnly = true)
    public byte[] generateWristbandPdf(UUID patientId, UUID hospitalId) {
        Patient patient = patientRepository.findById(patientId)
            .orElseThrow(() -> new ResourceNotFoundException("Patient not found with ID: " + patientId));
        // 404-not-403: a scoped caller printing for an unregistered patient
        // learns nothing.
        if (hospitalId != null && !patient.isRegisteredInHospital(hospitalId)) {
            throw new ResourceNotFoundException("Patient not found with ID: " + patientId);
        }
        String mrn = hospitalId != null ? patient.getMrnForHospital(hospitalId) : null;

        return renderLabel(cs -> {
            writeText(cs, 11, 8, LABEL_HEIGHT - 16,
                truncate(safe(patient.getFirstName()) + " " + safe(patient.getLastName()), 28));
            writeText(cs, 8, 8, LABEL_HEIGHT - 30,
                "DOB: " + (patient.getDateOfBirth() != null ? patient.getDateOfBirth() : "—"));
            writeText(cs, 8, 8, LABEL_HEIGHT - 42, "MRN: " + (mrn != null ? mrn : "—"));
            writeText(cs, 6, 8, 8, "ID: " + patient.getId());
        }, wristbandQrPayload(patient));
    }

    @Transactional(readOnly = true)
    public byte[] generateSpecimenLabelPdf(UUID specimenId, UUID hospitalId) {
        LabSpecimen specimen = specimenRepository.findById(specimenId)
            .orElseThrow(() -> new ResourceNotFoundException("Specimen not found with ID: " + specimenId));
        UUID orderHospitalId = specimen.getLabOrder() != null && specimen.getLabOrder().getHospital() != null
            ? specimen.getLabOrder().getHospital().getId()
            : null;
        if (hospitalId != null && !Objects.equals(orderHospitalId, hospitalId)) {
            throw new ResourceNotFoundException("Specimen not found with ID: " + specimenId);
        }
        Patient patient = specimen.getLabOrder() != null ? specimen.getLabOrder().getPatient() : null;
        String patientLine = patient != null
            ? truncate(safe(patient.getFirstName()) + " " + safe(patient.getLastName()), 28)
            : "—";
        // The stored barcode_value ("LAB-" + accession) is what downstream
        // lab tooling expects — first surface to ever render it.
        String qrPayload = specimen.getBarcodeValue() != null
            ? specimen.getBarcodeValue()
            : specimen.getAccessionNumber();

        return renderLabel(cs -> {
            writeText(cs, 10, 8, LABEL_HEIGHT - 16, safe(specimen.getAccessionNumber()));
            writeText(cs, 8, 8, LABEL_HEIGHT - 30, patientLine);
            writeText(cs, 8, 8, LABEL_HEIGHT - 42,
                truncate(safe(specimen.getSpecimenType()), 22)
                    + (specimen.getCollectedAt() != null ? "  " + specimen.getCollectedAt() : ""));
        }, qrPayload);
    }

    /** Package-visible so the test can pin the bare-UUID contract. */
    String wristbandQrPayload(Patient patient) {
        return String.valueOf(patient.getId());
    }

    /* ── Rendering ─────────────────────────────────────────────────────── */

    @FunctionalInterface
    private interface LabelText {
        void write(PDPageContentStream cs) throws IOException;
    }

    private byte[] renderLabel(LabelText textBlock, String qrPayload) {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(LABEL_WIDTH, LABEL_HEIGHT));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                textBlock.write(cs);
                byte[] qrPng = buildQrPng(qrPayload);
                PDImageXObject qr = PDImageXObject.createFromByteArray(doc, qrPng, "qr");
                float qrSize = LABEL_HEIGHT - 12f;
                cs.drawImage(qr, LABEL_WIDTH - qrSize - 6f, 6f, qrSize, qrSize);
            }
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                doc.save(baos);
                return baos.toByteArray();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to generate label PDF", e);
        }
    }

    private static void writeText(PDPageContentStream cs, int size, float x, float y, String text)
            throws IOException {
        cs.beginText();
        cs.setFont(PDType1Font.HELVETICA_BOLD, size);
        cs.newLineAtOffset(x, y);
        cs.showText(text != null ? text : "");
        cs.endText();
    }

    private static byte[] buildQrPng(String content) {
        try {
            var hints = Map.of(EncodeHintType.MARGIN, 1);
            var matrix = new MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, 220, 220, hints);
            var img = MatrixToImageWriter.toBufferedImage(matrix);
            var baos = new ByteArrayOutputStream();
            ImageIO.write(img, "PNG", baos);
            return baos.toByteArray();
        } catch (com.google.zxing.WriterException | IOException e) {
            throw new IllegalStateException("QR generation failed", e);
        }
    }

    private static String safe(String v) {
        return v == null ? "" : v;
    }

    private static String truncate(String s, int n) {
        return s != null && s.length() > n ? s.substring(0, n - 1) + "…" : s;
    }
}
