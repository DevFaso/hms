package com.example.hms.testsupport;

/**
 * Balanced-delimiter walking for the source-scanning guards
 * ({@code architecture/*GuardTest}, {@code i18n/*ProseGuardTest}). One copy,
 * so a fix to how a {@code '"'} char literal, a {@code "}"} string, a
 * {@code """} text block or a {@code /* ... *&#47;} comment is skipped reaches
 * every scan: the first versions each carried their own walker, and only one
 * of them knew about char literals.
 */
public final class SourceScan {

    /**
     * Appended to every guard's failure message: the standing rule for anyone fixing a
     * D-numbered defect. It names only the page that is in this tree; that page
     * summarises the defect-fix-mandate skill and links it, so the message never points
     * at a file a branch may not carry yet.
     */
    public static final String MANDATE =
        " — fixing a QA defect? Read docs/qa/regression-guards.md (\"How a fix PR uses these guards\", the"
            + " defect-fix-mandate rule): fix the CLASS, not the instance;"
            + " an allowlist entry carries its defect id and the fix PR deletes it; a new site fails CI and is never"
            + " allowlisted to pass";

    private SourceScan() {
    }

    /** Index of the {@code }} that closes the {@code {} at {@code open}, or the source length. */
    public static int closingBrace(String source, int open) {
        return closing(source, open, '{', '}');
    }

    /** Index of the {@code )} that closes the {@code (} at {@code open}, or the source length. */
    public static int closingParen(String source, int open) {
        return closing(source, open, '(', ')');
    }

    private static int closing(String source, int open, char opener, char closer) {
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            int skipped = endOfLiteralOrComment(source, i);
            if (skipped >= 0) {
                i = skipped;
                continue;
            }
            char ch = source.charAt(i);
            if (ch == opener) {
                depth++;
            } else if (ch == closer && --depth == 0) {
                return i;
            }
        }
        return source.length();
    }

    /**
     * When a string literal, text block, char literal or comment starts at
     * {@code i}: the index of its last character. Otherwise {@code -1}.
     */
    public static int endOfLiteralOrComment(String source, int i) {
        if (source.startsWith("\"\"\"", i)) {
            int end = source.indexOf("\"\"\"", i + 3);
            return end < 0 ? source.length() : end + 2;
        }
        char ch = source.charAt(i);
        if (ch == '"') {
            return endOfStringLiteral(source, i);
        }
        if (ch == '\'') {
            return endOfCharLiteral(source, i);
        }
        if (ch == '/' && i + 1 < source.length()) {
            char next = source.charAt(i + 1);
            if (next == '/') {
                int nl = source.indexOf('\n', i);
                return nl < 0 ? source.length() : nl - 1;
            }
            if (next == '*') {
                int end = source.indexOf("*/", i + 2);
                return end < 0 ? source.length() : end + 1;
            }
        }
        return -1;
    }

    /** The index of the closing quote of the string literal opened at {@code openQuote}. */
    public static int endOfStringLiteral(String source, int openQuote) {
        for (int i = openQuote + 1; i < source.length(); i++) {
            char ch = source.charAt(i);
            if (ch == '\\') {
                i++;
            } else if (ch == '"') {
                return i;
            }
        }
        return source.length();
    }

    /**
     * The index of the closing quote of the char literal opened at {@code openQuote}:
     * {@code '"'}, {@code '\\n'}, {@code '\\u0041'}. An escape runs to the next quote
     * (at most eight characters), so a unicode escape does not throw the walker out
     * of step for the rest of the file.
     */
    public static int endOfCharLiteral(String source, int openQuote) {
        int i = openQuote + 1;
        if (i < source.length() && source.charAt(i) == '\\') {
            if (i + 1 < source.length() && source.charAt(i + 1) == '\'') {
                return Math.min(i + 2, source.length() - 1); // '\''
            }
            int close = source.indexOf('\'', i + 2);
            if (close > 0 && close - openQuote <= 9) {
                return close;
            }
            i++;
        }
        return Math.min(i + 1, source.length());
    }

    /**
     * The source with every {@code //} and {@code /* *&#47;} comment blanked
     * (spaces, newlines kept), so that a {@code @PutMapping} or a {@code public}
     * written in a Javadoc is not read as code. Literals are kept verbatim.
     */
    public static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        for (int i = 0; i < source.length(); i++) {
            char ch = source.charAt(i);
            if (ch == '/' && i + 1 < source.length() && (source.charAt(i + 1) == '/' || source.charAt(i + 1) == '*')) {
                int end = endOfLiteralOrComment(source, i);
                for (int k = i; k <= end && k < source.length(); k++) {
                    out.append(source.charAt(k) == '\n' ? '\n' : ' ');
                }
                i = end;
                continue;
            }
            int literalEnd = endOfLiteralOrComment(source, i);
            if (literalEnd >= 0) {
                out.append(source, i, Math.min(literalEnd + 1, source.length()));
                i = literalEnd;
                continue;
            }
            out.append(ch);
        }
        return out.toString();
    }

    /**
     * The index just after the {@code ;}, {@code {} or {@code }} that precedes
     * {@code index} outside any literal or comment: where the statement
     * containing {@code index} starts. A {@code ;} inside a string does not count.
     */
    public static int statementStart(String source, int index) {
        int start = 0;
        for (int i = 0; i < index; i++) {
            int skipped = endOfLiteralOrComment(source, i);
            if (skipped >= 0) {
                i = skipped;
                continue;
            }
            char ch = source.charAt(i);
            if (ch == ';' || ch == '{' || ch == '}') {
                start = i + 1;
            }
        }
        return start;
    }

    /**
     * The first {@code ch} at or after {@code from} that is outside any literal or
     * comment, or {@code -1}: a {@code }} inside {@code "rescheduled {}"} is text.
     */
    public static int indexOfOutsideLiterals(String source, char ch, int from) {
        for (int i = Math.max(from, 0); i < source.length(); i++) {
            int skipped = endOfLiteralOrComment(source, i);
            if (skipped >= 0) {
                i = skipped;
                continue;
            }
            if (source.charAt(i) == ch) {
                return i;
            }
        }
        return -1;
    }

    /**
     * From {@code from}: past whitespace, past an annotation's argument list and
     * every further annotation (with or without arguments), to the first character
     * of what follows — the signature after {@code @PutMapping(...) @Operation(...)},
     * the body after {@code @TransactionalEventListener(...) @Retryable(include = {...})}.
     */
    public static int skipAnnotations(String source, int from) {
        int i = from;
        while (i < source.length()) {
            while (i < source.length() && Character.isWhitespace(source.charAt(i))) {
                i++;
            }
            if (i < source.length() && source.charAt(i) == '(') {
                i = closingParen(source, i) + 1;
            } else if (i < source.length() && source.charAt(i) == '@') {
                i++;
                while (i < source.length() && (Character.isLetterOrDigit(source.charAt(i)) || source.charAt(i) == '.'
                    || source.charAt(i) == '_')) {
                    i++;
                }
            } else {
                return i;
            }
        }
        return i;
    }

    /**
     * Every statement boundary of the source in one pass: the indexes just after
     * each {@code ;}, {@code {} or {@code }} outside literals and comments, starting
     * with 0. {@link #statementStart(String, int)} per sink is O(n) each; a scan with
     * many sinks asks this once and looks its boundaries up.
     */
    public static int[] statementStarts(String source) {
        java.util.List<Integer> starts = new java.util.ArrayList<>();
        starts.add(0);
        for (int i = 0; i < source.length(); i++) {
            int skipped = endOfLiteralOrComment(source, i);
            if (skipped >= 0) {
                i = skipped;
                continue;
            }
            char ch = source.charAt(i);
            if (ch == ';' || ch == '{' || ch == '}') {
                starts.add(i + 1);
            }
        }
        int[] out = new int[starts.size()];
        for (int k = 0; k < out.length; k++) {
            out[k] = starts.get(k);
        }
        return out;
    }

    /** The statement start at or before {@code index}, from {@link #statementStarts(String)}. */
    public static int statementStartBefore(int[] starts, int index) {
        int at = java.util.Arrays.binarySearch(starts, index);
        if (at >= 0) {
            return starts[at];
        }
        int insertion = -at - 1;
        return starts[Math.max(insertion - 1, 0)];
    }

    /** 1-based line of {@code index} in {@code source}. */
    public static int lineOf(String source, int index) {
        return 1 + (int) source.chars().limit(index).filter(c -> c == '\n').count();
    }
}
