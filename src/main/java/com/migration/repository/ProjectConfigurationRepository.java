package com.migration.repository;

import com.migration.model.ProjectConfiguration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProjectConfigurationRepository extends JpaRepository<ProjectConfiguration, Long> {
}
