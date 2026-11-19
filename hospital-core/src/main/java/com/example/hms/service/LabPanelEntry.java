package com.example.hms.service;

import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.LabResultComponent;
import com.example.hms.model.LabTestComponent;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.payload.dto.LabResultComponentValueDTO;
import com.example.hms.payload.dto.LabResultRequestDTO;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * r63 (NFS panels): turns the component values of a hand-entered panel
 * result into what is stored. A full blood count is one order, one entry and
 * one release, with one value per component (Hb, WBC, platelets...), each
 * checked against its own component's ranges like a single value is.
 *
 * <p>The result's {@code resultValue} becomes a one-line summary
 * ("HB 13.5 g/dL; WBC 7.2 10^9/L") so every surface that prints a single
 * value still prints something true; the graded values are the components.
 */
final class LabPanelEntry {

    static final String COMPONENTS_FIELD = "components";
    private static final int SUMMARY_MAX = 2048;

    private LabPanelEntry() {
    }

    /**
     * The component values to store for this request, in the panel's order;
     * empty when the test is not a panel. Rewrites the request's value (the
     * summary) and unit (none) for a panel. Refuses components on a test that
     * is not a panel, an unknown or repeated code, a panel with no value at
     * all, and a value its component's ranges cannot hold.
     */
    static List<LabResultComponent> componentsFor(LabResultRequestDTO request, LabTestDefinition definition) {
        List<LabResultComponentValueDTO> sent = request.getComponents();
        boolean anySent = sent != null && !sent.isEmpty();
        if (definition == null || !definition.isPanel()) {
            if (anySent) {
                throw new FieldBusinessException(COMPONENTS_FIELD, "lab.result.components.notPanel");
            }
            return List.of();
        }
        Map<String, String> valuesByCode = anySent ? sentValues(sent, definition) : Map.of();
        List<LabResultComponent> stored = inPanelOrder(definition, valuesByCode);
        if (stored.isEmpty()) {
            throw new FieldBusinessException(COMPONENTS_FIELD, "lab.result.components.required");
        }
        String line = summaryOf(stored);
        request.setResultValue(line.length() > SUMMARY_MAX ? line.substring(0, SUMMARY_MAX - 1) + "…" : line);
        request.setResultUnit(null);
        return stored;
    }

    /** The typed values by component code, each checked; blank values are kept as blank. */
    private static Map<String, String> sentValues(List<LabResultComponentValueDTO> sent, LabTestDefinition definition) {
        Map<String, String> valuesByCode = new HashMap<>();
        for (int i = 0; i < sent.size(); i++) {
            LabResultComponentValueDTO value = sent.get(i);
            if (value != null) {
                readOne(value, COMPONENTS_FIELD + "[" + i + "]", definition, valuesByCode);
            }
        }
        return valuesByCode;
    }

    private static void readOne(LabResultComponentValueDTO value, String field, LabTestDefinition definition,
                                Map<String, String> valuesByCode) {
        String typedCode = value.getCode() == null ? "" : value.getCode().trim();
        String code = typedCode.toUpperCase(Locale.ROOT);
        LabTestComponent component = findComponent(definition, code);
        if (component == null) {
            throw new FieldBusinessException(field + ".code", "lab.result.components.unknownCode", typedCode);
        }
        if (valuesByCode.containsKey(code)) {
            throw new FieldBusinessException(field + ".code", "lab.result.components.duplicateCode", code);
        }
        String raw = value.getValue() == null ? "" : value.getValue().trim();
        if (!raw.isEmpty()) {
            raw = LabResultValueRules.normalizeDecimalComma(raw);
            LabResultValueRules.requireValidValue(raw, component.getReferenceRanges(), field + ".value");
        }
        valuesByCode.put(code, raw);
    }

    /** The entered (non-blank) values in the panel's own order, with its names and units. */
    private static List<LabResultComponent> inPanelOrder(LabTestDefinition definition, Map<String, String> valuesByCode) {
        List<LabResultComponent> stored = new ArrayList<>();
        for (LabTestComponent component : definition.getComponents()) {
            String value = component == null || component.getCode() == null
                ? null : valuesByCode.get(component.getCode().toUpperCase(Locale.ROOT));
            if (value != null && !value.isEmpty()) {
                stored.add(LabResultComponent.builder()
                    .code(component.getCode())
                    .name(component.getName())
                    .value(value)
                    .unit(component.getUnit())
                    .build());
            }
        }
        return stored;
    }

    private static String summaryOf(List<LabResultComponent> stored) {
        StringBuilder summary = new StringBuilder();
        for (LabResultComponent component : stored) {
            if (!summary.isEmpty()) {
                summary.append("; ");
            }
            summary.append(component.getCode()).append(' ').append(component.getValue());
            if (component.getUnit() != null) {
                summary.append(' ').append(component.getUnit());
            }
        }
        return summary.toString();
    }

    private static LabTestComponent findComponent(LabTestDefinition definition, String code) {
        if (code == null || code.isEmpty()) {
            return null;
        }
        return definition.getComponents().stream()
            .filter(c -> c != null && c.getCode() != null && c.getCode().equalsIgnoreCase(code))
            .findFirst()
            .orElse(null);
    }
}
