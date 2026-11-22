package com.migration.ai;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.migration.dto.FilePreAnalysisResult;
import com.migration.model.ProjectConfiguration;
import com.migration.repository.ProjectConfigurationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.ai.parser.BeanOutputParser;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Service gérant les interactions avec le LLM via Spring AI.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MigrationAiService {

    private final ChatClient chatClient;
    private final ProjectConfigurationRepository projectConfigRepository;

    /**
     * Méthode générique pour interroger le LLM avec un contexte et une requête.
     *
     * @param systemPromptTemplate Le template du System Prompt.
     * @param systemParams Les paramètres pour remplir le template du System Prompt.
     * @param userMessage Le message ou le code de l'utilisateur à analyser/refactoriser.
     * @return La réponse générée par le LLM.
     */
    public String analyzeAndRefactor(String systemPromptTemplate, Map<String, Object> systemParams, String userMessage) {
        return analyzeAndRefactor(systemPromptTemplate, systemParams, userMessage, null, null);
    }

    /**
     * Méthode générique pour interroger le LLM avec un contexte et une requête, et des instructions supplémentaires.
     *
     * @param systemPromptTemplate Le template du System Prompt.
     * @param systemParams Les paramètres pour remplir le template du System Prompt.
     * @param userMessage Le message ou le code de l'utilisateur à analyser/refactoriser.
     * @param additionalInstructions Instructions complémentaires pour le LLM.
     * @return La réponse générée par le LLM.
     */
    public String analyzeAndRefactor(String systemPromptTemplate, Map<String, Object> systemParams, String userMessage, String additionalInstructions, String model) {
        log.debug("Appel au LLM pour refactoring...");

        String finalPromptTemplate = systemPromptTemplate;
        if (additionalInstructions != null && !additionalInstructions.trim().isEmpty()) {
            finalPromptTemplate += "\n\nVoici des directives supplémentaires de l'utilisateur pour ce fichier : " + additionalInstructions;
        }
        SystemPromptTemplate promptTemplate = new SystemPromptTemplate(finalPromptTemplate);
        Message systemMessage = promptTemplate.createMessage(systemParams);
        UserMessage userMsg = new UserMessage(userMessage);

        String modelToUse = model;
        if (modelToUse == null || modelToUse.trim().isEmpty()) {
            ProjectConfiguration config = projectConfigRepository.findById(1L).orElse(null);
            if (config != null && config.getDefaultLlmModel() != null && !config.getDefaultLlmModel().isEmpty()) {
                modelToUse = config.getDefaultLlmModel();
            }
        }

        Prompt prompt;
        if (modelToUse != null && !modelToUse.trim().isEmpty()) {
            OllamaOptions options = OllamaOptions.create().withModel(modelToUse);
            prompt = new Prompt(List.of(systemMessage, userMsg), options);
        } else {
            prompt = new Prompt(List.of(systemMessage, userMsg));
        }

        // Dans une implémentation complète, nous pourrions configurer les Function Calls ici.
        String response = chatClient.call(prompt).getResult().getOutput().getContent();

        log.debug("Réponse du LLM reçue.");
        return response;
    }

    /**
     * Demande au LLM de valider une ambiguïté ou de proposer des choix.
     * Si l'application est configurée pour demander une validation humaine, ce service pourrait retourner
     * le doute au pipeline qui, lui, interrogera l'humain.
     */
    public String askForClarification(String context, String ambiguity) {
        String systemPrompt = "Tu es un assistant de migration expert. Voici un problème ambigu que nous avons rencontré : {ambiguity}. Analyse le contexte : {context}. Propose la meilleure solution ou demande une confirmation explicite.";
        return analyzeAndRefactor(systemPrompt, Map.of("ambiguity", ambiguity, "context", context), "Que devons-nous faire ?");
    }

    /**
     * Effectue une pré-analyse sémantique structurée d'un fichier legacy via Spring AI (BeanOutputParser).
     *
     * @param fileName   Nom du fichier source.
     * @param fileType   Type/extension du fichier (JAVA, JSP, XML, PROPERTIES...).
     * @param sourceCode Contenu du fichier.
     * @param model      Modèle Ollama optionnel (sinon modèle par défaut configuré).
     * @return Résultat structuré {@link FilePreAnalysisResult}.
     */
    public FilePreAnalysisResult preAnalyzeFile(String fileName, String fileType, String sourceCode, String model) {
        log.debug("Pré-analyse sémantique LLM du fichier {} ({})...", fileName, fileType);

        ObjectMapper lenientMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);
        BeanOutputParser<FilePreAnalysisResult> outputParser =
                new BeanOutputParser<>(FilePreAnalysisResult.class, lenientMapper);

        String systemPromptText = """
                Tu es un architecte logiciel senior spécialisé dans la migration d'applications legacy J2EE / Struts / JDBC / JSP vers Spring Boot 3.
                Ta seule tâche est d'analyser le fichier source fourni et de répondre EXCLUSIVEMENT par un objet JSON valide (aucun texte, aucun commentaire, aucun markdown autour).

                Valeurs attendues :
                - architecturalRole : STRUTS_ACTION | BUSINESS_SERVICE | JDBC_DAO | DTO_MODEL | UTILITY | JSP_VIEW | CONFIG_XML | SERVLET | FILTER | TRIVIAL | OTHER
                - migrationComplexity : TRIVIAL | LOW | MEDIUM | HIGH | CRITICAL
                - functionalSummary : résumé métier et technique concis de 5 à 10 lignes en français
                - targetSpringArtifact : RestController | Service | Repository | Entity/DTO | Thymeleaf/Front | Configuration | ToDelete
                - detectedRisks : tableau JSON de chaînes décrivant les risques legacy (ex: ["SQL dynamique", "Couplage HttpSession"])
                - suggestedFunctionalLot : domaine fonctionnel métier déduit (ex: "Facturation", "GestionServices")

                {format}
                """;

        SystemPromptTemplate promptTemplate = new SystemPromptTemplate(systemPromptText);
        Message systemMessage = promptTemplate.createMessage(Map.of("format", outputParser.getFormat()));

        // Limiter la taille envoyée pour rester largement dans la fenêtre de contexte (num_ctx=8192)
        String contentToAnalyze = sourceCode != null && sourceCode.length() > 8000
                ? sourceCode.substring(0, 8000) + "\n\n/* ... contenu tronqué pour pré-analyse ... */"
                : (sourceCode != null ? sourceCode : "");

        String userContent = """
                Analyse le fichier suivant :
                - Nom : %s
                - Type : %s

                Code source :
                ```
                %s
                ```

                Réponds UNIQUEMENT par un objet JSON brut commençant par { et finissant par }, avec exactement ces 6 champs :
                {
                  "architecturalRole": "...",
                  "migrationComplexity": "...",
                  "functionalSummary": "...",
                  "targetSpringArtifact": "...",
                  "detectedRisks": ["..."],
                  "suggestedFunctionalLot": "..."
                }
                """.formatted(fileName, fileType, contentToAnalyze);
        UserMessage userMessage = new UserMessage(userContent);

        String modelToUse = resolveModel(model);
        OllamaOptions options = OllamaOptions.create()
                .withTemperature(0.1f)
                .withNumCtx(8192);
        if (modelToUse != null && !modelToUse.isBlank()) {
            options = options.withModel(modelToUse);
        }

        Prompt prompt = new Prompt(List.of(systemMessage, userMessage), options);
        String rawResponse = chatClient.call(prompt).getResult().getOutput().getContent();

        try {
            return outputParser.parse(extractJsonPayload(rawResponse));
        } catch (Exception firstError) {
            log.debug("Premier parsing JSON échoué pour {}, tentative de reformattage JSON...", fileName);
            UserMessage repairMessage = new UserMessage("""
                    Convertis l'analyse suivante en UN SEUL objet JSON valide (rien avant {, rien après }) respectant strictement ces 6 clés :
                    architecturalRole, migrationComplexity, functionalSummary, targetSpringArtifact, detectedRisks (tableau de chaînes), suggestedFunctionalLot.

                    Analyse à convertir :
                    """ + rawResponse);
            Prompt repairPrompt = new Prompt(List.of(systemMessage, repairMessage), options);
            String repairedResponse = chatClient.call(repairPrompt).getResult().getOutput().getContent();
            return outputParser.parse(extractJsonPayload(repairedResponse));
        }
    }

    private String resolveModel(String requestedModel) {
        if (requestedModel != null && !requestedModel.isBlank()) {
            return requestedModel.trim();
        }
        ProjectConfiguration config = projectConfigRepository.findById(1L).orElse(null);
        if (config != null && config.getDefaultLlmModel() != null && !config.getDefaultLlmModel().isBlank()) {
            return config.getDefaultLlmModel().trim();
        }
        return null;
    }

    private String extractJsonPayload(String rawResponse) {
        if (rawResponse == null) {
            return "";
        }
        String trimmed = rawResponse.trim();
        if (trimmed.startsWith("```json")) {
            trimmed = trimmed.substring(7);
        } else if (trimmed.startsWith("```")) {
            trimmed = trimmed.substring(3);
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        trimmed = trimmed.trim();
        int firstBrace = trimmed.indexOf('{');
        int lastBrace = trimmed.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return trimmed.substring(firstBrace, lastBrace + 1);
        }
        return trimmed;
    }
}
