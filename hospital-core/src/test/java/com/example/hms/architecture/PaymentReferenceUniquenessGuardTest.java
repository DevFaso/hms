package com.example.hms.architecture;

import org.junit.jupiter.api.Test;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.SpringAsmInfo;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1129 (round 20, the D345 / D975 class): every main-code write of a
 * payment's provider reference — on an invoice or copay payment
 * ({@code PaymentTransaction}), a pharmacy dispense payment
 * ({@code PharmacyPayment}) or a walk-in sale ({@code PharmacySale}) — goes
 * through {@code PaymentReferenceRegistry.requireUnused}, which reads all
 * three ledgers. Round 19 refused a reused reference on invoices and copays
 * only, so the pharmacy checkout took a mobile-money reference already on an
 * invoice payment, and another patient's.
 *
 * <p>A bytecode scan finds every method that stores a reference. Each is
 * listed: "CHECKED" (its class calls the registry, verified here), or why it
 * does not record a new provider transaction. A new writer fails until it is
 * listed; a listed one that disappears fails until its line goes.
 */
class PaymentReferenceUniquenessGuardTest {

    private static final String REGISTRY = "com/example/hms/service/payment/PaymentReferenceRegistry";

    /** Owner (internal name) → the reference setter names on it. */
    private static final Map<String, Set<String>> REFERENCE_WRITES = Map.of(
        "com/example/hms/model/PaymentTransaction", Set.of("setReferenceNumber"),
        "com/example/hms/model/PaymentTransaction$PaymentTransactionBuilder", Set.of("referenceNumber"),
        "com/example/hms/model/pharmacy/PharmacyPayment", Set.of("setReferenceNumber"),
        "com/example/hms/model/pharmacy/PharmacyPayment$PharmacyPaymentBuilder", Set.of("referenceNumber"),
        "com/example/hms/model/pharmacy/PharmacySale", Set.of("setReferenceNumber"),
        "com/example/hms/model/pharmacy/PharmacySale$PharmacySaleBuilder", Set.of("referenceNumber"));

    /** "OuterClass.method" → "CHECKED" or why it records no new provider transaction. */
    private static final Map<String, String> WRITERS = new LinkedHashMap<>();

    static {
        WRITERS.put("BillingInvoiceServiceImpl.savePaymentRow",
            "CHECKED: every caller runs rejectReusedReference first (patient, staff and copay payments)");
        WRITERS.put("PharmacyPaymentServiceImpl.createPayment", "CHECKED: a typed mobile-money reference, before anything is charged");
        WRITERS.put("PharmacySaleServiceImpl.createSale", "CHECKED: a mobile-money walk-in sale");
        WRITERS.put("PharmacyPaymentMapper.toEntity",
            "VIA PharmacyPaymentServiceImpl.createPayment: the mapper copies the request; its only caller checks the"
                + " reference first and then sets the stored one itself");
    }

    @Test
    void everyPaymentReferenceWriteIsCheckedAcrossEveryLedger() throws IOException {
        Map<String, Set<String>> scan = scan();
        Set<String> writers = scan.keySet().stream()
            .filter(k -> !k.startsWith("#"))
            .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        assertThat(writers).as("payment reference writers found").hasSizeGreaterThanOrEqualTo(3)
            .as("a method that stores a payment's provider reference is not listed (QA D1129): route it through"
                + " PaymentReferenceRegistry.requireUnused, or say why it records no new transaction")
            .isEqualTo(new TreeSet<>(WRITERS.keySet()));
        Set<String> classesCallingRegistry = scan.getOrDefault("#registry", Set.of());
        for (Map.Entry<String, String> e : WRITERS.entrySet()) {
            if (e.getValue().startsWith("CHECKED")) {
                String owner = e.getKey().substring(0, e.getKey().indexOf('.'));
                assertThat(classesCallingRegistry).as(e.getKey() + " is listed CHECKED but its class never calls the registry")
                    .contains(owner);
            }
        }
    }

    /** Writer "Class.method" → targets; plus "#registry" → the classes that call requireUnused. */
    private static Map<String, Set<String>> scan() throws IOException {
        Map<String, Set<String>> found = new java.util.TreeMap<>();
        Resource[] all = new PathMatchingResourcePatternResolver().getResources("classpath*:com/example/hms/**/*.class");
        for (Resource resource : all) {
            if (!resource.getURL().toString().contains("/main/")) {
                continue;
            }
            String[] owner = new String[1];
            try (InputStream in = resource.getInputStream()) {
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
                                Set<String> setters = REFERENCE_WRITES.get(target);
                                boolean entityItself = target.endsWith("/" + owner[0])
                                    || target.contains("/" + owner[0] + "$");
                                if (setters != null && setters.contains(method) && !entityItself) {
                                    found.computeIfAbsent(caller, k -> new TreeSet<>()).add(target + "." + method);
                                }
                                if (REGISTRY.equals(target) && "requireUnused".equals(method)) {
                                    found.computeIfAbsent("#registry", k -> new TreeSet<>()).add(owner[0]);
                                }
                            }
                        };
                    }
                }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
        }
        return found;
    }

    private static String enclosingMethod(String name) {
        if (name.startsWith("lambda$")) {
            int end = name.indexOf('$', "lambda$".length());
            return end < 0 ? name : name.substring("lambda$".length(), end);
        }
        return name;
    }
}
