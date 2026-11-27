package com.example.hms.upload;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;
import org.springframework.lang.NonNull;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.multipart.MultipartRequest;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.WebUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every file of every multipart request is checked here, before its handler
 * runs: against the {@link UploadPolicy} the handler names (size, type by
 * content, name matching content), then against the caller's upload rate and
 * daily allowance ({@link UploadLimiter}). A file sent to a handler that names
 * no policy is refused, so a new multipart endpoint cannot take files
 * unchecked.
 *
 * <p>What is checked is what the handler can read as a file: the parts bound
 * to its file parameters, or every file part when it takes the multipart
 * request itself. A part bound to a text or JSON parameter is a form field.
 *
 * <p>The limiter comes through an {@link ObjectProvider}: a controller slice
 * test has no such bean, and still gets the content check.
 */
public class UploadAdmissionInterceptor implements HandlerInterceptor {

    private static final DefaultParameterNameDiscoverer PARAMETER_NAMES = new DefaultParameterNameDiscoverer();

    private final ObjectProvider<UploadLimiter> limiter;

    public UploadAdmissionInterceptor(ObjectProvider<UploadLimiter> limiter) {
        this.limiter = limiter;
    }

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                             @NonNull Object handler) {
        MultipartHttpServletRequest multipart = WebUtils.getNativeRequest(request, MultipartHttpServletRequest.class);
        if (multipart == null) {
            return true;
        }
        HandlerMethod method = handler instanceof HandlerMethod handlerMethod ? handlerMethod : null;
        UploadPolicy policy = method != null ? method.getMethodAnnotation(UploadPolicy.class) : null;
        // Without a policy nothing says which parts are files: every one counts.
        Set<String> fileParts = policy != null ? filePartNamesOf(method) : null;
        List<MultipartFile> files = new ArrayList<>();
        for (Map.Entry<String, List<MultipartFile>> part : multipart.getMultiFileMap().entrySet()) {
            if (fileParts == null || fileParts.contains(part.getKey())) {
                part.getValue().stream()
                    // An empty part carries nothing to check; the handler answers it (a required file).
                    .filter(file -> file != null && !file.isEmpty())
                    .forEach(files::add);
            }
        }
        if (files.isEmpty()) {
            return true;
        }
        if (policy == null) {
            throw new UploadRefusedException("file.upload.notAccepted");
        }
        long bytes = 0;
        for (MultipartFile file : files) {
            UploadAdmission.vet(file, policy.value());
            bytes += file.getSize();
        }
        UploadLimiter limits = limiter.getIfAvailable();
        if (limits != null) {
            limits.admit(files.size(), bytes);
        }
        return true;
    }

    /**
     * The names of the parts the handler binds to a file parameter, or
     * {@code null} when it can read any (it takes the multipart request
     * itself, or binds no file parameter by name).
     */
    private static Set<String> filePartNamesOf(HandlerMethod method) {
        Set<String> names = new HashSet<>();
        for (MethodParameter parameter : method.getMethodParameters()) {
            Class<?> raw = parameter.getParameterType();
            if (MultipartRequest.class.isAssignableFrom(raw) || HttpServletRequest.class.isAssignableFrom(raw)) {
                return null;
            }
            if (!carriesAFile(ResolvableType.forMethodParameter(parameter))) {
                continue;
            }
            String name = boundNameOf(parameter);
            if (name == null) {
                return null;
            }
            names.add(name);
        }
        return names.isEmpty() ? null : names;
    }

    private static String boundNameOf(MethodParameter parameter) {
        RequestParam param = parameter.getParameterAnnotation(RequestParam.class);
        if (param != null && StringUtils.hasText(param.name())) {
            return param.name();
        }
        RequestPart part = parameter.getParameterAnnotation(RequestPart.class);
        if (part != null && StringUtils.hasText(part.name())) {
            return part.name();
        }
        parameter.initParameterNameDiscovery(PARAMETER_NAMES);
        return parameter.getParameterName();
    }

    private static boolean carriesAFile(ResolvableType type) {
        Class<?> raw = type.resolve(Object.class);
        if (raw.isArray()) {
            return carriesAFile(type.getComponentType());
        }
        if (MultipartFile.class.isAssignableFrom(raw) || Part.class.isAssignableFrom(raw)) {
            return true;
        }
        for (ResolvableType generic : type.getGenerics()) {
            Class<?> element = generic.resolve();
            if (element != null && (MultipartFile.class.isAssignableFrom(element) || Part.class.isAssignableFrom(element))) {
                return true;
            }
        }
        return false;
    }
}
