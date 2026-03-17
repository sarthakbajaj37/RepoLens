package com.repolens.repository;

import com.repolens.entity.RepositoryMetadata;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Spring Data JPA repository for RepositoryMetadata entity.
 */
public interface RepositoryMetadataRepository extends JpaRepository<RepositoryMetadata, Long> {

    Optional<RepositoryMetadata> findByOwnerAndName(String owner, String name);

    boolean existsByOwnerAndName(String owner, String name);
}
