package com.example.hms.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.Handle;
import org.springframework.asm.Label;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import org.springframework.asm.SpringAsmInfo;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D304 / D800: a patient holds at most one open stay. The rule is a check
 * ({@code AdmissionRepository.existsOpenAdmissionForPatient}) in each code path
 * that opens a stay, so this guard finds those paths in the bytecode and
 * fails when one appears without the check:
 * <ul>
 *   <li>every main class that constructs an {@code Admission} (a {@code new},
 *       or a constructor reference) is one of {@link #CREATORS} and calls the
 *       check;</li>
 *   <li>no main code reopens a stay: {@code Admission.activate()} has no
 *       caller, and {@code setStatus} with an open status (PENDING, ACTIVE,
 *       ON_LEAVE, AWAITING_DISCHARGE) happens only in a creator, on the stay
 *       it is building;</li>
 *   <li>the in-app admission serialises on the patient row before it checks,
 *       so two concurrent admissions cannot both pass.</li>
 * </ul>
 * A transfer moves the stay (bed, ward, department); it never builds a second
 * one, which is why no transfer class is here. The sets only shrink.
 */
class AdmissionCreationGuardTest {

    private static final String ADMISSION = "com/example/hms/model/Admission";
    private static final String ADMISSION_STATUS = "com/example/hms/enums/AdmissionStatus";
    private static final String ADMISSION_REPOSITORY = "com/example/hms/repository/AdmissionRepository";
    private static final String PATIENT_REPOSITORY = "com/example/hms/repository/PatientRepository";
    private static final Set<String> OPEN_STATUSES = Set.of("PENDING", "ACTIVE", "ON_LEAVE", "AWAITING_DISCHARGE");

    /** The classes that open a stay, each with the one-open-stay check. */
    private static final Set<String> CREATORS = Set.of(
        // POST /admissions: the in-app admission (the ED "admit" and every
        // portal admit button go through it).
        "AdmissionServiceImpl",
        // ADT^A01 auto-create from an HL7 sender.
        "MllpInboundAdtVisitProjectionServiceImpl");

    /** Outer class → what it does with stays and the check. */
    private record Usage(Set<String> constructs, Set<String> calls, Set<String> opensByStatus) {
        Usage() {
            this(new TreeSet<>(), new TreeSet<>(), new TreeSet<>());
        }
    }

    @Test
    @DisplayName("QA D800: every class that builds an Admission is a known creator and checks for an open stay")
    void everyAdmissionCreatorChecksForAnOpenStay() throws IOException {
        Map<String, Usage> usage = scan();
        Set<String> creators = new TreeSet<>();
        usage.forEach((owner, u) -> {
            if (!u.constructs().isEmpty()) {
                creators.add(owner);
            }
        });

        assertThat(creators).as("the main classes that construct an Admission").isEqualTo(new TreeSet<>(CREATORS));
        for (String creator : CREATORS) {
            assertThat(usage.get(creator).calls())
                .as(creator + " opens a stay, so it refuses a second open one")
                .contains(ADMISSION_REPOSITORY + "#existsOpenAdmissionForPatient");
        }
    }

    @Test
    @DisplayName("QA D800: no main code reopens a stay by status")
    void noCodeReopensAStay() throws IOException {
        Map<String, Usage> usage = scan();
        Set<String> reopeners = new TreeSet<>();
        Set<String> activators = new TreeSet<>();
        usage.forEach((owner, u) -> {
            if (!u.opensByStatus().isEmpty() && !CREATORS.contains(owner)) {
                reopeners.add(owner + " " + u.opensByStatus());
            }
            if (u.calls().contains(ADMISSION + "#activate")) {
                activators.add(owner);
            }
        });
        assertThat(reopeners).as("classes that set an open status on an Admission without building it").isEmpty();
        assertThat(activators).as("callers of Admission.activate()").isEmpty();
    }

    @Test
    @DisplayName("QA D304: the in-app admission locks the patient row before the open-stay check")
    void inAppAdmissionSerialisesOnThePatient() throws IOException {
        assertThat(scan().get("AdmissionServiceImpl").calls())
            .contains(PATIENT_REPOSITORY + "#lockByIdUnscoped");
    }

    // ── bytecode scan ───────────────────────────────────────────────────────

    private static Map<String, Usage> scan() throws IOException {
        Map<String, Usage> usage = new TreeMap<>();
        for (Resource resource : mainClasses()) {
            String[] owner = new String[1];
            boolean[] skip = new boolean[1];
            try (InputStream in = resource.getInputStream()) {
                new ClassReader(in).accept(new ClassVisitor(SpringAsmInfo.ASM_VERSION) {
                    @Override
                    public void visit(int version, int access, String name, String signature,
                                      String superName, String[] interfaces) {
                        owner[0] = outerSimpleName(name);
                        // The entity (and its own builder, if any) constructs itself.
                        skip[0] = name.equals(ADMISSION) || name.startsWith(ADMISSION + "$");
                    }

                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                     String signature, String[] exceptions) {
                        if (skip[0]) {
                            return null;
                        }
                        Usage u = usage.computeIfAbsent(owner[0], k -> new Usage());
                        String method = enclosingMethod(name);
                        return new StatusTrackingVisitor(u, method);
                    }
                }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
        }
        return usage;
    }

    /**
     * Records constructions, calls, and a {@code setStatus} whose argument is
     * an open {@code AdmissionStatus} constant loaded by the instruction just
     * before it.
     */
    private static final class StatusTrackingVisitor extends MethodVisitor {
        private final Usage usage;
        private final String method;
        private String pendingOpenStatus;

        StatusTrackingVisitor(Usage usage, String method) {
            super(SpringAsmInfo.ASM_VERSION);
            this.usage = usage;
            this.method = method;
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            pendingOpenStatus = null;
            if (opcode == Opcodes.NEW && ADMISSION.equals(type)) {
                usage.constructs().add(method);
            }
        }

        @Override
        public void visitFieldInsn(int opcode, String fieldOwner, String name, String descriptor) {
            pendingOpenStatus = opcode == Opcodes.GETSTATIC && ADMISSION_STATUS.equals(fieldOwner)
                && OPEN_STATUSES.contains(name) ? name : null;
        }

        @Override
        public void visitMethodInsn(int opcode, String target, String name, String descriptor, boolean isInterface) {
            usage.calls().add(target + "#" + name);
            if (ADMISSION.equals(target) && "setStatus".equals(name) && pendingOpenStatus != null) {
                usage.opensByStatus().add(method + ":" + pendingOpenStatus);
            }
            pendingOpenStatus = null;
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrap, Object... arguments) {
            pendingOpenStatus = null;
            for (Object argument : arguments) {
                if (argument instanceof Handle handle) {
                    usage.calls().add(handle.getOwner() + "#" + handle.getName());
                    if (ADMISSION.equals(handle.getOwner()) && "<init>".equals(handle.getName())) {
                        usage.constructs().add(method);
                    }
                }
            }
        }

        @Override
        public void visitInsn(int opcode) {
            pendingOpenStatus = null;
        }

        @Override
        public void visitVarInsn(int opcode, int varIndex) {
            pendingOpenStatus = null;
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            pendingOpenStatus = null;
        }

        @Override
        public void visitLdcInsn(Object value) {
            pendingOpenStatus = null;
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
            pendingOpenStatus = null;
        }
    }

    /** {@code lambda$foo$3} belongs to {@code foo}. */
    private static String enclosingMethod(String name) {
        if (name.startsWith("lambda$")) {
            int end = name.indexOf('$', "lambda$".length());
            return end < 0 ? name : name.substring("lambda$".length(), end);
        }
        return name;
    }

    private static Resource[] mainClasses() throws IOException {
        Resource[] all = new PathMatchingResourcePatternResolver().getResources("classpath*:com/example/hms/**/*.class");
        return Arrays.stream(all)
            .filter(resource -> {
                try {
                    return resource.getURL().toString().contains("/main/");
                } catch (IOException e) {
                    return false;
                }
            })
            .toArray(Resource[]::new);
    }

    private static String outerSimpleName(String internalName) {
        String simple = internalName.substring(internalName.lastIndexOf('/') + 1);
        int nested = simple.indexOf('$');
        return nested < 0 ? simple : simple.substring(0, nested);
    }
}
