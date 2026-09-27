package com.example.hms.service.empi;

import com.example.hms.service.integration.impl.MllpInboundMergeServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReaderFactory;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.net.URL;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who may hold an {@link EmpiAuthorisedMergePort}: the inbound HL7
 * {@code ADT^A40} path, and nothing else.
 *
 * <p>The port merges at a hospital its caller names, without the verified
 * request scope every other EMPI entry point resolves. A REST controller that
 * held one could merge at any hospital id a request carried. So any new holder
 * has to be a decision someone makes here, not an injection that compiles.
 *
 * <p>Checked on the COMPILED main classes, found on the classpath, rather than
 * by grepping source: a holder is a constructor parameter, a field or a method
 * parameter of the port type (or of {@code EmpiServiceImpl}, which implements
 * it), including inside a generic such as {@code ObjectProvider<…>}, and
 * that is how a method reference or a wrapper would reach it too. Main classes
 * are told apart from test classes by their code source, so this runs the same
 * from the module, the repository root or an IDE.
 */
class EmpiAuthorisedMergePortInjectionTest {

    private static final Set<String> HOLDER_TYPES = Set.of(
        EmpiAuthorisedMergePort.class.getName(),
        EmpiServiceImpl.class.getName());

    @Test
    void onlyTheInboundA40PathHoldsTheAuthorisedMergePort() throws IOException {
        URL mainCode = codeLocation(EmpiServiceImpl.class);
        ClassLoader loader = getClass().getClassLoader();
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver(loader);
        MetadataReaderFactory metadata = new CachingMetadataReaderFactory(resolver);

        Set<String> holders = new TreeSet<>();
        int inspected = 0;
        for (Resource resource : resolver.getResources("classpath*:com/example/hms/**/*.class")) {
            String className = metadata.getMetadataReader(resource).getClassMetadata().getClassName();
            Class<?> type = load(className, loader);
            if (type == null || !mainCode.equals(codeLocation(type))) {
                continue;
            }
            inspected++;
            if (!HOLDER_TYPES.contains(type.getName()) && holds(type)) {
                holders.add(type.getName());
            }
        }

        // The scan must actually have seen the main code, or an empty result
        // would pass for the wrong reason.
        assertThat(inspected).isGreaterThan(1000);
        assertThat(holders).containsExactly(MllpInboundMergeServiceImpl.class.getName());
    }

    private static boolean holds(Class<?> type) {
        List<Type> dependencyTypes = new ArrayList<>();
        try {
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                dependencyTypes.addAll(List.of(constructor.getGenericParameterTypes()));
            }
            for (Field field : type.getDeclaredFields()) {
                dependencyTypes.add(field.getGenericType());
            }
            for (Method method : type.getDeclaredMethods()) {
                dependencyTypes.addAll(List.of(method.getGenericParameterTypes()));
            }
        } catch (LinkageError unresolvable) {
            // A class whose signatures reference an optional dependency absent
            // from the test classpath cannot declare the port either.
            return false;
        }
        return dependencyTypes.stream()
            .map(Type::getTypeName)
            .anyMatch(name -> HOLDER_TYPES.stream().anyMatch(name::contains));
    }

    private static Class<?> load(String className, ClassLoader loader) {
        try {
            return Class.forName(className, false, loader);
        } catch (ClassNotFoundException | LinkageError unloadable) {
            return null;
        }
    }

    private static URL codeLocation(Class<?> type) {
        CodeSource source = type.getProtectionDomain().getCodeSource();
        return source == null ? null : source.getLocation();
    }

    @Test
    void thePortIsNotReachableThroughTheSharedEmpiServiceInterface() {
        // Moving the method back onto EmpiService would hand it to every
        // class that injects EmpiService — which is exactly what this type
        // exists to prevent.
        List<String> shared = Stream.of(EmpiService.class.getMethods())
            .map(Method::getName)
            .filter(Objects::nonNull)
            .toList();
        // Non-empty, and the interface it should be: a "does not contain" on
        // an empty list would pass for any interface at all.
        assertThat(shared)
            .isNotEmpty()
            .contains("mergePatients", "mergeIdentities")
            .doesNotContain("mergePatientsAtAuthorisedHospital");
    }
}
