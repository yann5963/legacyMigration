package com.migration.ai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Service gérant l'indexation et la recherche de contexte dans la base vectorielle (RAG).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RagService {

    private final VectorStore vectorStore;

    /**
     * Indexe un fragment de code ou de contexte dans la base vectorielle.
     *
     * @param content Le contenu à indexer.
     * @param metadata Métadonnées associées (ex: type de fichier, chemin source).
     */
    public void indexContext(String content, java.util.Map<String, Object> metadata) {
        log.debug("Indexation du contenu dans la base vectorielle. Métadonnées : {}", metadata);
        Document document = new Document(content, metadata);
        vectorStore.add(List.of(document));
    }

    /**
     * Recherche les documents les plus pertinents par rapport à une requête.
     *
     * @param query La requête de recherche (ex: un nom de méthode, un concept métier).
     * @param topK Le nombre de résultats à retourner.
     * @return Une chaîne concaténant les contenus pertinents trouvés.
     */
    public String findRelevantContext(String query, int topK) {
        log.debug("Recherche de contexte pertinent pour la requête : {}", query);
        // Dans une implémentation réelle avec Spring AI, on utiliserait le SearchRequest
        // List<Document> results = vectorStore.similaritySearch(SearchRequest.query(query).withTopK(topK));

        List<Document> results = vectorStore.similaritySearch(query); // Simplification pour l'exemple

        return results.stream()
                .limit(topK)
                .map(Document::getContent)
                .collect(Collectors.joining("\n\n---\n\n"));
    }
}
