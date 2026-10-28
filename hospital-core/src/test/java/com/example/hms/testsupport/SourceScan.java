package com.example.hms.testsupport;

/**
 * Balanced-delimiter walking for the source-scanning guards
 * ({@code architecture/*GuardTest}, {@code i18n/*ProseGuardTest}). One copy,
 * so a fix to how a {@code '"'} char literal or a {@code "}"} string is skipped
 * reaches every scan: the first versions each carried their own walker, and
 * only one of them knew about char literals.
 */
public final class SourceScan {

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
            char ch = source.charAt(i);
            if (ch == '"') {
                i = endOfStringLiteral(source, i);
            } else if (ch == '\'') {
                i = endOfCharLiteral(source, i);
            } else if (ch == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                i = endOfLine(source, i);
            } else if (ch == opener) {
                depth++;
            } else if (ch == closer && --depth == 0) {
                return i;
            }
        }
        return source.length();
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

    /** The index of the closing quote of the char literal opened at {@code openQuote} ({@code '"'}, {@code '\\n'}). */
    public static int endOfCharLiteral(String source, int openQuote) {
        int i = openQuote + 1;
        if (i < source.length() && source.charAt(i) == '\\') {
            i++;
        }
        return Math.min(i + 1, source.length());
    }

    private static int endOfLine(String source, int from) {
        int nl = source.indexOf('\n', from);
        return nl < 0 ? source.length() : nl;
    }

    /** 1-based line of {@code index} in {@code source}. */
    public static int lineOf(String source, int index) {
        return 1 + (int) source.chars().limit(index).filter(c -> c == '\n').count();
    }
}
