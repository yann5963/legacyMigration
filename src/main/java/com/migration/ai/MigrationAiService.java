package com.migration.ai;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.migration.config.MigrationPromptsProperties;
import com.migration.dto.FilePreAnalysisResult;
import com.migration.dto.MultiFileConversionResponse;
import com.migration.dto.ReverseCheckReport;
import com.migration.model.ProjectConfiguration;
import com.migration.repository.ProjectConfigurationRepository;
import com.migration.parser.GeneratedArtifactValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.ai.parser.BeanOutputParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Service gérant les interactions avec le LLM via Spring AI.
 */
@Service
@Slf4j
public class MigrationAiService {

    private final ChatClient chatClient;
    private final ProjectConfigurationRepository projectConfigRepository;
    private final MigrationPromptsProperties prompts;

    public MigrationAiService(ChatClient chatClient, ProjectConfigurationRepository projectConfigRepository) {
        this(chatClient, projectConfigRepository, new MigrationPromptsProperties());
    }

    @Autowired
    public MigrationAiService(ChatClient chatClient, ProjectConfigurationRepository projectConfigRepository,
                              MigrationPromptsProperties prompts) {
        this.chatClient = chatClient;
        this.projectConfigRepository = projectConfigRepository;
        this.prompts = prompts == null ? new MigrationPromptsProperties() : prompts;
    }

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
        return analyzeAndRefactor(prompts.getClarification(), Map.of("ambiguity", ambiguity, "context", context),
                "Que devons-nous faire ?");
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
        return preAnalyzeFile(fileName, fileType, sourceCode, model, null);
    }

    /**
     * Pré-analyse sémantique structurée d'un fichier legacy via Spring AI (BeanOutputParser).
     * @param fileName Nom du fichier source.
     * @param fileType Type/extension du fichier (JAVA, JSP, XML, PROPERTIES...).
     * @param sourceCode Contenu du fichier.
     * @param model Modèle Ollama optionnel (sinon modèle par défaut configuré).
     * @param rootEntryPoints Points d'entrée racine pour l'analyse (optionnel).
     * @return Résultat structuré {@link FilePreAnalysisResult}.
     */
    public FilePreAnalysisResult preAnalyzeFile(String fileName, String fileType, String sourceCode, String model,
                                               String rootEntryPoints) {
        log.debug("Pré-analyse sémantique LLM du fichier {} ({})...", fileName, fileType);

        ObjectMapper lenientMapper = createLenientObjectMapper();
        BeanOutputParser<FilePreAnalysisResult> outputParser =
                new BeanOutputParser<>(FilePreAnalysisResult.class, lenientMapper);

        String systemPromptText = prompts.getPreAnalysis().getSystem();

        SystemPromptTemplate promptTemplate = new SystemPromptTemplate(systemPromptText);
        Message systemMessage = promptTemplate.createMessage(Map.of("format", outputParser.getFormat()));

        // Limiter la taille envoyée pour rester largement dans la fenêtre de contexte (num_ctx=8192)
        String contentToAnalyze = sourceCode != null && sourceCode.length() > 8000
                ? sourceCode.substring(0, 8000) + "\n\n/* ... contenu tronqué pour pré-analyse ... */"
                : (sourceCode != null ? sourceCode : "");

        /**
         * Contenu de l'utilisateur envoyé au modèle, incluant le nom du fichier, le type et le code source.
         */
        String userContent = prompts.render(prompts.getPreAnalysis().getUser(), Map.of(
                "fileName", fileName,
                "fileType", fileType,
                "content", contentToAnalyze));
        if (rootEntryPoints != null && !rootEntryPoints.isBlank()) {
            userContent = prompts.render(prompts.getPreAnalysis().getRootContext(),
                    Map.of("rootEntryPoints", rootEntryPoints)) + "\n\n" + userContent;
        }
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
            UserMessage repairMessage = new UserMessage(prompts.render(prompts.getPreAnalysis().getRepair(),
                    Map.of("rawResponse", rawResponse)));
            Prompt repairPrompt = new Prompt(List.of(systemMessage, repairMessage), options);
            String repairedResponse = chatClient.call(repairPrompt).getResult().getOutput().getContent();
            return outputParser.parse(extractJsonPayload(repairedResponse));
        }
    }

    /**
     * Effectue une conversion multi-fichiers (1 -> N) vers Spring Boot 3 en sortie structurée JSON.
     *
     * @param systemPrompt Directives complètes de conversion (rôle, risques, dépendances, découpage).
     * @param sourceCode   Code source legacy à convertir.
     * @param model        Modèle Ollama optionnel.
     * @return Réponse structurée {@link MultiFileConversionResponse}.
     */
    public MultiFileConversionResponse convertToMultiFile(String systemPrompt, String sourceCode, String model) {
        log.debug("Appel au LLM pour conversion multi-fichiers...");

        ObjectMapper lenientMapper = createLenientObjectMapper();
        BeanOutputParser<MultiFileConversionResponse> outputParser =
                new BeanOutputParser<>(MultiFileConversionResponse.class, lenientMapper);

        String fullSystemPrompt = systemPrompt + "\n\n"
                + prompts.render(prompts.getConversion().getResponseFormat(),
                Map.of("format", outputParser.getFormat()));
        SystemMessage systemMessage = new SystemMessage(fullSystemPrompt);
        UserMessage userMessage = new UserMessage(prompts.render(prompts.getConversion().getUser(),
                Map.of("sourceCode", sourceCode != null ? sourceCode : "")));

        String modelToUse = resolveModel(model);
        OllamaOptions options = OllamaOptions.create()
                .withTemperature(0.2f)
                .withNumCtx(8192);
        if (modelToUse != null && !modelToUse.isBlank()) {
            options = options.withModel(modelToUse);
        }

        Prompt prompt = new Prompt(List.of(systemMessage, userMessage), options);
        String rawResponse = chatClient.call(prompt).getResult().getOutput().getContent();

        MultiFileConversionResponse parsedResponse = null;
        try {
            parsedResponse = outputParser.parse(extractJsonPayload(rawResponse));
            return GeneratedArtifactValidator.validate(parsedResponse);
        } catch (Exception firstError) {
            log.warn("Reponse de conversion multi-fichiers rejetee, tentative de correction : {}",
                    firstError.getMessage());
            String validationPrefix = parsedResponse != null
                    ? "Le JSON est valide mais le code Java genere est invalide ou incomplet.\n"
                      + "Erreur de validation : " + firstError.getMessage() + "\n"
                      + "Corrige impérativement le champ `code` concerné pour fournir un fichier Java 21 complet et syntaxiquement valide "
                      + "(ex. `public class Nom { ... }` ou `public record Nom(...) { ... }`, sans omettre `class` ni `(...)` sur un `record`).\n\n"
                    : "";
            String repairBody = prompts.render(prompts.getConversion().getRepair(),
                    Map.of("rawResponse", rawResponse == null ? "" : rawResponse));
            String validationSuffix = parsedResponse == null
                    ? "\n\nErreur de validation : " + firstError.getMessage()
                    : "";
            UserMessage repairMessage = new UserMessage(validationPrefix + repairBody + validationSuffix);
            Prompt repairPrompt = new Prompt(List.of(systemMessage, userMessage, repairMessage), options);
            String repairedResponse = chatClient.call(repairPrompt).getResult().getOutput().getContent();
            return GeneratedArtifactValidator.validate(outputParser.parse(extractJsonPayload(repairedResponse)));
        }
    }

    /**
     * Réalise un audit de parité fonctionnelle (Reverse Checking) entre le code source legacy
     * et l'ensemble des artefacts Spring Boot générés, en sortie structurée JSON.
     *
     * @param sourceFileName          Nom du fichier source legacy.
     * @param sourceCode              Contenu du fichier source legacy.
     * @param formattedTargetArtifacts Ensemble formaté des artefacts Spring Boot générés.
     * @param model                   Modèle Ollama optionnel.
     * @return Rapport structuré {@link ReverseCheckReport}.
     */
    public ReverseCheckReport reverseCheck(String sourceFileName, String sourceCode,
                                           String formattedTargetArtifacts, String model) {
        log.debug("Appel au LLM pour Reverse Checking du fichier {}...", sourceFileName);

        ObjectMapper lenientMapper = createLenientObjectMapper();
        BeanOutputParser<ReverseCheckReport> outputParser =
                new BeanOutputParser<>(ReverseCheckReport.class, lenientMapper);

        SystemPromptTemplate systemTemplate = new SystemPromptTemplate(prompts.getReverseCheck().getSystem());
        Message systemMessage = systemTemplate.createMessage(Map.of("format", outputParser.getFormat()));

        String userContent = prompts.render(prompts.getReverseCheck().getUser(), Map.of(
                "sourceFileName", sourceFileName != null ? sourceFileName : "unknown",
                "sourceCode", sourceCode != null ? sourceCode : "",
                "targetArtifacts", formattedTargetArtifacts != null ? formattedTargetArtifacts : ""));
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

        ReverseCheckReport report;
        try {
            report = outputParser.parse(extractJsonPayload(rawResponse));
        } catch (Exception firstError) {
            log.debug("Premier parsing JSON ReverseCheck échoué pour {}, tentative de réparation...", sourceFileName);
            UserMessage repairMessage = new UserMessage(prompts.render(prompts.getReverseCheck().getRepair(),
                    Map.of("rawResponse", rawResponse == null ? "" : rawResponse)));
            Prompt repairPrompt = new Prompt(List.of(systemMessage, repairMessage), options);
            String repairedResponse = chatClient.call(repairPrompt).getResult().getOutput().getContent();
            report = outputParser.parse(extractJsonPayload(repairedResponse));
        }
        return normalizeReverseCheckReport(report);
    }

    private ReverseCheckReport normalizeReverseCheckReport(ReverseCheckReport report) {
        if (report == null) {
            return new ReverseCheckReport(0, "ECARTS_DETECTES", "Rapport vide retourné par l'IA.", List.of(), List.of());
        }
        int clampedScore = Math.max(0, Math.min(100, report.score()));
        List<ReverseCheckReport.RuleCheckItem> rules = report.rules() != null ? report.rules() : List.of();
        List<String> sqlChecks = report.sqlAndDataChecks() != null ? report.sqlAndDataChecks() : List.of();
        boolean hasGaps = rules.stream().anyMatch(r -> r.status() != null &&
                ("MANQUANT".equalsIgnoreCase(r.status()) || "PARTIEL".equalsIgnoreCase(r.status())));
        String normalizedStatus = ("CONFORME".equalsIgnoreCase(report.globalStatus()) && !hasGaps && sqlChecks.isEmpty())
                ? "CONFORME"
                : "ECARTS_DETECTES";
        String summary = report.summary() != null ? report.summary() : "";
        return new ReverseCheckReport(clampedScore, normalizedStatus, summary, rules, sqlChecks);
    }

    /**
     * Crée un ObjectMapper configuré pour être tolérant aux variations de format JSON.
     * @return Un ObjectMapper configuré pour la désérialisation JSON tolérante.    
     */
    private ObjectMapper createLenientObjectMapper() {
        return new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true)
                .configure(JsonReadFeature.ALLOW_JAVA_COMMENTS.mappedFeature(), true)
                .configure(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature(), true)
                .configure(JsonReadFeature.ALLOW_SINGLE_QUOTES.mappedFeature(), true)
                .configure(JsonReadFeature.ALLOW_TRAILING_COMMA.mappedFeature(), true);
    }

    /**
     * Résout le modèle à utiliser en fonction du modèle demandé et de la configuration par défaut du projet.
     * @param requestedModel Le modèle demandé par l'utilisateur.
     * @return Le modèle à utiliser, soit le modèle demandé, soit le modèle par défaut du projet, ou null si aucun modèle n'est disponible.
     */
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

    /**
     * Extrait le contenu JSON d'une réponse brute, en supprimant les balises de code et les espaces superflus.
     * @param rawResponse La réponse brute contenant potentiellement du JSON.
     * @return Le contenu JSON extrait de la réponse brute, ou la chaîne originale si aucun JSON n'est trouvé.
     */
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
