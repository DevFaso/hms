package com.example.hms.service;

import com.example.hms.payload.dto.medicalhistory.ImmunizationResponseDTO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class ImmunizationCertificatePdfService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("MMMM d, yyyy");
    private static final String EM_DASH = "\u2014";
    private static final float MARGIN = 50f;
    private static final float LINE_HEIGHT = 15f;

    public byte[] generate(String patientName, List<ImmunizationResponseDTO> immunizations) {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                float pageWidth = page.getMediaBox().getWidth();
                float y = page.getMediaBox().getHeight() - MARGIN;

                // Title
                writeText(cs, PDType1Font.HELVETICA_BOLD, 18,
                        centered(pageWidth, "IMMUNIZATION CERTIFICATE", 18), y, "IMMUNIZATION CERTIFICATE");
                y -= 24;
                writeText(cs, PDType1Font.HELVETICA, 11,
                        centered(pageWidth, "Official Health Record", 11), y, "Official Health Record");
                y -= 8;
                drawLine(cs, MARGIN, y, pageWidth - MARGIN, y);
                y -= 18;

                // Patient info
                writeText(cs, PDType1Font.HELVETICA_BOLD, 12, MARGIN, y, "Patient: ");
                writeText(cs, PDType1Font.HELVETICA, 12, MARGIN + 52, y, safe(patientName));
                y -= LINE_HEIGHT;
                writeText(cs, PDType1Font.HELVETICA_BOLD, 12, MARGIN, y, "Print Date: ");
                writeText(cs, PDType1Font.HELVETICA, 12, MARGIN + 68, y, LocalDate.now().format(DATE_FMT));
                y -= 8;
                drawLine(cs, MARGIN, y, pageWidth - MARGIN, y);
                y -= 18;

                // Table header
                writeText(cs, PDType1Font.HELVETICA_BOLD, 10, MARGIN, y, "Vaccine");
                writeText(cs, PDType1Font.HELVETICA_BOLD, 10, 200, y, "Date Administered");
                writeText(cs, PDType1Font.HELVETICA_BOLD, 10, 340, y, "Dose");
                writeText(cs, PDType1Font.HELVETICA_BOLD, 10, 380, y, "Lot #");
                writeText(cs, PDType1Font.HELVETICA_BOLD, 10, 460, y, "Manufacturer");
                y -= 4;
                drawLine(cs, MARGIN, y, pageWidth - MARGIN, y);
                y -= LINE_HEIGHT;

                // Rows
                for (ImmunizationResponseDTO imm : immunizations) {
                    if (y < 80) break; // stop at page boundary
                    String vaccineName = safe(imm.getVaccineDisplay() != null
                            ? imm.getVaccineDisplay() : imm.getVaccineCode());
                    String dateStr = imm.getAdministrationDate() != null
                            ? imm.getAdministrationDate().format(DateTimeFormatter.ofPattern("MM/dd/yyyy"))
                            : EM_DASH;
                    String dose = (imm.getDoseNumber() != null && imm.getTotalDosesInSeries() != null)
                            ? imm.getDoseNumber() + " of " + imm.getTotalDosesInSeries()
                            : (imm.getDoseNumber() != null ? String.valueOf(imm.getDoseNumber()) : EM_DASH);

                    writeText(cs, PDType1Font.HELVETICA, 9, MARGIN, y, truncate(vaccineName, 28));
                    writeText(cs, PDType1Font.HELVETICA, 9, 200, y, dateStr);
                    writeText(cs, PDType1Font.HELVETICA, 9, 340, y, dose);
                    writeText(cs, PDType1Font.HELVETICA, 9, 380, y, truncate(safe(imm.getLotNumber()), 10));
                    writeText(cs, PDType1Font.HELVETICA, 9, 460, y, truncate(safe(imm.getManufacturer()), 15));
                    y -= LINE_HEIGHT;
                }

                y -= 10;
                drawLine(cs, MARGIN, y, pageWidth - MARGIN, y);
                y -= 20;
                writeText(cs, PDType1Font.HELVETICA, 9, MARGIN, y,
                        "Total vaccines recorded: " + immunizations.size());
                y -= 25;
                writeText(cs, PDType1Font.HELVETICA, 8, MARGIN, y,
                        "This certificate is generated from the Hospital Management System for informational purposes.");
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to generate immunization certificate PDF", e);
        }
    }

    private static void writeText(PDPageContentStream cs, PDType1Font font, int size,
                                   float x, float y, String text) throws IOException {
        if (text == null || text.isBlank()) return;
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(text);
        cs.endText();
    }

    private static void drawLine(PDPageContentStream cs, float x1, float y1,
                                  float x2, float y2) throws IOException {
        cs.moveTo(x1, y1);
        cs.lineTo(x2, y2);
        cs.stroke();
    }

    private static float centered(float pageWidth, String text, int fontSize) {
        return (pageWidth / 2f) - (text.length() * fontSize * 0.28f);
    }

    private static String safe(String s) {
        return s != null ? s : EM_DASH;
    }

    private static String truncate(String s, int max) {
        if (s == null) return EM_DASH;
        return s.length() > max ? s.substring(0, max - 1) + "\u2026" : s;
    }
}

