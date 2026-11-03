package com.example.hms.utility;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Id;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * QA D1361 (round 31, the D1347 class): a page cut from an order that is not
 * total is not a page. {@code GET /lab-results} asked its query for a page
 * with no order at all, and PostgreSQL returned the rows in a different
 * order for every OFFSET: read page by page, 16 of 202 results came back
 * twice and 16 never came back. A page sorted on a key that is not unique
 * (a date many rows share) fails the same way at its ties.
 *
 * <p>Rule: a paged repository read always ends its order with the root's
 * primary key {@code id}, the one key every entity carries and no two rows
 * share. {@link com.example.hms.config.StablePageOrderConfig} applies
 * {@link #forQuery} to the {@code Pageable} of EVERY repository call, so no
 * caller can forget it:</p>
 * <ul>
 *   <li>a sort that already names {@code id} is kept as it is;</li>
 *   <li>a caller's sort is kept and closed by {@code id}, in the direction
 *       of its last key;</li>
 *   <li>no sort, and the query has its own order ({@code ORDER BY}, or an
 *       {@code OrderBy} method name): Spring Data appends the page's sort
 *       AFTER the query's own, so {@code id} only breaks its ties;</li>
 *   <li>no sort and no order at all: newest first ({@code createdAt}
 *       descending, then {@code id}) when the query's root entity has a
 *       {@code createdAt}, else {@code id} alone.</li>
 * </ul>
 * <p>An unpaged request has no page boundary to keep, and a native query is
 * plain SQL whose sort Spring cannot qualify reliably; both are returned as
 * they are (the D1361 guard in {@code SortedPagePostgresIT} names every
 * paged native query, so none slips through unordered).</p>
 */
public final class StablePageOrder {

    /** The unique key every entity carries. */
    public static final String UNIQUE_KEY = "id";
    /** The newest-first key of {@code BaseEntity} and most other entities. */
    public static final String CREATED_AT = "createdAt";

    private static final Pattern ORDER_BY = Pattern.compile("\\border\\s++by\\b");
    private static final Pattern FROM_ENTITY = Pattern.compile("\\bfrom\\s++([\\w.]++)");
    private static final Pattern ENDS_ON_ID = Pattern.compile(
        "\\.id(?:\\s++(?:asc|desc))?(?:\\s++nulls\\s++(?:first|last))?$");

    private StablePageOrder() {
    }

    /**
     * The page a repository method should run with.
     *
     * @param pageable   the caller's page (may be null or unpaged)
     * @param domainType the repository's entity
     * @param method     the repository method being called
     */
    public static Pageable forQuery(Pageable pageable, Class<?> domainType, Method method) {
        if (pageable == null || pageable.isUnpaged()) {
            return pageable;
        }
        Query query = method.getAnnotation(Query.class);
        if (query != null && query.nativeQuery()) {
            return pageable;
        }
        Sort sort = pageable.getSort();
        Class<?> root = rootOf(domainType, query);
        String[] keys = uniqueKeys(root);
        if (sort.getOrderFor(keys[keys.length - 1]) != null || (sort.isUnsorted() && endsOnId(query))) {
            return pageable;
        }
        Sort stable;
        if (sort.isSorted()) {
            stable = sort.and(Sort.by(lastDirection(sort), keys));
        } else if (hasOwnOrder(method, query)) {
            stable = Sort.by(Sort.Direction.ASC, keys);
        } else if (hasField(root, CREATED_AT)) {
            stable = Sort.by(Sort.Direction.DESC, CREATED_AT).and(Sort.by(Sort.Direction.DESC, keys));
        } else {
            stable = Sort.by(Sort.Direction.ASC, keys);
        }
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), stable);
    }

    /**
     * The entity's primary key, as sort properties: {@code id} for every
     * entity that has one (an {@code @EmbeddedId} called {@code id} included),
     * else its {@code @Id} fields (an {@code @IdClass} key, a natural key).
     */
    static String[] uniqueKeys(Class<?> type) {
        if (type == null || hasField(type, UNIQUE_KEY)) {
            return new String[]{UNIQUE_KEY};
        }
        List<String> keys = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (field.isAnnotationPresent(Id.class) || field.isAnnotationPresent(EmbeddedId.class)) {
                    keys.add(field.getName());
                }
            }
        }
        return keys.isEmpty() ? new String[]{UNIQUE_KEY} : keys.toArray(String[]::new);
    }

    private static Sort.Direction lastDirection(Sort sort) {
        Sort.Direction last = Sort.Direction.ASC;
        for (Sort.Order order : sort) {
            last = order.getDirection();
        }
        return last;
    }

    /** The query's own ORDER BY already ends on an {@code id}: it is total as it is. */
    private static boolean endsOnId(Query query) {
        if (query == null) {
            return false;
        }
        String jpql = query.value().toLowerCase(Locale.ROOT).strip();
        int order = jpql.lastIndexOf("order by");
        return order >= 0 && ENDS_ON_ID.matcher(jpql.substring(order)).find();
    }

    private static boolean hasOwnOrder(Method method, Query query) {
        if (query != null) {
            return ORDER_BY.matcher(query.value().toLowerCase(Locale.ROOT)).find();
        }
        return method.getName().contains("OrderBy");
    }

    /**
     * The entity the query selects from: the repository's entity for a
     * derived or inherited method, or when the query's first {@code FROM}
     * names it; null when that {@code FROM} names another entity.
     */
    private static Class<?> rootOf(Class<?> domainType, Query query) {
        if (query == null || query.value().isBlank()) {
            return domainType;
        }
        Matcher from = FROM_ENTITY.matcher(query.value().toLowerCase(Locale.ROOT));
        if (domainType == null || !from.find()) {
            return null;
        }
        String entity = from.group(1);
        entity = entity.substring(entity.lastIndexOf('.') + 1);
        return entity.equals(domainType.getSimpleName().toLowerCase(Locale.ROOT)) ? domainType : null;
    }

    private static boolean hasField(Class<?> type, String name) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (name.equals(field.getName())) {
                    return true;
                }
            }
        }
        return false;
    }
}
