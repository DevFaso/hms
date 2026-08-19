package com.example.hms.cdshooks.service;

import com.example.hms.cdshooks.dto.CdsHookDtos.CdsServiceDescriptor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Indexes {@link CdsHookService} beans by their declared {@code id}, with a
 * fast lookup for invocation and a stable iteration order for the discovery
 * endpoint.
 */
@Component
public class CdsHookRegistry {

    private final Map<String, CdsHookService> servicesById;
    private final List<CdsServiceDescriptor> descriptors;

    public CdsHookRegistry(List<CdsHookService> services) {
        Map<String, CdsHookService> byId = new HashMap<>();
        services.forEach(svc -> {
            CdsServiceDescriptor d = svc.descriptor();
            if (d == null || d.id() == null || d.id().isBlank()) {
                throw new IllegalStateException("CDS service " + svc.getClass().getName()
                    + " produced an invalid descriptor (missing id)");
            }
            if (byId.put(d.id(), svc) != null) {
                throw new IllegalStateException("Duplicate CDS service id: " + d.id());
            }
        });
        this.servicesById = Map.copyOf(byId);
        this.descriptors = services.stream()
            .map(CdsHookService::descriptor)
            .sorted(java.util.Comparator.comparing(CdsServiceDescriptor::id))
            .toList();
    }

    public List<CdsServiceDescriptor> descriptors() {
        return descriptors;
    }

    public Optional<CdsHookService> findById(String id) {
        return Optional.ofNullable(servicesById.get(id));
    }
}
