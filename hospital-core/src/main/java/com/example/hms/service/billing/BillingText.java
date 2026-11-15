package com.example.hms.service.billing;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * QA D1901: a sentence the server writes onto an invoice (a draft's note, a
 * line's description, a cancellation reason), kept as a message code and its
 * arguments so every reader sees it in their own language, instead of text
 * frozen in whichever language was active when it was written.
 *
 * <p>An argument is a value (a visit code, an invoice number), a message code
 * (a blood product, a delivery mode) or a calendar day: never translated
 * text. {@link BillingTextRenderer} turns it into words for one reader; the
 * French copy ({@link #french()}) is what the existing text column keeps, so
 * search, older clients and the column's NOT NULL rule keep working.
 *
 * <p>Stored as {@code *_code} plus {@code *_args} (a JSON array such as
 * {@code [{"value":"V-12"},{"code":"transfusion.productType.PLATELETS"},{"date":"2026-11-15"}]}).
 */
public record BillingText(String code, List<Arg> args) {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** What an argument holds, and so how it is shown. */
    public enum Kind {
        /** Shown as it is: a code, a number. */
        VALUE("value"),
        /** A message code, shown in the reader's language. */
        CODE("code"),
        /** An ISO calendar day, written the reader's way. */
        DATE("date");

        private final String jsonName;

        Kind(String jsonName) {
            this.jsonName = jsonName;
        }

        String jsonName() {
            return jsonName;
        }

        static Kind ofJsonName(String name) {
            for (Kind kind : values()) {
                if (kind.jsonName.equals(name)) {
                    return kind;
                }
            }
            return null;
        }
    }

    /** One argument of the sentence. */
    public record Arg(Kind kind, String value) {
    }

    public BillingText {
        args = args == null ? List.of() : List.copyOf(args);
    }

    public static BillingText of(String code, Arg... args) {
        return new BillingText(code, List.of(args));
    }

    /** A value shown as it is (a visit code, an invoice number); never translated text. */
    public static Arg value(String value) {
        return new Arg(Kind.VALUE, value == null ? "" : value);
    }

    /** A message code, resolved in the reader's language. */
    public static Arg code(String messageCode) {
        return new Arg(Kind.CODE, messageCode);
    }

    /** A calendar day, written the reader's way. */
    public static Arg date(LocalDate day) {
        return new Arg(Kind.DATE, day == null ? "" : day.toString());
    }

    /** The arguments as stored in {@code *_args}; null when the sentence has none. */
    public String argsJson() {
        if (args.isEmpty()) {
            return null;
        }
        ArrayNode array = JSON.createArrayNode();
        for (Arg arg : args) {
            array.addObject().put(arg.kind().jsonName(), arg.value());
        }
        return array.toString();
    }

    /**
     * The French copy kept in the text column (the product's first language);
     * the code itself only if no bundle knows it, so the column is never empty.
     */
    public String french() {
        String french = BillingTextRenderer.renderOrNull(BillingTextRenderer.STORED_LOCALE, this);
        return french != null ? french : code;
    }

    /**
     * The stored arguments read back; empty when the JSON is not an array of
     * one-key objects of a known kind (the reader then sees the stored text).
     */
    static Optional<List<Arg>> parseArgs(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) {
            return Optional.of(List.of());
        }
        JsonNode root;
        try {
            root = JSON.readTree(argsJson);
        } catch (JacksonException e) {
            return Optional.empty();
        }
        if (root == null || !root.isArray()) {
            return Optional.empty();
        }
        List<Arg> parsed = new ArrayList<>();
        for (JsonNode node : root) {
            Arg arg = parseArg(node);
            if (arg == null) {
                return Optional.empty();
            }
            parsed.add(arg);
        }
        return Optional.of(parsed);
    }

    private static Arg parseArg(JsonNode node) {
        if (node == null || !node.isObject() || node.size() != 1) {
            return null;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = node.properties().iterator();
        Map.Entry<String, JsonNode> field = fields.next();
        Kind kind = Kind.ofJsonName(field.getKey());
        if (kind == null || field.getValue() == null || !field.getValue().isString()) {
            return null;
        }
        return new Arg(kind, field.getValue().asString());
    }
}
