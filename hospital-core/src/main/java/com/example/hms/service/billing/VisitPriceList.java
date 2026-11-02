package com.example.hms.service.billing;

import com.example.hms.model.Treatment;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * QA D972: the hospital's active, priced services, matched by name the way
 * the checkout always has: a line takes a service's price only when exactly
 * one service matches (case and accents aside); otherwise it is posted at
 * zero for the cashier to price.
 */
public final class VisitPriceList {

    private final List<Treatment> priced;

    public VisitPriceList(List<Treatment> services) {
        this.priced = services == null ? List.of() : services.stream()
            .filter(t -> !Boolean.FALSE.equals(t.getActive()) && t.getPrice() != null && t.getPrice().signum() >= 0
                && t.getName() != null)
            .toList();
    }

    /** The one priced service named exactly like {@code name}, if exactly one is. */
    public Optional<Treatment> sameName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String wanted = fold(name);
        return single(candidate -> candidate.equals(wanted));
    }

    /** The one priced service whose name contains any of {@code words} (already folded), if exactly one does. */
    public Optional<Treatment> naming(List<String> words) {
        return single(name -> words.stream().anyMatch(name::contains));
    }

    private Optional<Treatment> single(Predicate<String> nameMatches) {
        List<Treatment> matches = priced.stream().filter(t -> nameMatches.test(fold(t.getName()))).toList();
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }

    static String fold(String text) {
        return Normalizer.normalize(text.trim().toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "");
    }
}
