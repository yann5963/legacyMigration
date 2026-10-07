package com.migration.ai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
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

    /**
     * Méthode générique pour interroger le LLM avec un contexte et une requête.
     *
     * @param systemPromptTemplate Le template du System Prompt.
     * @param systemParams Les paramètres pour remplir le template du System Prompt.
     * @param userMessage Le message ou le code de l'utilisateur à analyser/refactoriser.
     * @return La réponse générée par le LLM.
     */
    public String analyzeAndRefactor(String systemPromptTemplate, Map<String, Object> systemParams, String userMessage) {
        return analyzeAndRefactor(systemPromptTemplate, systemParams, userMessage, null);
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
    public String analyzeAndRefactor(String systemPromptTemplate, Map<String, Object> systemParams, String userMessage, String additionalInstructions) {
        log.debug("Appel au LLM pour refactoring...");

        String finalPromptTemplate = systemPromptTemplate;
        if (additionalInstructions != null && !additionalInstructions.trim().isEmpty()) {
            finalPromptTemplate += "\n\nVoici des directives supplémentaires de l'utilisateur pour ce fichier : " + additionalInstructions;
        }
        SystemPromptTemplate promptTemplate = new SystemPromptTemplate(finalPromptTemplate);
        Message systemMessage = promptTemplate.createMessage(systemParams);
        UserMessage userMsg = new UserMessage(userMessage);

        Prompt prompt = new Prompt(List.of(systemMessage, userMsg));

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
}
