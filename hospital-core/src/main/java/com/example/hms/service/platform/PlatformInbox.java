package com.example.hms.service.platform;

import com.example.hms.enums.InBasketItemType;
import com.example.hms.enums.InBasketPriority;
import com.example.hms.model.User;
import com.example.hms.payload.dto.clinical.CreateInBasketItemRequest;
import com.example.hms.repository.UserRepository;
import com.example.hms.service.InBasketService;
import com.example.hms.service.i18n.NotificationLocales;
import com.example.hms.service.i18n.NotificationLocalizer;
import com.example.hms.utility.MessageUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * QA D4308: what reaches the platform administrators' inbox.
 *
 * <p>Nothing was ever addressed to a super-admin, so the inbox on their menu was
 * always empty. Four things are filed there, each to every active platform
 * administrator, through the same in-basket every other reader uses:
 * <ul>
 *   <li>a provider facility whose evidence awaits verification (a task, removed
 *       once somebody decides);</li>
 *   <li>an integration that fails repeatedly (once per 24-hour window);</li>
 *   <li>a webhook endpoint the platform switched off after repeated failures;</li>
 *   <li>a request a hospital administrator sends to the platform.</li>
 * </ul>
 * An item is an inbox row only: nothing here sends an SMS or an e-mail.
 *
 * <p>An item is filed after the caller's transaction commits, in a transaction
 * of its own, and a failure to file is logged and never reaches the caller: the
 * verification, the delivery sweep or the health record it reports on must not
 * fail because the inbox could not be written.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PlatformInbox {

    public static final String PROVIDER_VERIFICATION = "PROVIDER_VERIFICATION";
    public static final String INTEGRATION_FAILING = "INTEGRATION_FAILING";
    public static final String WEBHOOK_ENDPOINT_DISABLED = "WEBHOOK_ENDPOINT_DISABLED";
    public static final String PLATFORM_REQUEST = "PLATFORM_REQUEST";

    private final InBasketService inBasketService;
    private final UserRepository userRepository;

    /** A provider facility's evidence was submitted (or submitted again) and awaits a decision. */
    public void providerAwaitingVerification(UUID facilityId, String facilityName, String facilityCode) {
        afterCommit(() -> {
            // A second submission replaces the item of the first, read or not.
            inBasketService.removeAllItemsForReference(facilityId, PROVIDER_VERIFICATION);
            file(null, InBasketItemType.TASK, InBasketPriority.NORMAL, facilityId, PROVIDER_VERIFICATION,
                "inbox.platform.provider.title", new Object[] {text(facilityName)},
                "inbox.platform.provider.body", new Object[] {text(facilityName), text(facilityCode)}, null, null);
        });
    }

    /** The evidence was verified or refused: nobody still has to decide. */
    public void providerDecided(UUID facilityId) {
        afterCommit(() -> inBasketService.removeOpenItemsForReference(facilityId, PROVIDER_VERIFICATION));
    }

    /**
     * An integration reached the repeated-failure threshold in its current
     * 24-hour window. One item per integration, organisation and window.
     */
    public void integrationFailingRepeatedly(String integrationId, UUID organizationId,
                                             LocalDateTime windowStartedAt, int failures) {
        UUID reference = UUID.nameUUIDFromBytes(
            (integrationId + '|' + organizationId + '|' + windowStartedAt).getBytes(StandardCharsets.UTF_8));
        String nameKey = "integration.connector." + integrationId + ".displayName";
        Object name = MessageUtil.resolveOrNull(NotificationLocales.STAFF, nameKey) != null
            ? NotificationLocalizer.key(nameKey) : integrationId;
        afterCommit(() -> file(null, InBasketItemType.MESSAGE, InBasketPriority.URGENT, reference, INTEGRATION_FAILING,
            "inbox.platform.integration.title", new Object[] {name},
            "inbox.platform.integration.body", new Object[] {name, failures}, null, null));
    }

    /** A hospital's webhook endpoint was switched off after repeated delivery failures. */
    public void webhookEndpointDisabled(UUID endpointId, UUID hospitalId, String hospitalName, int failures) {
        afterCommit(() -> file(hospitalId, InBasketItemType.MESSAGE, InBasketPriority.URGENT, endpointId,
            WEBHOOK_ENDPOINT_DISABLED,
            "inbox.platform.webhook.title", new Object[] {text(hospitalName)},
            "inbox.platform.webhook.body", new Object[] {text(hospitalName), failures}, null, null));
    }

    /** A hospital administrator wrote to the platform. The message is shown as typed. */
    public void hospitalRequest(UUID requestId, UUID hospitalId, String hospitalName, String requesterName,
                                String subject, String message) {
        afterCommit(() -> file(hospitalId, InBasketItemType.MESSAGE, InBasketPriority.NORMAL, requestId,
            PLATFORM_REQUEST,
            "inbox.platform.request.title", new Object[] {text(hospitalName), subject},
            null, null, message, requesterName));
    }

    private void file(UUID hospitalId, InBasketItemType type, InBasketPriority priority, UUID referenceId,
                      String referenceType, String titleKey, Object[] titleArgs, String bodyKey, Object[] bodyArgs,
                      String body, String senderName) {
        List<User> administrators;
        try {
            administrators = userRepository.findActivePlatformAdministrators();
        } catch (RuntimeException ex) {
            log.warn("Platform inbox: no item filed for {} {} (administrators could not be read)",
                referenceType, referenceId, ex);
            return;
        }
        for (User administrator : administrators) {
            try {
                CreateInBasketItemRequest request = new CreateInBasketItemRequest();
                request.setRecipientUserId(administrator.getId());
                request.setHospitalId(hospitalId);
                request.setItemType(type);
                request.setPriority(priority);
                request.setTitleKey(titleKey);
                request.setTitleArgs(titleArgs);
                // The stored text is what a reader sees should the key ever stop rendering.
                request.setTitle(render(titleKey, titleArgs));
                request.setBodyKey(bodyKey);
                request.setBodyArgs(bodyArgs);
                request.setBody(bodyKey != null ? render(bodyKey, bodyArgs) : body);
                request.setReferenceId(referenceId);
                request.setReferenceType(referenceType);
                request.setOrderingProviderName(senderName);
                inBasketService.createItemInNewTransaction(request);
            } catch (RuntimeException ex) {
                log.warn("Platform inbox: item {} {} not filed for administrator {}",
                    referenceType, referenceId, administrator.getId(), ex);
            }
        }
    }

    private static String render(String key, Object[] args) {
        Object[] plain = new Object[args == null ? 0 : args.length];
        for (int i = 0; i < plain.length; i++) {
            plain[i] = args[i] instanceof java.util.Map<?, ?> nested && nested.get("key") instanceof String nestedKey
                ? MessageUtil.resolve(NotificationLocales.STAFF, nestedKey) : args[i];
        }
        return MessageUtil.resolve(NotificationLocales.STAFF, key, plain);
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private void afterCommit(Runnable work) {
        Runnable guarded = () -> {
            try {
                work.run();
            } catch (RuntimeException ex) {
                log.warn("Platform inbox: an item could not be filed", ex);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    guarded.run();
                }
            });
        } else {
            guarded.run();
        }
    }
}
