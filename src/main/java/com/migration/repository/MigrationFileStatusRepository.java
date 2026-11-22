package com.migration.repository;

import com.migration.model.MigrationFileStatus;
import com.migration.model.MigrationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface MigrationFileStatusRepository extends JpaRepository<MigrationFileStatus, Long> {
    List<MigrationFileStatus> findByStatusIn(Collection<MigrationStatus> statuses);
}
