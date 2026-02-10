package com.example.hms.repository.empi;

import com.example.hms.enums.empi.EmpiAliasType;
import com.example.hms.model.empi.EmpiIdentityAlias;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmpiIdentityAliasRepository extends JpaRepository<EmpiIdentityAlias, UUID> {

    boolean existsByAliasTypeAndAliasValueIgnoreCase(EmpiAliasType aliasType, String aliasValue);

    Optional<EmpiIdentityAlias> findByAliasTypeAndAliasValueIgnoreCase(EmpiAliasType aliasType, String aliasValue);
}
