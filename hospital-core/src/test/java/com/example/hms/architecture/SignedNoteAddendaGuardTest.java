package com.example.hms.architecture;

import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D2260 (r63, High): an addendum to a signed note was saved (201) and shown nowhere: not the visit, the
 * chart review, the record PDF or the after-visit summary. A signed note is locked, so its addendum is the
 * only way to correct it, and a reader that shows the note without its addenda shows a record that is wrong.
 *
 * <p>Two guards, so a new reader cannot leave them out:
 * <ol>
 *   <li>Every response DTO that carries a note to a reader (its name says Note, it has {@code signedAt}) has an
 *       addenda list; so does every reader DTO in {@link #NOTE_CARRIERS} that shows a note's content without
 *       "Note" in its name.</li>
 *   <li>Every main source file that reads the note entities ({@code EncounterNote}, {@code NursingNote}) is
 *       either a reader that handles addenda (it names them) or is listed in {@link #NOT_A_READER} with why it
 *       shows no note text; the list holds no stale entry.</li>
 * </ol>
 */
class SignedNoteAddendaGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");
    private static final Path DTO_ROOT = MAIN.resolve("payload/dto");

    private static final Pattern NOTE_ENTITY = Pattern.compile("\\b(?:EncounterNote|NursingNote)\\b");
    /** The note's own addenda read: {@code NoteAddenda.of(note)}, {@code note.getAddenda()} or a builder's {@code .addenda(}. */
    private static final Pattern ADDENDA = Pattern.compile("\\bNoteAddenda\\.(?:of|signedOnly)\\(|\\bgetAddenda\\(\\)|\\.addenda\\(");

    /** Reader DTOs that show a signed note's content without "Note" in their name: class → addenda field. */
    private static final Map<String, String> NOTE_CARRIERS = Map.of(
        "com.example.hms.payload.dto.clinical.AfterVisitSummaryDTO", "noteAddenda");

    /** File → why it reads a note entity and shows none of its text to a reader. */
    private static final Map<String, String> NOT_A_READER = Map.ofEntries(
        Map.entry("SmartPhraseController.java", "documentation only: phrases are typed into the note form"),
        Map.entry("RestrictedChartGate.java", "QA D3863: reads only whose chart a nursing note is in, never its text"),
        Map.entry("SmartPhraseService.java", "documentation only: expands phrases, reads no note"),
        Map.entry("CheckOutMapper.java",
            "copies the signed note's plan and instructions into the stored discharge summary at checkout; "
                + "the patient reads the note's addenda in the visit document (AfterVisitSummarySections)"),
        Map.entry("DischargeSummaryServiceImpl.java",
            "the same copy at discharge, and whether a note still awaits its signature"),
        Map.entry("DoctorWorklistServiceImpl.java", "counts the doctor's unsigned notes (QA D2261)"),
        Map.entry("PatientServiceImpl.java", "maps nursing notes through NursingNoteMapper.toResponse, which carries addenda"),
        Map.entry("NursingNoteServiceImpl.java", "maps every note through NursingNoteMapper.toResponse, which carries addenda"),
        Map.entry("PatientSnapshotServiceImpl.java", "a comment names the entity; no note is read"),
        Map.entry("UnsignedNoteFollowUp.java", "files and clears the author's 'note to sign' task"),
        Map.entry("NurseTaskServiceImpl.java", "writes a nursing note; reads none back"),
        Map.entry("SensitivityClassifier.java", "classifies a note's sensitivity"),
        Map.entry("SensitivityClassifierImpl.java", "classifies a note's sensitivity"),
        Map.entry("SignableReportFinder.java", "finds a note to sign by id; shows no text"));

    @Test
    void everyNoteResponseDtoCarriesItsAddenda() throws IOException, ClassNotFoundException {
        List<String> missing = new ArrayList<>();
        int checked = 0;
        for (String className : dtoClassNames()) {
            Class<?> type = Class.forName(className);
            for (Class<?> candidate : withNested(type)) {
                String simple = candidate.getSimpleName();
                // A Lombok builder and an abstract base are not what a reader receives; their concrete DTOs are.
                boolean reader = simple.contains("Note") && !simple.contains("Request") && !simple.contains("Addend")
                    && !simple.endsWith("Builder") && !Modifier.isAbstract(candidate.getModifiers())
                    && hasField(candidate, "signedAt");
                if (!reader) {
                    continue;
                }
                checked++;
                if (!hasListField(candidate, "addenda") && !hasListField(candidate, "addendums")) {
                    missing.add(candidate.getName());
                }
            }
        }
        for (Map.Entry<String, String> carrier : NOTE_CARRIERS.entrySet()) {
            checked++;
            if (!hasListField(Class.forName(carrier.getKey()), carrier.getValue())) {
                missing.add(carrier.getKey() + "#" + carrier.getValue());
            }
        }
        assertThat(checked).as("the scan found the note reader DTOs").isGreaterThanOrEqualTo(5);
        assertThat(missing)
            .as("a note shown to a reader carries its addenda (QA D2260): add an addenda list")
            .isEmpty();
    }

    @Test
    void everySourceThatReadsANoteShowsItsAddendaOrSaysWhyNot() throws IOException {
        List<String> missing = new ArrayList<>();
        Set<String> seen = new TreeSet<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String rel = MAIN.relativize(file).toString().replace('\\', '/');
                if (rel.startsWith("model/") || rel.startsWith("repository/")) {
                    continue;
                }
                String raw = Files.readString(file, StandardCharsets.UTF_8);
                String name = file.getFileName().toString();
                if (!NOTE_ENTITY.matcher(raw).find()) {
                    continue;
                }
                if (NOT_A_READER.containsKey(name)) {
                    seen.add(name);
                    continue;
                }
                if (!ADDENDA.matcher(SourceScan.stripComments(raw)).find()) {
                    missing.add(rel);
                }
            }
        }
        assertThat(missing)
            .as("a reader of a note shows its addenda (QA D2260): use NoteAddenda.of(note), "
                + "or list the file in NOT_A_READER with why it shows no note text")
            .isEmpty();
        assertThat(seen).as("NOT_A_READER holds no stale entry").containsExactlyInAnyOrderElementsOf(NOT_A_READER.keySet());
    }

    /**
     * QA D2260 review: an addendum saved without its author's attestation has no signedAt, and the
     * after-visit summary is the patient's copy (the portal reads /encounters/{id}/avs). Every source
     * that fills a patient copy's addenda ({@code setNoteAddenda(}) takes them from
     * {@code NoteAddenda.signedOnly(}, never {@code NoteAddenda.of(}.
     */
    @Test
    void aPatientCopyCarriesSignedAddendaOnly() throws IOException {
        List<String> missing = new ArrayList<>();
        int copies = 0;
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String code = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                if (!code.contains("setNoteAddenda(") || file.toString().contains("payload")) {
                    continue;
                }
                String rel = MAIN.relativize(file).toString().replace('\\', '/');
                // Clearing it (the desk's shaping) gives the patient nothing; not a fill.
                if (code.replace("setNoteAddenda(null)", "").contains("setNoteAddenda(")) {
                    copies++;
                    if (!code.contains("NoteAddenda.signedOnly(") || code.contains("NoteAddenda.of(")) {
                        missing.add(rel);
                    }
                }
            }
        }
        assertThat(copies).as("the scan found the after-visit summary").isPositive();
        assertThat(missing)
            .as("a patient copy carries attested (signed) addenda only: use NoteAddenda.signedOnly(note)")
            .isEmpty();
    }

    /** A patient- or proxy-facing read, by the house naming (the D1910 and D2730 guards use the same). */
    private static final Pattern CALLED_OR_DECLARED = Pattern.compile("\\b(\\w++)\\s*+\\(");

    private static boolean isPatientRead(String name) {
        return name.startsWith("toPatientPortal") || name.endsWith("ForPortalPatient")
            || name.contains("ForPatientPortal") || name.endsWith("ForProxy");
    }

    /** What hands a reader a raw note or every addendum. */
    private static final Pattern RAW_NOTE = Pattern.compile(
        "\\btoEncounterNoteResponseDTO\\(|\\bNoteAddenda\\.of\\(|\\bgetAddenda\\(\\)");

    /** The patient copy: the note shaped by PatientSafeView, or the signed addenda only. */
    private static final Pattern PATIENT_COPY = Pattern.compile(
        "\\bPatientSafeView\\.encounterNote\\(|\\bNoteAddenda\\.signedOnly\\(");

    /**
     * QA D2550 (r65): /me/patient/encounters sent the full note with its UNATTESTED addendum to the patient's
     * browser; only the screen hid it. Every patient- or proxy-facing read that maps a note, or a note's
     * addenda, sends the patient copy ({@code PatientSafeView.encounterNote}, {@code NoteAddenda.signedOnly});
     * and no controller under /me/patient maps a note itself.
     */
    @Test
    void aPatientOrProxyReadSendsThePatientCopyOfANote() throws IOException {
        List<String> missing = new ArrayList<>();
        Set<String> readers = new TreeSet<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String rel = MAIN.relativize(file).toString().replace('\\', '/');
                if (rel.startsWith("payload/") || rel.startsWith("repository/") || rel.startsWith("model/")) {
                    continue;
                }
                String code = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                if (rel.startsWith("controller/") && code.contains("\"/me/patient") && RAW_NOTE.matcher(code).find()) {
                    missing.add(rel + " (a /me/patient controller maps a note itself)");
                }
                java.util.regex.Matcher m = CALLED_OR_DECLARED.matcher(code);
                while (m.find()) {
                    if (!isPatientRead(m.group(1))) {
                        continue;
                    }
                    String body = SourceScan.methodBody(code, m.group(1));
                    if (body == null || !RAW_NOTE.matcher(body).find()) {
                        continue;
                    }
                    readers.add(rel + "#" + m.group(1));
                    if (!PATIENT_COPY.matcher(body).find()) {
                        missing.add(rel + "#" + m.group(1));
                    }
                }
            }
        }
        assertThat(readers).as("the scan found the patient's visit mapper")
            .contains("mapper/EncounterMapper.java#toPatientPortalEncounterResponseDTO");
        assertThat(missing)
            .as("a patient or proxy read sends the patient copy of a note (QA D2550): wrap it in"
                + " PatientSafeView.encounterNote(...) or take NoteAddenda.signedOnly(note)")
            .isEmpty();
    }

    private static List<String> dtoClassNames() throws IOException {
        List<String> names = new ArrayList<>();
        try (Stream<Path> files = Files.walk(DTO_ROOT)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String rel = Path.of("src/main/java").relativize(file).toString().replace('\\', '/');
                names.add(rel.substring(0, rel.length() - ".java".length()).replace('/', '.'));
            }
        }
        return names;
    }

    private static List<Class<?>> withNested(Class<?> type) {
        List<Class<?>> all = new ArrayList<>();
        all.add(type);
        for (Class<?> nested : type.getDeclaredClasses()) {
            if (Modifier.isStatic(nested.getModifiers())) {
                all.addAll(withNested(nested));
            }
        }
        return all;
    }

    private static boolean hasField(Class<?> type, String name) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getName().equals(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasListField(Class<?> type, String name) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getName().equals(name) && List.class.isAssignableFrom(f.getType())) {
                    return true;
                }
            }
        }
        return false;
    }
}
