package com.migration.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Prompts et directives éditables sans modifier le code de l'application.
 */
@Configuration
@ConfigurationProperties(prefix = "migration.prompts")
public class MigrationPromptsProperties {

    private PreAnalysis preAnalysis = new PreAnalysis();
    private Conversion conversion = new Conversion();
    private Finalization finalization = new Finalization();
    private ReverseCheck reverseCheck = new ReverseCheck();

    public ReverseCheck getReverseCheck() { return reverseCheck; }
    public void setReverseCheck(ReverseCheck reverseCheck) { this.reverseCheck = reverseCheck; }

    public Finalization getFinalization() { return finalization; }
    public void setFinalization(Finalization finalization) { this.finalization = finalization; }

    public static class Finalization {
        private String system = "";
        private String userTemplate = "";
        public String getSystem() { return system; }
        public void setSystem(String system) { this.system = system; }
        public String getUserTemplate() { return userTemplate; }
        public void setUserTemplate(String userTemplate) { this.userTemplate = userTemplate; }
    }
    private String clarification = "";
    private String contextHeader = "";
    private String ragHeader = "";
    private String metadata = "";
    private String entryPointHeader = "";
    private String entryPointBatch = "";
    private String entryPointWeb = "";
    private String entryPointShared = "";
    private String roleHeader = "";
    private String riskHeader = "";
    private String riskDetected = "";
    private String riskNone = "";
    private String riskDirectives = "";
    private String splittingHeader = "";
    private String splittingRules = "";
    private String existingArtifactsTemplate = "";
    private Map<String, String> roleDirectives = new LinkedHashMap<>();
    private Map<String, String> pipelineDirectives = new LinkedHashMap<>();

    public String render(String template, Map<String, ?> values) {
        String rendered = template == null ? "" : template;
        if (values != null) {
            for (Map.Entry<String, ?> entry : values.entrySet()) {
                rendered = rendered.replace("{" + entry.getKey() + "}",
                        entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
            }
        }
        return rendered;
    }

    public String roleDirective(String role, String targetArtifact, String basePackage) {
        String key = roleDirectives.containsKey(role) ? role
                : targetArtifact.contains("REPOSITORY") ? "REPOSITORY"
                : targetArtifact.contains("CONTROLLER") ? "CONTROLLER"
                : targetArtifact.contains("ENTITY") || targetArtifact.contains("DTO") ? "DTO_MODEL"
                : targetArtifact.contains("THYMELEAF") ? "JSP_VIEW"
                : targetArtifact.contains("CONFIGURATION") ? "CONFIG_XML"
                : "DEFAULT";
        return render(roleDirectives.getOrDefault(key, ""), Map.of(
                "role", role.isEmpty() ? "CONTROLLER" : role,
                "basePackage", basePackage));
    }

    public PreAnalysis getPreAnalysis() {
        return preAnalysis;
    }

    public void setPreAnalysis(PreAnalysis preAnalysis) {
        this.preAnalysis = preAnalysis;
    }

    public Conversion getConversion() {
        return conversion;
    }

    public void setConversion(Conversion conversion) {
        this.conversion = conversion;
    }

    public String getClarification() {
        return clarification;
    }

    public void setClarification(String clarification) {
        this.clarification = clarification;
    }

    public String getContextHeader() { return contextHeader; }
    public void setContextHeader(String contextHeader) { this.contextHeader = contextHeader; }
    public String getRagHeader() { return ragHeader; }
    public void setRagHeader(String ragHeader) { this.ragHeader = ragHeader; }
    public String getMetadata() { return metadata; }
    public void setMetadata(String metadata) { this.metadata = metadata; }
    public String getEntryPointHeader() { return entryPointHeader; }
    public void setEntryPointHeader(String entryPointHeader) { this.entryPointHeader = entryPointHeader; }
    public String getEntryPointBatch() { return entryPointBatch; }
    public void setEntryPointBatch(String entryPointBatch) { this.entryPointBatch = entryPointBatch; }
    public String getEntryPointWeb() { return entryPointWeb; }
    public void setEntryPointWeb(String entryPointWeb) { this.entryPointWeb = entryPointWeb; }
    public String getEntryPointShared() { return entryPointShared; }
    public void setEntryPointShared(String entryPointShared) { this.entryPointShared = entryPointShared; }
    public String getRoleHeader() { return roleHeader; }
    public void setRoleHeader(String roleHeader) { this.roleHeader = roleHeader; }
    public String getRiskHeader() { return riskHeader; }
    public void setRiskHeader(String riskHeader) { this.riskHeader = riskHeader; }
    public String getRiskDetected() { return riskDetected; }
    public void setRiskDetected(String riskDetected) { this.riskDetected = riskDetected; }
    public String getRiskNone() { return riskNone; }
    public void setRiskNone(String riskNone) { this.riskNone = riskNone; }
    public String getRiskDirectives() { return riskDirectives; }
    public void setRiskDirectives(String riskDirectives) { this.riskDirectives = riskDirectives; }
    public String getSplittingHeader() { return splittingHeader; }
    public void setSplittingHeader(String splittingHeader) { this.splittingHeader = splittingHeader; }
    public String getSplittingRules() { return splittingRules; }
    public void setSplittingRules(String splittingRules) { this.splittingRules = splittingRules; }
    public String getExistingArtifactsTemplate() { return existingArtifactsTemplate; }
    public void setExistingArtifactsTemplate(String existingArtifactsTemplate) { this.existingArtifactsTemplate = existingArtifactsTemplate; }
    public Map<String, String> getRoleDirectives() { return roleDirectives; }
    public void setRoleDirectives(Map<String, String> roleDirectives) { this.roleDirectives = roleDirectives; }
    public Map<String, String> getPipelineDirectives() { return pipelineDirectives; }
    public void setPipelineDirectives(Map<String, String> pipelineDirectives) { this.pipelineDirectives = pipelineDirectives; }

    public static class PreAnalysis {
        private String system = "";
        private String user = "";
        private String rootContext = "";
        private String repair = "";
        public String getSystem() { return system; }
        public void setSystem(String system) { this.system = system; }
        public String getUser() { return user; }
        public void setUser(String user) { this.user = user; }
        public String getRootContext() { return rootContext; }
        public void setRootContext(String rootContext) { this.rootContext = rootContext; }
        public String getRepair() { return repair; }
        public void setRepair(String repair) { this.repair = repair; }
    }

    public static class Conversion {
        private String responseFormat = "";
        private String user = "";
        private String repair = "";
        private String skeleton = "";
        private String method = "";
        private String test = "";
        private Registry registry = new Registry();
        public Registry getRegistry() { return registry; }
        public void setRegistry(Registry registry) { this.registry = registry; }
        public String getResponseFormat() { return responseFormat; }
        public void setResponseFormat(String responseFormat) { this.responseFormat = responseFormat; }
        public String getUser() { return user; }
        public void setUser(String user) { this.user = user; }
        public String getRepair() { return repair; }
        public void setRepair(String repair) { this.repair = repair; }
        public String getSkeleton() { return skeleton; }
        public void setSkeleton(String skeleton) { this.skeleton = skeleton; }
        public String getMethod() { return method; }
        public void setMethod(String method) { this.method = method; }
        public String getTest() { return test; }
        public void setTest(String test) { this.test = test; }
    }

    public static class Registry {
        private int topK = 5;
        private double similarityThreshold = 0.72;
        private int maxContextChars = 2500;
        public int getTopK() { return topK; }
        public void setTopK(int topK) { this.topK = topK; }
        public double getSimilarityThreshold() { return similarityThreshold; }
        public void setSimilarityThreshold(double similarityThreshold) { this.similarityThreshold = similarityThreshold; }
        public int getMaxContextChars() { return maxContextChars; }
        public void setMaxContextChars(int maxContextChars) { this.maxContextChars = maxContextChars; }
    }

    public static class ReverseCheck {
        private String system = """
                Tu es un auditeur QA senior extrêmement strict spécialisé en migration Java / Spring Boot.
                Compare le code source legacy original et l'ensemble des fichiers Spring Boot générés sans aucune complaisance.
                Vérifie chaque règle métier, validation, gestion d'erreur, requête SQL (clauses WHERE, jointures, tris, mappings de champs) et comportement fonctionnel.
                Réponds EXCLUSIVEMENT par un objet JSON valide respectant ce schéma :
                {format}""";
        private String user = """
                Fichier source legacy : {sourceFileName}
                ```
                {sourceCode}
                ```

                Artefacts Spring Boot générés :
                {targetArtifacts}""";
        private String repair = """
                Convertis l'audit suivant en UN SEUL objet JSON strictement valide (rien avant {, rien après }) avec les clés :
                score (entier 0..100), globalStatus (CONFORME ou ECARTS_DETECTES), summary, rules ([{ruleDescription, status, targetLocation, discrepancyDetails}]), sqlAndDataChecks ([...]).

                Réponse à convertir :
                {rawResponse}""";

        public String getSystem() { return system; }
        public void setSystem(String system) { this.system = system; }
        public String getUser() { return user; }
        public void setUser(String user) { this.user = user; }
        public String getRepair() { return repair; }
        public void setRepair(String repair) { this.repair = repair; }
    }
}