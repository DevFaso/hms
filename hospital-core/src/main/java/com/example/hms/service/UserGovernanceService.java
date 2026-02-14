package com.example.hms.service;

import com.example.hms.enums.EmploymentType;
import com.example.hms.enums.JobTitle;
import com.example.hms.enums.PasswordRotationStatus;
import com.example.hms.enums.Specialization;
import com.example.hms.model.User;
import com.example.hms.payload.dto.AdminSignupRequest;
import com.example.hms.payload.dto.UserResponseDTO;
import com.example.hms.payload.dto.superadmin.SuperAdminUserBulkImportRequestDTO;
import com.example.hms.payload.dto.superadmin.SuperAdminUserBulkImportResponseDTO;
import com.example.hms.payload.dto.superadmin.SuperAdminUserForcePasswordResetRequestDTO;
import com.example.hms.payload.dto.superadmin.SuperAdminUserForcePasswordResetResponseDTO;
import com.example.hms.payload.dto.superadmin.SuperAdminUserImportResultDTO;
import com.example.hms.payload.dto.superadmin.SuperAdminUserResetResultDTO;
import com.example.hms.payload.dto.superadmin.SuperAdminUserPasswordRotationDTO;
import com.example.hms.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.example.hms.config.PasswordRotationPolicy.MAX_PASSWORD_AGE_DAYS;
import static com.example.hms.config.PasswordRotationPolicy.WARNING_WINDOW_DAYS;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.security.SecureRandom;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class UserGovernanceService {

    private static final int MIN_GENERATED_PASSWORD_LENGTH = 12;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserService userService;
    private final PasswordResetService passwordResetService;
    private final UserRepository userRepository;

    @Transactional
    public UserResponseDTO createUser(AdminSignupRequest request) {
        AdminSignupRequest normalized = normalizeRoles(request);
        return userService.createUserWithRolesAndHospital(normalized);
    }

    @Transactional
    public SuperAdminUserBulkImportResponseDTO importUsers(SuperAdminUserBulkImportRequestDTO request) {
        Objects.requireNonNull(request, "Import request must not be null");
        String csv = request.getCsvContent();
    if (isBlank(csv)) {
            throw new IllegalArgumentException("CSV content must not be blank");
        }

        String delimiter = request.getDelimiter();
        char separator = (delimiter != null && !delimiter.isBlank()) ? delimiter.charAt(0) : ',';

        List<SuperAdminUserImportResultDTO> results = new ArrayList<>();
        int processed = 0;
        int imported = 0;

        try (BufferedReader reader = new BufferedReader(new StringReader(csv))) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                throw new IllegalArgumentException("CSV content must include a header row");
            }

            Map<String, Integer> headerIndex = parseHeader(headerLine, separator);
            String line;
            while ((line = reader.readLine()) != null) {
                processed++;
                if (line.isBlank()) {
                    continue;
                }
                RowOutcome outcome = processImportRow(line, headerIndex, request, separator, processed + 1);
                results.add(outcome.resultDto());
                if (outcome.success()) {
                    imported++;
                }
            }
        } catch (IOException ioEx) {
            throw new IllegalStateException("Unable to read CSV payload", ioEx);
        }

        return SuperAdminUserBulkImportResponseDTO.builder()
            .processed(processed)
            .imported(imported)
            .failed(processed - imported)
            .results(results)
            .build();
    }

    @Transactional
    public SuperAdminUserForcePasswordResetResponseDTO forcePasswordReset(SuperAdminUserForcePasswordResetRequestDTO request) {
        Objects.requireNonNull(request, "Force password reset request must not be null");

    ResetTargets targets = collectTargets(request);
    Map<UUID, SuperAdminUserResetResultDTO.SuperAdminUserResetResultDTOBuilder> builders = initialiseResetBuilders(targets);
    int succeeded = applyForceReset(targets.users(), request, builders);

        List<SuperAdminUserResetResultDTO> results = builders.values().stream()
            .map(SuperAdminUserResetResultDTO.SuperAdminUserResetResultDTOBuilder::build)
            .toList();

        return SuperAdminUserForcePasswordResetResponseDTO.builder()
            .requested(targets.requestedCount())
            .succeeded(succeeded)
            .results(results)
            .build();
    }

    @Transactional(readOnly = true)
    public List<SuperAdminUserPasswordRotationDTO> listPasswordRotationStatus() {
        List<User> users = userRepository.findByIsDeletedFalse();
        if (users.isEmpty()) {
            return List.of();
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDate today = now.toLocalDate();

        return users.stream()
            .map(user -> mapToPasswordRotationDto(user, now, today))
            .sorted(Comparator
                .<SuperAdminUserPasswordRotationDTO>comparingInt(dto -> statusPriority(dto.getStatus()))
                .thenComparing(SuperAdminUserPasswordRotationDTO::getRotationDueOn, Comparator.nullsLast(LocalDate::compareTo))
                .thenComparing(dto -> dto.getUsername() == null ? "" : dto.getUsername().toLowerCase(Locale.ROOT)))
            .toList();
    }

    private SuperAdminUserPasswordRotationDTO mapToPasswordRotationDto(User user, LocalDateTime now, LocalDate today) {
        LocalDateTime effectiveChangedAt = user.getPasswordChangedAt();
        if (effectiveChangedAt == null) {
            effectiveChangedAt = user.getPasswordRotationForcedAt();
        }
        if (effectiveChangedAt == null) {
            effectiveChangedAt = user.getCreatedAt();
        }
        if (effectiveChangedAt == null) {
            effectiveChangedAt = user.getUpdatedAt();
        }
        if (effectiveChangedAt == null) {
            effectiveChangedAt = now;
        }

    LocalDate rotationDueOn = effectiveChangedAt.toLocalDate().plusDays(MAX_PASSWORD_AGE_DAYS);
    LocalDate warningStartsOn = rotationDueOn.minusDays(WARNING_WINDOW_DAYS);

        long passwordAgeDays = Math.max(0, ChronoUnit.DAYS.between(effectiveChangedAt, now));
        long daysUntilDue = ChronoUnit.DAYS.between(today, rotationDueOn);

        boolean dueReached = !rotationDueOn.isAfter(today);
        boolean forcedFlag = user.isForcePasswordChange() || user.getPasswordRotationForcedAt() != null || dueReached;

        PasswordRotationStatus status;
        if (forcedFlag) {
            status = PasswordRotationStatus.FORCE_REQUIRED;
        } else if (!warningStartsOn.isAfter(today)) {
            status = PasswordRotationStatus.WARNING;
        } else {
            status = PasswordRotationStatus.HEALTHY;
        }

        return SuperAdminUserPasswordRotationDTO.builder()
            .userId(user.getId())
            .username(user.getUsername())
            .email(user.getEmail())
            .firstName(user.getFirstName())
            .lastName(user.getLastName())
            .forcePasswordChange(user.isForcePasswordChange())
            .passwordChangedAt(user.getPasswordChangedAt())
            .passwordRotationWarningAt(user.getPasswordRotationWarningAt())
            .passwordRotationForcedAt(user.getPasswordRotationForcedAt())
            .rotationDueOn(rotationDueOn)
            .warningStartsOn(warningStartsOn)
            .passwordAgeDays(passwordAgeDays)
            .daysUntilDue(daysUntilDue)
            .status(status)
            .build();
    }

    private int statusPriority(PasswordRotationStatus status) {
        return switch (status) {
            case FORCE_REQUIRED -> 0;
            case WARNING -> 1;
            default -> 2;
        };
    }

    private RowOutcome processImportRow(String line,
                                        Map<String, Integer> headerIndex,
                                        SuperAdminUserBulkImportRequestDTO request,
                                        char separator,
                                        int rowNumber) {
        List<String> columns = parseCsvLine(line, separator);
        try {
            AdminSignupRequest signupRequest = buildSignupRequest(columns, headerIndex, request, rowNumber);
            UserResponseDTO response = userService.createUserWithRolesAndHospital(signupRequest);

            if (request.isSendInviteEmails()) {
                queueInvitation(response.getEmail());
            }

            SuperAdminUserImportResultDTO successResult = SuperAdminUserImportResultDTO.builder()
                .rowNumber(rowNumber)
                .identifier(signupRequest.getEmail())
                .success(true)
                .message("Imported successfully")
                .userId(response.getId())
                .build();
            return new RowOutcome(true, successResult);
        } catch (RuntimeException ex) {
            log.warn("[USER IMPORT] Failed to import row {}: {}", rowNumber, ex.getMessage());
            SuperAdminUserImportResultDTO failure = SuperAdminUserImportResultDTO.builder()
                .rowNumber(rowNumber)
                .identifier(extractIdentifier(columns, headerIndex))
                .success(false)
                .message(safeMessage(ex))
                .build();
            return new RowOutcome(false, failure);
        }
    }

    private ResetTargets collectTargets(SuperAdminUserForcePasswordResetRequestDTO request) {
        Set<UUID> idTargets = new LinkedHashSet<>(request.getUserIds());
        List<String> emailTargets = request.getEmails().stream()
            .filter(Objects::nonNull)
            .map(email -> email.toLowerCase(Locale.ROOT))
            .collect(Collectors.toCollection(ArrayList::new));

        Set<String> usernameTargets = request.getUsernames().stream()
            .filter(Objects::nonNull)
            .map(username -> username.toLowerCase(Locale.ROOT).trim())
            .filter(username -> !username.isBlank())
            .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<UUID, User> uniqueUsers = new LinkedHashMap<>();
        if (!idTargets.isEmpty()) {
            userRepository.findAllById(idTargets).forEach(user -> uniqueUsers.put(user.getId(), user));
        }
        if (!emailTargets.isEmpty()) {
            userRepository.findByEmailInIgnoreCase(emailTargets)
                .forEach(user -> uniqueUsers.putIfAbsent(user.getId(), user));
        }
        if (!usernameTargets.isEmpty()) {
            userRepository.findByUsernameInIgnoreCase(new ArrayList<>(usernameTargets))
                .forEach(user -> uniqueUsers.putIfAbsent(user.getId(), user));
        }

        return new ResetTargets(idTargets, emailTargets, new ArrayList<>(usernameTargets), new ArrayList<>(uniqueUsers.values()));
    }

    private Map<UUID, SuperAdminUserResetResultDTO.SuperAdminUserResetResultDTOBuilder> initialiseResetBuilders(ResetTargets targets) {
        Map<UUID, SuperAdminUserResetResultDTO.SuperAdminUserResetResultDTOBuilder> resultBuilders = new TreeMap<>();

        for (User user : targets.users()) {
            resultBuilders.put(user.getId(), SuperAdminUserResetResultDTO.builder()
                .userId(user.getId())
                .email(user.getEmail())
                .success(false));
        }

    for (UUID id : targets.ids()) {
            resultBuilders.computeIfAbsent(id, missingId -> SuperAdminUserResetResultDTO.builder()
                .userId(missingId)
                .success(false)
                .message("User not found"));
        }

    for (String email : targets.emails()) {
            boolean exists = targets.users().stream().anyMatch(user -> email.equalsIgnoreCase(user.getEmail()));
            if (!exists) {
                UUID syntheticId = UUID.nameUUIDFromBytes(("email:" + email).getBytes(StandardCharsets.UTF_8));
                resultBuilders.put(syntheticId, SuperAdminUserResetResultDTO.builder()
                    .email(email)
                    .success(false)
                    .message("User not found"));
            }
        }

        for (String username : targets.usernames()) {
            boolean exists = targets.users().stream().anyMatch(user -> username.equalsIgnoreCase(user.getUsername()));
            if (!exists) {
                UUID syntheticId = UUID.nameUUIDFromBytes(("username:" + username).getBytes(StandardCharsets.UTF_8));
                resultBuilders.put(syntheticId, SuperAdminUserResetResultDTO.builder()
                    .success(false)
                    .message("User not found (username: " + username + ")"));
            }
        }

        return resultBuilders;
    }

    private int applyForceReset(List<User> users,
                                SuperAdminUserForcePasswordResetRequestDTO request,
                                Map<UUID, SuperAdminUserResetResultDTO.SuperAdminUserResetResultDTOBuilder> builders) {
        int succeeded = 0;
        for (User user : users) {
            SuperAdminUserResetResultDTO.SuperAdminUserResetResultDTOBuilder builder = builders.get(user.getId());
            try {
                user.setForcePasswordChange(true);
                user.setUpdatedAt(LocalDateTime.now());
                userRepository.save(user);

                passwordResetService.invalidateAllForUser(user.getId());
                if (request.isSendEmail()) {
                    passwordResetService.requestReset(user.getEmail(), Locale.ENGLISH, null);
                }
                succeeded++;
                if (builder != null) {
                    builder.success(true).message("Reset queued");
                }
            } catch (RuntimeException ex) {
                log.warn("[USER RESET] Failed to queue reset for {}: {}", user.getId(), ex.getMessage());
                if (builder != null) {
                    builder.success(false).message(safeMessage(ex));
                }
            }
        }
        return succeeded;
    }

    private void queueInvitation(String email) {
        if (isBlank(email)) {
            return;
        }
        try {
            passwordResetService.requestReset(email, Locale.ENGLISH);
        } catch (RuntimeException ex) {
            log.debug("[USER IMPORT] Failed to queue invitation for {}: {}", email, ex.getMessage());
        }
    }

    private AdminSignupRequest buildSignupRequest(List<String> columns,
                                                  Map<String, Integer> headerIndex,
                                                  SuperAdminUserBulkImportRequestDTO request,
                                                  int rowNumber) {
        String username = value(columns, headerIndex, "username");
        String email = value(columns, headerIndex, "email");
        String firstName = value(columns, headerIndex, "firstname");
        String lastName = value(columns, headerIndex, "lastname");
        String phoneNumber = value(columns, headerIndex, "phonenumber");
        String rolesCell = value(columns, headerIndex, "roles");

        if (isAnyBlank(username, email, firstName, lastName, phoneNumber, rolesCell)) {
            throw new IllegalArgumentException("Missing required fields on row " + rowNumber);
        }

        Set<String> roleNames = parseRoles(rolesCell);
        if (roleNames.isEmpty()) {
            throw new IllegalArgumentException("No roles provided for row " + rowNumber);
        }

        UUID hospitalId = parseUuid(value(columns, headerIndex, "hospitalid"));
        if (hospitalId == null) {
            hospitalId = request.getDefaultHospitalId();
        }

        boolean forcePasswordChange = request.isForcePasswordChange();
        String forceColumn = value(columns, headerIndex, "forcepasswordchange");
        if (forceColumn != null) {
            forcePasswordChange = Boolean.parseBoolean(forceColumn);
        }

        String password = value(columns, headerIndex, "password");
        if (password == null || password.isBlank()) {
            password = generateRandomPassword();
        }

        AdminSignupRequest.AdminSignupRequestBuilder builder = AdminSignupRequest.builder()
            .username(username)
            .email(email)
            .password(password)
            .firstName(firstName)
            .lastName(lastName)
            .phoneNumber(phoneNumber)
            .hospitalId(hospitalId)
            .hospitalName(value(columns, headerIndex, "hospitalname"))
            .roleNames(roleNames)
            .licenseNumber(value(columns, headerIndex, "licensenumber"))
            .departmentId(parseUuid(value(columns, headerIndex, "departmentid")))
            .forcePasswordChange(forcePasswordChange);

        JobTitle jobTitle = parseEnum(JobTitle.class, value(columns, headerIndex, "jobtitle"));
        if (jobTitle != null) {
            builder.jobTitle(jobTitle);
        }
        EmploymentType employmentType = parseEnum(EmploymentType.class, value(columns, headerIndex, "employmenttype"));
        if (employmentType != null) {
            builder.employmentType(employmentType);
        }
        Specialization specialization = parseEnum(Specialization.class, value(columns, headerIndex, "specialization"));
        if (specialization != null) {
            builder.specialization(specialization);
        }

        return builder.build();
    }

    private AdminSignupRequest normalizeRoles(AdminSignupRequest request) {
        if (request.getRoleNames() == null) {
            return request;
        }
        Set<String> normalized = request.getRoleNames().stream()
            .filter(Objects::nonNull)
            .map(role -> role.trim().toUpperCase(Locale.ROOT))
            .map(role -> role.startsWith("ROLE_") ? role.substring(5) : role)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        request.setRoleNames(normalized);
        return request;
    }

    private Map<String, Integer> parseHeader(String headerLine, char separator) {
        List<String> headers = parseCsvLine(headerLine, separator);
        Map<String, Integer> index = new TreeMap<>();
        for (int i = 0; i < headers.size(); i++) {
            String key = headers.get(i);
            if (key == null) {
                continue;
            }
            index.put(key.trim().toLowerCase(Locale.ROOT), i);
        }
        return index;
    }

    private List<String> parseCsvLine(String line, char separator) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        int index = 0;
        while (index < line.length()) {
            char c = line.charAt(index);
            if (c == '"') {
                if (inQuotes && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    current.append('"');
                    index += 2;
                    continue;
                }
                inQuotes = !inQuotes;
            } else if (c == separator && !inQuotes) {
                values.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
            index++;
        }
        values.add(current.toString().trim());
        return values;
    }

    private String value(List<String> columns, Map<String, Integer> headerIndex, String key) {
        Integer idx = headerIndex.get(key);
        if (idx == null || idx < 0 || idx >= columns.size()) {
            return null;
        }
        String raw = columns.get(idx);
        return (raw == null || raw.isBlank()) ? null : raw.trim();
    }

    private String extractIdentifier(List<String> columns, Map<String, Integer> headerIndex) {
        String email = value(columns, headerIndex, "email");
        if (email != null) {
            return email;
        }
        String username = value(columns, headerIndex, "username");
        if (username != null) {
            return username;
        }
        return "row";
    }

    private Set<String> parseRoles(String rolesCell) {
        if (rolesCell == null || rolesCell.isBlank()) {
            return Set.of();
        }

        String[] tokens = rolesCell.split("[,;|\\s]+");
        return java.util.Arrays.stream(tokens)
            .map(token -> token == null ? null : token.trim())
            .filter(token -> token != null && !token.isBlank())
            .map(token -> token.toUpperCase(Locale.ROOT))
            .map(token -> token.startsWith("ROLE_") ? token.substring(5) : token)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid UUID: " + value, ex);
        }
    }

    private String safeMessage(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        return message.length() > 180 ? message.substring(0, 180) + "…" : message;
    }

    private String generateRandomPassword() {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*";
        StringBuilder sb = new StringBuilder();
        while (sb.length() < MIN_GENERATED_PASSWORD_LENGTH) {
            sb.append(alphabet.charAt(SECURE_RANDOM.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    private <E extends Enum<E>> E parseEnum(Class<E> enumClass, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (E constant : enumClass.getEnumConstants()) {
            if (constant.name().equals(normalized)) {
                return constant;
            }
        }
        return null;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private boolean isAnyBlank(String... values) {
        if (values == null) {
            return true;
        }
        for (String value : values) {
            if (isBlank(value)) {
                return true;
            }
        }
        return false;
    }

    private record RowOutcome(boolean success, SuperAdminUserImportResultDTO resultDto) {}

    private record ResetTargets(Set<UUID> ids, List<String> emails, List<String> usernames, List<User> users) {
        int requestedCount() {
            return ids.size() + emails.size() + usernames.size();
        }
    }
}
