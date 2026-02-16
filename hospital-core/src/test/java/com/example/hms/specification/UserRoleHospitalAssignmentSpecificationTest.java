package com.example.hms.specification;

import com.example.hms.model.UserRoleHospitalAssignment;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.domain.Specification;

import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserRoleHospitalAssignmentSpecificationTest {

    private static final String HOSPITAL = "hospital";
    private static final String USER = "user";
    private static final String ROLE = "role";
    private static final String REGISTERED_BY = "registeredBy";
    private static final String ASSIGNMENT_CODE = "assignmentCode";

    @Mock
    private Root<UserRoleHospitalAssignment> root;

    @Mock
    private CriteriaQuery<UserRoleHospitalAssignment> query;

    @Mock
    private CriteriaBuilder criteriaBuilder;

    @Test
    void belongsToHospitalWhenNullReturnsNull() {
        assertThat(UserRoleHospitalAssignmentSpecification.belongsToHospital(null)).isNull();
    }

    @Test
    void belongsToHospitalWhenIdProvidedBuildsPredicate() {
        UUID hospitalId = UUID.randomUUID();
        Specification<UserRoleHospitalAssignment> spec = UserRoleHospitalAssignmentSpecification.belongsToHospital(hospitalId);

    @SuppressWarnings("unchecked")
    Join<Object, Object> hospitalJoin = mock(Join.class);
    Path<Object> idPath = mock(Path.class);
        Predicate expected = mock(Predicate.class);

    when(root.join(HOSPITAL, JoinType.LEFT)).thenReturn((Join) hospitalJoin);
    when(hospitalJoin.get("id")).thenReturn((Path) idPath);
        when(criteriaBuilder.equal(idPath, hospitalId)).thenReturn(expected);
        when(query.distinct(true)).thenReturn(query);

        Predicate result = spec.toPredicate(root, query, criteriaBuilder);

        assertThat(result).isSameAs(expected);
        verify(query).distinct(true);
        verify(root).join(HOSPITAL, JoinType.LEFT);
    }

    @Test
    void hasActiveWhenNullReturnsNull() {
        assertThat(UserRoleHospitalAssignmentSpecification.hasActive(null)).isNull();
    }

    @Test
    void hasActiveWhenFlagProvidedBuildsEqualityPredicate() {
        Specification<UserRoleHospitalAssignment> spec = UserRoleHospitalAssignmentSpecification.hasActive(Boolean.TRUE);
        Predicate expected = mock(Predicate.class);
    Path<Object> activePath = mock(Path.class);

    when(root.get("active")).thenReturn((Path) activePath);
        when(criteriaBuilder.equal(activePath, Boolean.TRUE)).thenReturn(expected);

        Predicate result = spec.toPredicate(root, query, criteriaBuilder);

        assertThat(result).isSameAs(expected);
    }

    @Test
    void matchesSearchWhenBlankReturnsNull() {
        assertThat(UserRoleHospitalAssignmentSpecification.matchesSearch("   ")).isNull();
    }

    @Test
    void matchesSearchWhenTermProvidedBuildsOrPredicateWithLowercasePattern() {
    String rawTerm = "Admin";
        Specification<UserRoleHospitalAssignment> spec = UserRoleHospitalAssignmentSpecification.matchesSearch(rawTerm);

    Join<Object, Object> userJoin = mock(Join.class);
    Join<Object, Object> hospitalJoin = mock(Join.class);
    Join<Object, Object> roleJoin = mock(Join.class);
    Join<Object, Object> registrarJoin = mock(Join.class);
        Predicate orPredicate = mock(Predicate.class);

    when(root.join(USER, JoinType.LEFT)).thenReturn((Join) userJoin);
    when(root.join(HOSPITAL, JoinType.LEFT)).thenReturn((Join) hospitalJoin);
    when(root.join(ROLE, JoinType.LEFT)).thenReturn((Join) roleJoin);
    when(root.join(REGISTERED_BY, JoinType.LEFT)).thenReturn((Join) registrarJoin);
        when(query.distinct(true)).thenReturn(query);

    Path<Object> userPath = mock(Path.class);
    Path<Object> hospitalPath = mock(Path.class);
    Path<Object> rolePath = mock(Path.class);
    Path<Object> registrarPath = mock(Path.class);
    Path<Object> assignmentCodePath = mock(Path.class);

    when(userJoin.get("username")).thenReturn((Path) userPath);
    when(userJoin.get("email")).thenReturn((Path) mock(Path.class));
    when(userJoin.get("firstName")).thenReturn((Path) mock(Path.class));
    when(userJoin.get("lastName")).thenReturn((Path) mock(Path.class));
    when(roleJoin.get("name")).thenReturn((Path) rolePath);
    when(roleJoin.get("code")).thenReturn((Path) mock(Path.class));
    when(root.get(ASSIGNMENT_CODE)).thenReturn((Path) assignmentCodePath);
    when(hospitalJoin.get("name")).thenReturn((Path) hospitalPath);
    when(hospitalJoin.get("code")).thenReturn((Path) mock(Path.class));
    when(registrarJoin.get("username")).thenReturn((Path) registrarPath);
    when(registrarJoin.get("email")).thenReturn((Path) mock(Path.class));
    when(registrarJoin.get("firstName")).thenReturn((Path) mock(Path.class));
    when(registrarJoin.get("lastName")).thenReturn((Path) mock(Path.class));

    when(criteriaBuilder.lower(any(Expression.class))).thenAnswer(inv -> mock(Expression.class));
    when(criteriaBuilder.like(any(Expression.class), any(String.class))).thenAnswer(inv -> mock(Predicate.class));
        when(criteriaBuilder.or(any(Predicate[].class))).thenReturn(orPredicate);

        Predicate result = spec.toPredicate(root, query, criteriaBuilder);

        assertThat(result).isSameAs(orPredicate);
        verify(query).distinct(true);
        verify(root, atLeastOnce()).join(USER, JoinType.LEFT);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(criteriaBuilder, atLeastOnce()).like(any(Expression.class), captor.capture());
        assertThat(captor.getAllValues()).contains("%admin%".toLowerCase(Locale.ROOT));
    }

    @Test
    void hasAssignmentCodeWhenBlankReturnsNull() {
        assertThat(UserRoleHospitalAssignmentSpecification.hasAssignmentCode(" ")).isNull();
    }

    @Test
    void hasAssignmentCodeNormalizesValueBeforeComparison() {
        Specification<UserRoleHospitalAssignment> spec = UserRoleHospitalAssignmentSpecification.hasAssignmentCode(" AC-01 ");
    Path<String> codePath = mock(Path.class);
        Expression<String> lowered = mock(Expression.class);
        Predicate expected = mock(Predicate.class);

    when(root.get(ASSIGNMENT_CODE)).thenReturn((Path) codePath);
        when(criteriaBuilder.lower(codePath)).thenReturn(lowered);
        when(criteriaBuilder.equal(lowered, "ac-01".toLowerCase(Locale.ROOT))).thenReturn(expected);

        Predicate result = spec.toPredicate(root, query, criteriaBuilder);

        assertThat(result).isSameAs(expected);
    }
}
