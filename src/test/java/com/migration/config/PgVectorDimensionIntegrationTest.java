package com.migration.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.autoconfigure.vectorstore.pgvector.PgVectorStoreProperties;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingClient;
import org.springframework.ai.vectorstore.PgVectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "PGVECTOR_TEST_URL", matches = ".+")
class PgVectorDimensionIntegrationTest {

    @Test
    void repairsRealPgvectorThenSupportsSpringAiInsertAndSimilarityWithoutDataLoss() throws Exception {
        var source = new DriverManagerDataSource(System.getenv("PGVECTOR_TEST_URL"),
                System.getenv("PGVECTOR_TEST_USER"), System.getenv("PGVECTOR_TEST_PASSWORD"));
        String schema = "embedding_dimension_test_" + UUID.randomUUID().toString().replace("-", "");
        var admin = new JdbcTemplate(source);
        admin.execute("CREATE SCHEMA " + schema);
        try (var connection = source.getConnection()) {
            var scoped = new SingleConnectionDataSource(connection, true);
            var jdbc = new JdbcTemplate(scoped);
            jdbc.execute("SET search_path TO " + schema + ", public");
            jdbc.execute("""
                    CREATE TABLE vector_store (
                        id uuid PRIMARY KEY, content text, metadata json, embedding vector(1536)
                    )
                    """);
            var properties = new PgVectorStoreProperties();
            properties.setDimensions(768);
            var transactions = new TransactionTemplate(new DataSourceTransactionManager(scoped));
            var synchronizer = new PgVectorDimensionSynchronizer(jdbc, transactions, properties);
            synchronizer.run(null);
            var embedding = mock(EmbeddingClient.class);
            List<Double> values = Collections.nCopies(768, 0.1);
            when(embedding.embed(any(Document.class))).thenReturn(values);
            when(embedding.embed(anyString())).thenReturn(values);
            var store = new PgVectorStore(jdbc, embedding, 768, PgVectorStore.PgDistanceType.COSINE_DISTANCE,
                    false, PgVectorStore.PgIndexType.NONE);
            var document = new Document(UUID.randomUUID().toString(), "Harmless dimension test", Map.of("test", true));
            store.add(List.of(document));
            assertEquals(768, jdbc.queryForObject("SELECT vector_dims(embedding) FROM vector_store", Integer.class));
            assertEquals(1, store.similaritySearch(SearchRequest.query("dimension test").withTopK(1)).size());
            synchronizer.run(null);
            properties.setDimensions(1536);
            assertThrows(IllegalStateException.class, () -> synchronizer.run(null));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM vector_store", Integer.class));
            assertEquals(768, jdbc.queryForObject("SELECT vector_dims(embedding) FROM vector_store", Integer.class));
        } finally {
            admin.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }
}
