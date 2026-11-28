package com.migration.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.autoconfigure.vectorstore.pgvector.PgVectorStoreProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@Profile("!test")
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
@Slf4j
public class PgVectorDimensionSynchronizer implements ApplicationRunner {

    private static final String COLUMN_TYPE_SQL = """
            SELECT format_type(a.atttypid, a.atttypmod)
            FROM pg_attribute a
            WHERE a.attrelid = to_regclass('vector_store')
              AND a.attname = 'embedding' AND a.attnum > 0 AND NOT a.attisdropped
            """;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final PgVectorStoreProperties properties;

    @Override
    public void run(ApplicationArguments args) {
        int dimensions = properties.getDimensions();
        if (dimensions <= 0) {
            throw new IllegalStateException("Configurez explicitement spring.ai.vectorstore.pgvector.dimensions "
                    + "selon le modele d'embedding (768 pour nomic-embed-text).");
        }
        String expectedType = "vector(" + dimensions + ")";
        String actualType = columnType();
        if (expectedType.equals(actualType)) {
            return;
        }
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.execute("SET LOCAL lock_timeout = '10s'");
            jdbcTemplate.execute("LOCK TABLE vector_store IN ACCESS EXCLUSIVE MODE");
            String lockedType = columnType();
            if (expectedType.equals(lockedType)) {
                return;
            }
            if (Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM vector_store)", Boolean.class))) {
                throw new IllegalStateException("Dimensions pgvector incompatibles : vector_store.embedding est "
                        + lockedType + ", configuration attendue " + expectedType
                        + ". La table contient des donnees : sauvegardez-la puis regenerez les embeddings "
                        + "avec le modele configure. Aucune donnee n'a ete supprimee.");
            }
            // Changing the declared size is safe only when no existing vectors can be truncated.
            jdbcTemplate.execute("ALTER TABLE vector_store ALTER COLUMN embedding TYPE " + expectedType);
            log.info("Table vector_store vide : dimension corrigee de {} vers {}.", lockedType, expectedType);
        });
    }

    private String columnType() {
        var types = jdbcTemplate.queryForList(COLUMN_TYPE_SQL, String.class);
        if (types.size() != 1) {
            throw new IllegalStateException("Colonne vector_store.embedding introuvable. "
                    + "Verifiez l'initialisation pgvector et le search_path PostgreSQL.");
        }
        return types.get(0);
    }
}
