package com.migration.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.autoconfigure.vectorstore.pgvector.PgVectorStoreProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PgVectorDimensionSynchronizerTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);
    private final PgVectorStoreProperties properties = new PgVectorStoreProperties();
    private PgVectorDimensionSynchronizer synchronizer;

    @BeforeEach
    void setup() {
        properties.setDimensions(768);
        doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactions).executeWithoutResult(any());
        synchronizer = new PgVectorDimensionSynchronizer(jdbc, transactions, properties);
    }

    @Test
    void fixesOnlyAnEmptyTableAndIsIdempotent() {
        when(jdbc.queryForList(anyString(), eq(String.class)))
                .thenReturn(List.of("vector(1536)"), List.of("vector(1536)"), List.of("vector(768)"));
        when(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM vector_store)", Boolean.class)).thenReturn(false);
        synchronizer.run(null);
        synchronizer.run(null);
        var order = inOrder(jdbc);
        order.verify(jdbc).queryForList(anyString(), eq(String.class));
        order.verify(jdbc).execute("SET LOCAL lock_timeout = '10s'");
        order.verify(jdbc).execute("LOCK TABLE vector_store IN ACCESS EXCLUSIVE MODE");
        order.verify(jdbc).queryForList(anyString(), eq(String.class));
        order.verify(jdbc).queryForObject("SELECT EXISTS (SELECT 1 FROM vector_store)", Boolean.class);
        order.verify(jdbc).execute("ALTER TABLE vector_store ALTER COLUMN embedding TYPE vector(768)");
        verify(transactions, times(1)).executeWithoutResult(any());
    }

    @Test
    void neverTruncatesOrDropsExistingVectorsWhenDimensionsMismatch() {
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(List.of("vector(1536)"));
        when(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM vector_store)", Boolean.class)).thenReturn(true);
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> synchronizer.run(null));
        assertTrue(error.getMessage().contains("vector(1536)"));
        assertTrue(error.getMessage().contains("vector(768)"));
        assertTrue(error.getMessage().contains("sauvegardez"));
        verify(jdbc, never()).execute(startsWith("ALTER TABLE"));
        verify(jdbc, never()).execute(startsWith("DROP"));
        verify(jdbc, never()).execute(startsWith("TRUNCATE"));
    }

    @Test
    void refusesMissingDimensionConfigurationAndMissingColumn() {
        properties.setDimensions(-1);
        assertThrows(IllegalStateException.class, () -> synchronizer.run(null));
        verifyNoInteractions(jdbc);
        properties.setDimensions(768);
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(List.of());
        assertThrows(IllegalStateException.class, () -> synchronizer.run(null));
        verifyNoInteractions(transactions);
    }
}
