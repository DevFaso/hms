package com.example.hms.repository.support;

import com.example.hms.security.tenant.specification.TenantScopeSpecification;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.TypedQuery;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.support.JpaEntityInformation;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.io.Serializable;
import java.util.Optional;

/**
 * Custom Spring Data repository base class that automatically applies the tenant scope specification to every query.
 */
@Slf4j
@SuppressWarnings("java:S119")
public class TenantAwareJpaRepository<T, ID extends Serializable> extends SimpleJpaRepository<T, ID> {

    private final JpaEntityInformation<T, ?> entityInformation;

    public TenantAwareJpaRepository(JpaEntityInformation<T, ?> entityInformation, EntityManager entityManager) {
        super(entityInformation, entityManager);
        this.entityInformation = entityInformation;
    }

    private <S extends T> Specification<S> augmentWithTenantScope(@Nullable Specification<S> specification, Class<S> domainClass) {
        Specification<S> tenantSpec = new TenantScopeSpecification<>(domainClass);
        return specification == null ? tenantSpec : specification.and(tenantSpec);
    }

    @Override
    @NonNull
    protected TypedQuery<T> getQuery(@Nullable Specification<T> spec, @NonNull Sort sort) {
        return super.getQuery(augmentWithTenantScope(spec, entityInformation.getJavaType()), sort);
    }

    @Override
    @NonNull
    protected TypedQuery<T> getQuery(@Nullable Specification<T> spec, @NonNull Pageable pageable) {
        return super.getQuery(augmentWithTenantScope(spec, entityInformation.getJavaType()), pageable);
    }

    @Override
    @NonNull
    protected <S extends T> TypedQuery<S> getQuery(@Nullable Specification<S> spec,
                                                   @NonNull Class<S> domainClass,
                                                   @NonNull Sort sort) {
        return super.getQuery(augmentWithTenantScope(spec, domainClass), domainClass, sort);
    }

    @Override
    @NonNull
    protected <S extends T> TypedQuery<S> getQuery(@Nullable Specification<S> spec,
                                                   @NonNull Class<S> domainClass,
                                                   @NonNull Pageable pageable) {
        return super.getQuery(augmentWithTenantScope(spec, domainClass), domainClass, pageable);
    }

    @Override
    @SuppressWarnings("deprecation")
    @NonNull
    protected TypedQuery<Long> getCountQuery(@Nullable Specification<T> spec) {
        return super.getCountQuery(augmentWithTenantScope(spec, entityInformation.getJavaType()));
    }

    @Override
    @NonNull
    protected <S extends T> TypedQuery<Long> getCountQuery(@Nullable Specification<S> spec,
                                                           @NonNull Class<S> domainClass) {
        return super.getCountQuery(augmentWithTenantScope(spec, domainClass), domainClass);
    }

    @Override
    @NonNull
    public Optional<T> findById(@NonNull ID id) {
        Specification<T> idSpec = (root, query, builder) -> builder.equal(root.get(resolveIdAttributeName()), id);
        return super.findOne(idSpec);
    }

    @Override
    public boolean existsById(@NonNull ID id) {
        return findById(id).isPresent();
    }

    @Override
    @NonNull
    public T getReferenceById(@NonNull ID id) {
        return findById(id)
            .orElseThrow(() -> new EntityNotFoundException(entityInformation.getJavaType().getSimpleName() + " with id " + id + " not accessible in current tenant scope"));
    }

    private String resolveIdAttributeName() {
        var idAttribute = entityInformation.getIdAttribute();
        if (idAttribute != null) {
            return idAttribute.getName();
        }
        return entityInformation.getIdAttributeNames().iterator().next();
    }
}
