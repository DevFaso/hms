package com.example.hms.architecture;

import com.example.hms.model.medication.ApprovedContent;
import com.example.hms.model.medication.MedicationCatalogApprovedWrite;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.repository.MedicationCatalogItemRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.SpringAsmInfo;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Go-live D2 (V385): no write path changes an approved formulary entry or dose limit in place.
 *
 * <p>The class this guards: an edit screen, an importer, a mapper or a bulk statement that writes the name, the
 * strength, the controlled flag or a dose limit straight onto {@code clinical.medication_catalog_items}, so that
 * what a prescriber picks and what the dose check judges changed with no version, no second person and no
 * history. Read from the COMPILED classes, so a call is found whatever the variable is named:
 * <ul>
 *   <li>every field of {@link MedicationCatalogItem} is either {@link ApprovedContent}, or listed here as edited
 *       directly, with the reason: a new column fails until someone decides which it is;</li>
 *   <li>the setter of an {@link ApprovedContent} field is called by {@link MedicationCatalogApprovedWrite} alone;</li>
 *   <li>the builder of the entry sets such a field only in the classes listed here, which build a limit for one
 *       evaluation and never save it;</li>
 *   <li>{@link MedicationCatalogApprovedWrite} is called by the approval step alone;</li>
 *   <li>the catalogue is saved by the catalogue service and the approval step alone, and no JPQL or SQL in the
 *       code updates or deletes its rows in bulk.</li>
 * </ul>
 */
@DisplayName("An approved formulary entry or dose limit is never changed in place (V385)")
class MedicationCatalogApprovedWriteGuardTest {

    private static final String ENTRY = MedicationCatalogItem.class.getName();
    private static final String ENTRY_BUILDER = ENTRY + "$MedicationCatalogItemBuilder";
    private static final String APPROVED_WRITE = MedicationCatalogApprovedWrite.class.getName();
    private static final String APPROVAL_STEP = "com.example.hms.service.formulary.MedicationCatalogVersionService";
    private static final String CATALOGUE_SERVICE = "com.example.hms.service.impl.MedicationCatalogItemServiceImpl";

    /** Fields of the entry that are NOT approved content: edited directly, with the reason. */
    private static final Map<String, String> EDITED_DIRECTLY = Map.of(
        "code", "the formulary code set by imports and HL7 (D654); an identifier, never edited from a screen",
        "category", "the therapeutic category: a label for browsing, read by no prescribing or dispensing rule",
        "essentialList", "whether the medicine is on the national essential list: information, read by no rule",
        "description", "free notes for the catalogue's readers",
        "sensitivityCategory", "the privacy tag a proxy's read obeys (policy 2026-11-20): its own audited decision",
        "hospital", "the owner of the entry: set once, when the approved entry is created");

    /**
     * Classes that build a catalogue entry in memory with approved content: a published default or a merged
     * limit for ONE dose check, never saved and never linked to an order.
     */
    private static final Map<String, String> BUILT_NEVER_SAVED = Map.of(
        "com.example.hms.cdshooks.rules.ArtemetherLumefantrineWhoBands",
            "the WHO weight bands standing in for a catalogue with no limit: one evaluation's default",
        "com.example.hms.cdshooks.rules.ParacetamolPaediatricDefault",
            "the registered paracetamol default standing in for a catalogue with no limit",
        "com.example.hms.cdshooks.rules.ParacetamolFactsReader",
            "the limits a free-text brand is judged against (QA D1462): not a catalogue row");

    private static final Pattern BULK_WRITE = Pattern.compile(
        "(?i)\\b(update|delete\\s+from)\\s+(MedicationCatalogItem\\b|clinical\\.medication_catalog_items\\b)");

    /** One call found in a compiled class of the application. */
    private record Call(String from, String owner, String method) {
    }

    private static final List<Call> CALLS = new ArrayList<>();

    @BeforeAll
    static void readTheCompiledClasses() throws IOException {
        Resource[] classes = new PathMatchingResourcePatternResolver()
            .getResources("classpath*:com/example/hms/**/*.class");
        for (Resource resource : classes) {
            String path = resource.getURL().getPath();
            if (path.contains("/test/") || path.contains("test-classes")) {
                continue;
            }
            try (InputStream in = resource.getInputStream()) {
                ClassReader reader = new ClassReader(in);
                String from = outer(reader.getClassName().replace('/', '.'));
                reader.accept(new ClassVisitor(SpringAsmInfo.ASM_VERSION) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                                     String[] exceptions) {
                        return new MethodVisitor(SpringAsmInfo.ASM_VERSION) {
                            @Override
                            public void visitMethodInsn(int opcode, String owner, String called, String desc,
                                                        boolean isInterface) {
                                CALLS.add(new Call(from, owner.replace('/', '.'), called));
                            }
                        };
                    }
                }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
        }
    }

    /** A lambda or an inner class writes for the class that holds it. */
    private static String outer(String className) {
        int inner = className.indexOf('$');
        return inner < 0 ? className : className.substring(0, inner);
    }

    private static List<Field> entryFields() {
        return Arrays.stream(MedicationCatalogItem.class.getDeclaredFields())
            .filter(field -> !Modifier.isStatic(field.getModifiers()) && !field.isSynthetic())
            .toList();
    }

    private static Set<String> approvedFields() {
        return entryFields().stream().filter(field -> field.isAnnotationPresent(ApprovedContent.class))
            .map(Field::getName).collect(Collectors.toCollection(TreeSet::new));
    }

    private static String setterOf(String field) {
        return "set" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
    }

    private static Set<String> callersOf(String owner, Set<String> methods) {
        return CALLS.stream()
            .filter(call -> call.owner().equals(owner) && methods.contains(call.method()))
            .map(Call::from)
            .filter(from -> !from.equals(ENTRY))
            .collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    @DisplayName("every field of the catalogue entry is approved content, or edited directly for a stated reason")
    void everyFieldIsClassified() {
        List<String> unclassified = new ArrayList<>();
        for (Field field : entryFields()) {
            boolean approved = field.isAnnotationPresent(ApprovedContent.class);
            boolean direct = EDITED_DIRECTLY.containsKey(field.getName());
            if (approved == direct) {
                unclassified.add(field.getName() + (approved ? " is both" : " is neither"));
            }
        }
        assertThat(unclassified)
            .as("a field of MedicationCatalogItem is @ApprovedContent (it changes through an approved version) "
                + "or listed in EDITED_DIRECTLY with the reason, never both and never neither")
            .isEmpty();
        // What the task names: the entry a prescriber picks, and every limit the dose check reads.
        assertThat(approvedFields()).contains("nameFr", "genericName", "atcCode", "form", "strength", "strengthUnit",
            "route", "controlled", "active", "pediatricMaxDoseMgPerKg", "pediatricMaxDailyMgPerKg",
            "pediatricMaxSingleDoseMg", "pediatricMaxDailyMg", "pediatricWeightBands", "pediatricDoseStatus");
    }

    @Test
    @DisplayName("only MedicationCatalogApprovedWrite sets approved content on a catalogue entry")
    void onlyTheApprovedWriteSetsApprovedContent() {
        Set<String> setters = approvedFields().stream().map(MedicationCatalogApprovedWriteGuardTest::setterOf)
            .collect(Collectors.toSet());

        // The scan sees the calls it is looking for: an empty result would prove nothing.
        assertThat(CALLS).as("compiled classes read").hasSizeGreaterThan(10_000);
        assertThat(callersOf(ENTRY, setters))
            .as("a setter of @ApprovedContent called outside MedicationCatalogApprovedWrite changes an approved "
                + "formulary entry or dose limit in place: propose a MedicationCatalogVersion instead")
            .containsExactly(APPROVED_WRITE);
    }

    @Test
    @DisplayName("the builder sets approved content only where the entry is built for one evaluation and never saved")
    void theBuilderSetsApprovedContentOnlyInMemory() {
        Set<String> builders = callersOf(ENTRY_BUILDER, approvedFields());

        assertThat(builders)
            .as("a catalogue entry built with approved content outside BUILT_NEVER_SAVED could be saved over an "
                + "approved row: create it through MedicationCatalogApprovedWrite.newEntry")
            .isSubsetOf(BUILT_NEVER_SAVED.keySet());
        assertThat(BUILT_NEVER_SAVED.keySet()).as("stale entries of BUILT_NEVER_SAVED").isSubsetOf(builders);
    }

    @Test
    @DisplayName("MedicationCatalogApprovedWrite is called by the approval step alone")
    void theApprovedWriteIsCalledByTheApprovalStepAlone() {
        Set<String> callers = CALLS.stream()
            .filter(call -> call.owner().equals(APPROVED_WRITE))
            .map(Call::from)
            .filter(from -> !from.equals(APPROVED_WRITE))
            .collect(Collectors.toCollection(TreeSet::new));

        assertThat(callers).containsExactly(APPROVAL_STEP);
    }

    @Test
    @DisplayName("the catalogue is saved by its service and the approval step alone, and nothing saves a built entry")
    void theCatalogueIsSavedInTwoPlaces() {
        Set<String> writes = Set.of("save", "saveAll", "saveAndFlush", "saveAllAndFlush", "delete", "deleteById",
            "deleteAll", "deleteAllById", "deleteAllInBatch", "deleteAllByIdInBatch", "deleteInBatch");
        Set<String> savers = callersOf(MedicationCatalogItemRepository.class.getName(), writes);

        assertThat(savers).containsExactlyInAnyOrder(APPROVAL_STEP, CATALOGUE_SERVICE);
        assertThat(savers).doesNotContainAnyElementsOf(BUILT_NEVER_SAVED.keySet());
    }

    @Test
    @DisplayName("no JPQL or SQL in the code updates or deletes catalogue rows in bulk")
    void noBulkStatementWritesTheCatalogue() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java/com/example/hms"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (BULK_WRITE.matcher(Files.readString(file, StandardCharsets.UTF_8)).find()) {
                    offenders.add(file.toString());
                }
            }
        }
        assertThat(offenders)
            .as("a bulk UPDATE or DELETE on the medication catalogue bypasses the approved version")
            .isEmpty();
    }
}
