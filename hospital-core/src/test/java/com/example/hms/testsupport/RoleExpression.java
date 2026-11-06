package com.example.hms.testsupport;

import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.expression.EvaluationException;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.method.HandlerMethod;

import java.util.Set;

/**
 * Evaluates a handler's {@code @PreAuthorize} (method first, then class) for one
 * authority, with the role functions of Spring Security's expression root.
 * Answers {@link #YES}, {@link #NO}, {@link #NONE} when the handler declares no
 * expression (the URL rules in SecurityConfig decide), or {@link #CUSTOM} when
 * the expression calls something beyond the roles and only runtime can tell.
 */
public final class RoleExpression {

    public static final String YES = "YES";
    public static final String NO = "NO";
    public static final String NONE = "NONE";
    public static final String CUSTOM = "CUSTOM";

    private static final ExpressionParser PARSER = new SpelExpressionParser();

    private RoleExpression() {
    }

    public static String admits(HandlerMethod handler, String authority) {
        PreAuthorize annotation = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), PreAuthorize.class);
        if (annotation == null) {
            annotation = AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(), PreAuthorize.class);
        }
        if (annotation == null) {
            return NONE;
        }
        try {
            Boolean result = PARSER.parseExpression(annotation.value())
                .getValue(new StandardEvaluationContext(new Root(authority)), Boolean.class);
            return Boolean.TRUE.equals(result) ? YES : NO;
        } catch (EvaluationException | org.springframework.expression.ParseException e) {
            return CUSTOM;
        }
    }

    /** The functions a role expression calls; a getter or bean reference beyond them raises, and reads as CUSTOM. */
    public static final class Root {
        private final String authority;

        Root(String authority) {
            this.authority = authority;
        }

        public boolean hasAuthority(String wanted) {
            return authority.equals(wanted);
        }

        public boolean hasAnyAuthority(String... wanted) {
            return Set.of(wanted).contains(authority);
        }

        public boolean hasRole(String wanted) {
            return authority.equals(wanted.startsWith("ROLE_") ? wanted : "ROLE_" + wanted);
        }

        public boolean hasAnyRole(String... wanted) {
            for (String one : wanted) {
                if (hasRole(one)) {
                    return true;
                }
            }
            return false;
        }

        public boolean isAuthenticated() {
            return true;
        }

        public boolean permitAll() {
            return true;
        }

        public boolean denyAll() {
            return false;
        }
    }
}
