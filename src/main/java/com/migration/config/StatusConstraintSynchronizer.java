package com.migration.config;

import com.migration.model.MigrationStatus;
import com.migration.model.ParityStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Synchronise la contrainte CHECK PostgreSQL sur la colonne {@code status} de {@code migration_file_status}
 * avec toutes les valeurs de l'enum {@link MigrationStatus}.
 *
 * Hibernate 6 génère cette contrainte lors de la création initiale de la table, mais {@code ddl-auto: update}
 * ne la met pas à jour lorsque de nouvelles valeurs d'enum sont ajoutées.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StatusConstraintSynchronizer implements ApplicationRunner {

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try (Connection conn = dataSource.getConnection()) {
            String dbProduct = conn.getMetaData().getDatabaseProductName();
            if (dbProduct == null || !dbProduct.toLowerCase().contains("postgresql")) {
                return;
            }

            String allowedValues = Arrays.stream(MigrationStatus.values())
                    .map(Enum::name)
                    .map(v -> "'" + v + "'")
                    .collect(Collectors.joining(", "));

            jdbcTemplate.execute(
                    "ALTER TABLE migration_file_status DROP CONSTRAINT IF EXISTS migration_file_status_status_check");
            jdbcTemplate.execute(
                    "ALTER TABLE migration_file_status ADD CONSTRAINT migration_file_status_status_check CHECK (status IN ("
                            + allowedValues + "))");
            jdbcTemplate.execute(
                    "ALTER TABLE migration_file_status ALTER COLUMN last_custom_prompt TYPE varchar(20000)");

            String allowedParityValues = Arrays.stream(ParityStatus.values())
                    .map(Enum::name)
                    .map(v -> "'" + v + "'")
                    .collect(Collectors.joining(", "));
            jdbcTemplate.execute(
                    "ALTER TABLE migration_file_status DROP CONSTRAINT IF EXISTS migration_file_status_parity_status_check");
            jdbcTemplate.execute(
                    "ALTER TABLE migration_file_status ADD CONSTRAINT migration_file_status_parity_status_check CHECK (parity_status IS NULL OR parity_status IN ("
                            + allowedParityValues + "))");

            log.info("Contrainte PostgreSQL migration_file_status_status_check synchronisée avec {} statuts.",
                    MigrationStatus.values().length);
        } catch (Exception e) {
            log.warn("Impossible de synchroniser la contrainte migration_file_status_status_check : {}", e.getMessage());
        }
    }
}
