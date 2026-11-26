package com.migration.repository;

import com.migration.model.GeneratedArtifact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface GeneratedArtifactRepository extends JpaRepository<GeneratedArtifact, Long> {

    List<GeneratedArtifact> findByFqcnOrderByIdAsc(String fqcn);

    List<GeneratedArtifact> findByFqcnIn(Collection<String> fqcns);

    List<GeneratedArtifact> findByArtifactTypeAndSqlTableNameIsNotNull(String artifactType);
}
