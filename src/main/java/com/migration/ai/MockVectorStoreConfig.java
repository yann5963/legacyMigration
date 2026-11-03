package com.migration.ai;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.embedding.EmbeddingClient;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import java.util.List;
@Configuration
@Profile("test")
public class MockVectorStoreConfig {
    @Bean("vectorStore")
    @Primary
    public VectorStore vectorStore() {
        return new SimpleVectorStore(new EmbeddingClient() {
            @Override
            public List<Double> embed(String document) { return List.of(1.0); }
            @Override
            public EmbeddingResponse call(EmbeddingRequest request) { return null; }
            @Override
            public List<Double> embed(org.springframework.ai.document.Document document) { return List.of(1.0); }
        });
    }
}
