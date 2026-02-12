package com.example.hms.specification;

import com.example.hms.model.Permission;
import com.example.hms.payload.dto.PermissionFilterDTO;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Fetch;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PermissionSpecificationTest {

    private static final String ASSIGNMENT = "assignment";
    private static final String ROLE = "role";
    private static final String HOSPITAL = "hospital";
    private static final String NAME = "name";

    @Mock
    private Root<Permission> root;

    @Mock
    private CriteriaBuilder criteriaBuilder;

    @Mock
    private CriteriaQuery<Permission> entityQuery;

    @Mock
    private CriteriaQuery<Long> countQuery;

    @Test
    void buildWhenSelectingEntitiesFetchesAssociationsAndBuildsPredicate() {
        var assignmentId = UUID.randomUUID();
        var filter = new PermissionFilterDTO(assignmentId, "Clinical Ops");

        when(entityQuery.getResultType()).thenReturn(Permission.class);
        when(entityQuery.distinct(true)).thenReturn(entityQuery);

        Predicate conjunction = mock(Predicate.class);
        when(criteriaBuilder.conjunction()).thenReturn(conjunction);

        Fetch<Object, Object> assignmentFetch = mock(Fetch.class);
        Fetch<Object, Object> roleFetch = mock(Fetch.class);
        Fetch<Object, Object> hospitalFetch = mock(Fetch.class);
        when(root.fetch(ASSIGNMENT, JoinType.LEFT)).thenReturn((Fetch) assignmentFetch);
        when(assignmentFetch.fetch(ROLE, JoinType.LEFT)).thenReturn((Fetch) roleFetch);
        when(assignmentFetch.fetch(HOSPITAL, JoinType.LEFT)).thenReturn((Fetch) hospitalFetch);

        Path<Object> assignmentPath = mock(Path.class);
        Path<Object> assignmentIdPath = mock(Path.class);
        when(root.get(ASSIGNMENT)).thenReturn((Path) assignmentPath);
        when(assignmentPath.get("id")).thenReturn(assignmentIdPath);

        Predicate assignmentPredicate = mock(Predicate.class);
        when(criteriaBuilder.equal(assignmentIdPath, assignmentId)).thenReturn(assignmentPredicate);
        Predicate afterAssignment = mock(Predicate.class);
        when(criteriaBuilder.and(conjunction, assignmentPredicate)).thenReturn(afterAssignment);

        Path<String> namePath = mock(Path.class);
        when(root.get(NAME)).thenReturn((Path) namePath);
        Expression<String> lowerName = mock(Expression.class);
        when(criteriaBuilder.lower(namePath)).thenReturn(lowerName);
        Predicate namePredicate = mock(Predicate.class);
        when(criteriaBuilder.like(lowerName, "%clinical ops%"))
            .thenReturn(namePredicate);

        Predicate combined = mock(Predicate.class);
        when(criteriaBuilder.and(afterAssignment, namePredicate)).thenReturn(combined);

        Predicate result = PermissionSpecification.build(filter).toPredicate(root, entityQuery, criteriaBuilder);

        assertThat(result).isSameAs(combined);

        InOrder inOrder = inOrder(entityQuery);
        inOrder.verify(entityQuery).getResultType();
        inOrder.verify(entityQuery).distinct(true);

        verify(root).fetch(ASSIGNMENT, JoinType.LEFT);
        verify(assignmentFetch).fetch(ROLE, JoinType.LEFT);
        verify(assignmentFetch).fetch(HOSPITAL, JoinType.LEFT);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void buildWhenQueryNotForPermissionSkipsFetchAndReturnsBasePredicate() {
        var filter = new PermissionFilterDTO();
        when(countQuery.getResultType()).thenReturn(Long.class);

        Predicate conjunction = mock(Predicate.class);
        when(criteriaBuilder.conjunction()).thenReturn(conjunction);

        Predicate result = PermissionSpecification.build(filter)
            .toPredicate(root, (CriteriaQuery) countQuery, criteriaBuilder);

        assertThat(result).isSameAs(conjunction);
        verify(root, never()).fetch(anyString(), any());
        verify(countQuery, never()).distinct(true);
    }
}
