package com.example.hms.architecture;

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
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D601: a hard delete of lab / microbiology data must never orphan the rows
 * that point at it (dev carries no foreign key to stop it). Every main-code
 * method that calls a {@code delete*} method on a lab or microbiology
 * repository is found by a bytecode scan (lambdas included) and must, earlier
 * in the same method, either:
 * <ul>
 *   <li>call an {@code existsBy*} reference check on any repository, or</li>
 *   <li>call one of the {@link #GUARD_HELPERS} that refuse the delete once the
 *       row is part of the chart;</li>
 * </ul>
 * or be recorded in {@link #RECORDED} with the reason it is safe. The map only
 * shrinks: an entry that no longer matches an unchecked delete fails the test.
 *
 * <p>Swept paths (2026-10-23): LabTestDefinitionServiceImpl.delete
 * (status + existsByLabTestDefinition_Id), LabOrderServiceImpl.deleteLabOrder
 * (existsByLabOrder_Id), LabResultServiceImpl.deleteLabResult
 * (requireNotReleased), plus the recorded paths below.
 */
class LabHardDeleteGuardCoverageTest {

    private static final String REPOSITORY_PACKAGE = "com/example/hms/repository/";

    /** Lab and microbiology repositories; Labor* (obstetrics) is not lab data. */
    private static boolean isGuardedRepository(String owner) {
        if (!owner.startsWith(REPOSITORY_PACKAGE)) {
            return false;
        }
        String simple = owner.substring(REPOSITORY_PACKAGE.length());
        return (simple.startsWith("Lab") && !simple.startsWith("Labor")) || simple.startsWith("Micro");
    }

    /** Helpers that refuse a delete once the row is referenced by the chart. */
    private static final Set<String> GUARD_HELPERS = Set.of("requireNotReleased");

    /** Unchecked deletes, each with why nothing can be orphaned. Only shrinks. */
    private static final Map<String, String> RECORDED = Map.ofEntries(
        entry("com.example.hms.service.LabTestValidationStudyServiceImpl#delete",
            "a validation study is a leaf record: no order, result or definition column points at it"),
        entry("com.example.hms.service.impl.MicroCultureServiceImpl#deleteIsolate",
            "children (susceptibilities) are deleted first in the same method; a final report needs a "
                + "correction reason and is stamped CORRECTED, so the chart keeps the trail"),
        entry("com.example.hms.service.impl.MicroCultureServiceImpl#deleteSusceptibility",
            "a susceptibility row is a leaf; a final report needs a correction reason and is stamped CORRECTED"));

    @Test
    @DisplayName("QA D601: every lab hard delete is preceded by a reference check or is recorded")
    void everyLabHardDeleteIsGuarded() throws IOException {
        Set<String> unchecked = new TreeSet<>();
        Set<String> checked = new TreeSet<>();
        Resource[] classes = new PathMatchingResourcePatternResolver()
            .getResources("classpath*:com/example/hms/**/*.class");
        for (Resource resource : classes) {
            String path = resource.getURL().getPath();
            if (path.contains("/test/") || path.contains("test-classes")) {
                continue;
            }
            try (InputStream in = resource.getInputStream()) {
                ClassReader reader = new ClassReader(in);
                String className = reader.getClassName().replace('/', '.');
                reader.accept(new ClassVisitor(SpringAsmInfo.ASM_VERSION) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                     String signature, String[] exceptions) {
                        String key = className + "#" + name;
                        return new MethodVisitor(SpringAsmInfo.ASM_VERSION) {
                            private boolean guarded;

                            @Override
                            public void visitMethodInsn(int opcode, String owner, String called,
                                                        String desc, boolean isInterface) {
                                if (called.startsWith("existsBy") || GUARD_HELPERS.contains(called)) {
                                    guarded = true;
                                } else if (called.startsWith("delete") && isGuardedRepository(owner)) {
                                    (guarded ? checked : unchecked).add(key);
                                }
                            }
                        };
                    }
                }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
        }

        assertThat(checked)
            .as("the swept, reference-checked deletes are still found (the scan works)")
            .contains(
                "com.example.hms.service.LabTestDefinitionServiceImpl#delete",
                "com.example.hms.service.LabOrderServiceImpl#deleteLabOrder",
                "com.example.hms.service.LabResultServiceImpl#deleteLabResult");
        assertThat(unchecked)
            .as("a lab hard delete with no reference check before it: add one, or record why it is safe")
            .isSubsetOf(RECORDED.keySet());
        assertThat(RECORDED.keySet())
            .as("a recorded delete that is now checked or gone: remove it (the map only shrinks)")
            .isSubsetOf(unchecked);
    }
}
