package com.migration.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectConfiguration {

    @Id
    private Long id;

    private String targetJavaVersion;
    private String targetSpringBootVersion;
    private String targetGroupId;
    private String targetArtifactId;
    private String targetProjectPath;
}
