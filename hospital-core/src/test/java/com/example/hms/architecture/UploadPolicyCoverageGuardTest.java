package com.example.hms.architecture;

import com.example.hms.testsupport.ControllerMappingRegistry;
import com.example.hms.upload.UploadKind;
import com.example.hms.upload.UploadPolicy;
import jakarta.servlet.http.Part;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartRequest;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every way a file can enter the application goes through the shared upload
 * check ({@code com.example.hms.upload}): its type is read from its content,
 * its size is bounded, and the caller's rate and daily allowance are counted.
 *
 * <p>The check runs in {@code UploadAdmissionInterceptor}, before the handler,
 * for the kind the handler names with {@link UploadPolicy}; a handler that
 * names none receives no file. This guard makes that a build failure instead
 * of a refusal found at run time, and closes the ways around the interceptor:
 * <ol>
 *   <li>every handler that takes a multipart body carries {@code @UploadPolicy},
 *       and only such handlers do;</li>
 *   <li>the interceptor is registered for every path, with no exclusion;</li>
 *   <li>no class reads the multipart request itself (the servlet parts, a
 *       multipart resolver) outside the listed ones;</li>
 *   <li>every class that reads the bytes of an uploaded file also runs the
 *       check for the type it stores or parses.</li>
 * </ol>
 */
@DisplayName("No multipart endpoint takes a file past the shared upload check")
class UploadPolicyCoverageGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");

    /** Reading the multipart request itself: its servlet parts, the resolver, the raw request. */
    private static final Pattern RAW_MULTIPART = Pattern.compile(
        "\\.getParts\\s*\\(|\\.getPart\\s*\\(|MultipartHttpServletRequest|MultipartResolver|\\.getFileMap\\s*\\("
            + "|\\.getMultiFileMap\\s*\\(");

    /** Path under com/example/hms -> why it may read the multipart request itself. */
    private static final Map<String, String> RAW_MULTIPART_READERS = Map.of(
        "config/UploadSizeRefusalFilter.java",
            "parses the parts early only to answer an oversize body with the localized 413; reads no file",
        "upload/UploadAdmissionInterceptor.java",
            "the shared check itself: it lists the files of the request to vet each one");

    private static final Pattern READS_UPLOAD_BYTES = Pattern.compile(
        "\\.getInputStream\\s*\\(|\\.getBytes\\s*\\(|\\.transferTo\\s*\\(");

    @Test
    @DisplayName("every multipart handler names its upload policy, and only multipart handlers do")
    void everyMultipartHandlerNamesItsPolicy() {
        TreeSet<String> unguarded = new TreeSet<>();
        TreeSet<String> misplaced = new TreeSet<>();
        TreeMap<String, UploadKind> guarded = new TreeMap<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry
                : ControllerMappingRegistry.mapping().getHandlerMethods().entrySet()) {
            HandlerMethod handler = entry.getValue();
            String name = handler.getBeanType().getSimpleName() + "." + handler.getMethod().getName();
            boolean multipart = consumesMultipart(entry.getKey()) || takesAFile(handler);
            UploadPolicy policy = handler.getMethodAnnotation(UploadPolicy.class);
            if (multipart && policy == null) {
                unguarded.add(name);
            } else if (!multipart && policy != null) {
                misplaced.add(name);
            } else if (policy != null) {
                guarded.put(name, policy.value());
            }
        }

        assertThat(unguarded)
            .as("A handler takes a multipart body without @UploadPolicy. Name the UploadKind it accepts (add one "
                + "to UploadKind for a new class of file): the interceptor then checks every file by content, "
                + "size, rate and allowance before the handler runs. Without it the endpoint answers 400.")
            .isEmpty();
        assertThat(misplaced)
            .as("@UploadPolicy on a handler that takes no multipart body: it checks nothing there.")
            .isEmpty();
        assertThat(guarded)
            .as("the upload endpoints were found (a rename moves the entry; a removal deletes it)")
            .containsExactlyInAnyOrderEntriesOf(Map.of(
                "FileUploadController.uploadProfileImage", UploadKind.PROFILE_IMAGE,
                "FileUploadController.uploadReferralAttachment", UploadKind.REFERRAL_ATTACHMENT,
                "FileUploadController.uploadChartAttachment", UploadKind.CHART_ATTACHMENT,
                "FileUploadController.uploadChatAttachment", UploadKind.CHAT_ATTACHMENT,
                "PatientPhotoController.upload", UploadKind.PATIENT_PHOTO,
                "PatientPortalController.uploadDocument", UploadKind.PATIENT_DOCUMENT,
                "ImagingStudyImageController.upload", UploadKind.IMAGING_IMAGE,
                "PriceListController.importCsv", UploadKind.CSV_IMPORT,
                "ReferenceCatalogController.importCatalog", UploadKind.CSV_IMPORT));
    }

    @Test
    @DisplayName("no upload kind accepts a type a browser would run")
    void noKindAcceptsActiveContent() {
        for (UploadKind kind : UploadKind.values()) {
            assertThat(kind.allowedTypes()).as("%s accepts at least one type", kind).isNotEmpty();
            assertThat(kind.maxBytes()).as("%s is bounded by the one upload limit", kind)
                .isPositive().isLessThanOrEqualTo(com.example.hms.service.FileUploadService.MAX_UPLOAD_BYTES);
            for (String extension : new String[] {".svg", ".svgz", ".html", ".htm", ".xhtml", ".xml", ".js", ".mjs",
                ".exe", ".zip", ".docm", ".jar", ""}) {
                assertThat(kind.acceptsExtension(extension)).as("%s accepts %s", kind, extension).isFalse();
            }
        }
        for (UploadKind photo : EnumSet.of(UploadKind.PATIENT_PHOTO, UploadKind.PROFILE_IMAGE,
                UploadKind.CHAT_PHOTO)) {
            assertThat(photo.allowedTypes()).as("%s takes raster images only", photo)
                .isNotEmpty()
                .allMatch(com.example.hms.upload.UploadType::isRasterImage);
            assertThat(photo.isRasterOnly()).as("%s is typed by its bytes alone", photo).isTrue();
        }
        for (UploadKind named : EnumSet.complementOf(EnumSet.of(UploadKind.PATIENT_PHOTO, UploadKind.PROFILE_IMAGE,
                UploadKind.CHAT_PHOTO))) {
            assertThat(named.isRasterOnly()).as("%s keeps the rule that the name announces the content", named)
                .isFalse();
        }
        for (com.example.hms.upload.UploadType type : com.example.hms.upload.UploadType.values()) {
            assertThat(Set.of("image/svg+xml", "text/html", "application/xhtml+xml", "text/xml", "application/xml",
                "text/javascript", "application/javascript"))
                .as("the stored and served type of %s", type).doesNotContain(type.mediaType());
        }
    }

    @Test
    @DisplayName("the interceptor is registered for every path, with no exclusion")
    void theInterceptorCoversEveryPath() throws IOException {
        String config = UploadStorageWriteGuardTest.codeOnly(
            Files.readString(MAIN.resolve("config/UploadAdmissionWebConfig.java")));

        assertThat(config).contains("registry.addInterceptor(new UploadAdmissionInterceptor(limiter))");
        assertThat(config).doesNotContain("PathPatterns");
    }

    @Test
    @DisplayName("only the listed classes read the multipart request itself")
    void noClassReadsTheMultipartRequestItself() throws IOException {
        TreeSet<String> readers = classesMatching(RAW_MULTIPART, null);

        assertThat(readers)
            .as("A class reads the multipart request itself (servlet parts, the resolver, the file map): a file "
                + "read that way was not asked for by a handler. Take a MultipartFile parameter on a handler "
                + "that carries @UploadPolicy.")
            .isSubsetOf(RAW_MULTIPART_READERS.keySet());
        assertThat(RAW_MULTIPART_READERS.keySet())
            .as("A listed class no longer reads the multipart request: remove it from the list.")
            .isSubsetOf(readers);
    }

    @Test
    @DisplayName("every class that reads an uploaded file's bytes runs the shared check")
    void everyReaderOfUploadedBytesRunsTheCheck() throws IOException {
        TreeSet<String> readers = classesMatching(READS_UPLOAD_BYTES, "MultipartFile");
        readers.removeIf(path -> path.startsWith("upload/"));
        TreeSet<String> unchecked = new TreeSet<>();
        for (String reader : readers) {
            String code = UploadStorageWriteGuardTest.codeOnly(Files.readString(MAIN.resolve(reader)));
            if (!code.contains("UploadAdmission.vet(")) {
                unchecked.add(reader);
            }
        }

        assertThat(readers).as("the classes that store or parse uploads were found").contains(
            "service/FileUploadService.java", "service/impl/PatientPhotoServiceImpl.java",
            "service/ReferenceCatalogServiceImpl.java", "controller/PriceListController.java");
        assertThat(unchecked)
            .as("A class reads the bytes of a MultipartFile without UploadAdmission.vet(file, kind): the type "
                + "it stores or parses must be the one the check derived from the content, on the HTTP path "
                + "and on any other caller's.")
            .isEmpty();
    }

    private static boolean consumesMultipart(RequestMappingInfo mapping) {
        return mapping.getConsumesCondition().getConsumableMediaTypes().stream()
            .anyMatch(type -> "multipart".equalsIgnoreCase(type.getType()));
    }

    /** A parameter that is, holds, or is bound from an uploaded part. */
    private static boolean takesAFile(HandlerMethod handler) {
        for (MethodParameter parameter : handler.getMethodParameters()) {
            if (parameter.hasParameterAnnotation(RequestPart.class)
                    || carriesAFile(ResolvableType.forMethodParameter(parameter))) {
                return true;
            }
        }
        return false;
    }

    private static boolean carriesAFile(ResolvableType type) {
        Class<?> raw = type.resolve(Object.class);
        if (raw.isArray()) {
            return carriesAFile(type.getComponentType());
        }
        if (MultipartFile.class.isAssignableFrom(raw) || Part.class.isAssignableFrom(raw)
                || MultipartRequest.class.isAssignableFrom(raw)) {
            return true;
        }
        for (ResolvableType generic : type.getGenerics()) {
            if (generic.resolve() != null && generic.resolve() != raw && carriesAFile(generic)) {
                return true;
            }
        }
        return false;
    }

    /** Main classes whose code (comments removed) matches, and names {@code alsoNaming} when given. */
    private static TreeSet<String> classesMatching(Pattern pattern, String alsoNaming) throws IOException {
        TreeSet<String> matches = new TreeSet<>();
        try (Stream<Path> walk = Files.walk(MAIN)) {
            for (Path source : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                String code = UploadStorageWriteGuardTest.codeOnly(Files.readString(source));
                if (pattern.matcher(code).find() && (alsoNaming == null || code.contains(alsoNaming))) {
                    matches.add(MAIN.relativize(source).toString().replace('\\', '/'));
                }
            }
        }
        return matches;
    }
}
