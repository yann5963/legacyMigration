package com.migration.repository;

import com.migration.model.MigrationFileStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MigrationFileStatusRepository extends JpaRepository<MigrationFileStatus, Long> {
}
