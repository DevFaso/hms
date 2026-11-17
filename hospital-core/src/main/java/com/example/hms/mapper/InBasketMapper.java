package com.example.hms.mapper;

import com.example.hms.model.InBasketItem;
import com.example.hms.payload.dto.clinical.InBasketItemDTO;
import com.example.hms.service.i18n.NotificationLocalizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Hand-written mapper for InBasketItem ↔ DTO conversions.
 *
 * <p>QA D1053: an item written with a message key is rendered in the reader's
 * language (the request locale); one without a key, or whose key no longer
 * renders, shows its stored text.
 */
@Slf4j
@Component
public class InBasketMapper {

    private final NotificationLocalizer localizer;

    /** Without a localizer every item shows its stored text. */
    public InBasketMapper() {
        this(null);
    }

    @Autowired
    public InBasketMapper(NotificationLocalizer localizer) {
        this.localizer = localizer;
    }

    public InBasketItemDTO toDto(InBasketItem item) {
        if (item == null) return null;

        Locale locale = LocaleContextHolder.getLocale();
        return InBasketItemDTO.builder()
                .id(item.getId())
                .itemType(item.getItemType() != null ? item.getItemType().name() : null)
                .priority(item.getPriority() != null ? item.getPriority().name() : null)
                .status(item.getStatus() != null ? item.getStatus().name() : null)
                .title(rendered(item, item.getTitleKey(), item.getTitleArgs(), item.getTitle(), locale))
                .body(rendered(item, item.getBodyKey(), item.getBodyArgs(), item.getBody(), locale))
                .referenceId(item.getReferenceId())
                .referenceType(item.getReferenceType())
                .encounterId(item.getEncounter() != null ? item.getEncounter().getId() : null)
                .patientId(item.getPatient() != null ? item.getPatient().getId() : null)
                .patientName(item.getPatientName())
                .orderingProviderName(item.getOrderingProviderName())
                .createdAt(item.getCreatedAt())
                .readAt(item.getReadAt())
                .acknowledgedAt(item.getAcknowledgedAt())
                .build();
    }

    private String rendered(InBasketItem item, String key, List<Object> args, String stored, Locale locale) {
        if (key == null || localizer == null || locale == null) {
            return stored;
        }
        try {
            return localizer.render(key, args, locale);
        } catch (RuntimeException ex) {
            log.warn("In-basket item {} kept its stored text: key {} did not render ({})",
                item.getId(), key, ex.getClass().getSimpleName());
            return stored;
        }
    }
}
