sed -i '/import java.util.List;/a import java.util.ArrayList;\nimport jakarta.persistence.OneToMany;\nimport jakarta.persistence.CascadeType;' src/main/java/com/migration/model/MigrationFileStatus.java

sed -i '/private String errorMessage;/a \n    @OneToMany(mappedBy = "migrationFileStatus", cascade = CascadeType.ALL, fetch = FetchType.LAZY)\n    @Builder.Default\n    private List<FileChunkStatus> chunks = new ArrayList<>();' src/main/java/com/migration/model/MigrationFileStatus.java
