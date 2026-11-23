package com.migration.service;

import com.migration.model.ContextReference;
import com.migration.model.GeneratedArtifact;
import com.migration.model.MigrationFileStatus;
import com.migration.model.ProjectConfiguration;
import com.migration.repository.MigrationFileStatusRepository;
import com.migration.repository.ProjectConfigurationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Construit dynamiquement un prompt de conversion Spring Boot 3 à partir des métadonnées
 * de pré-analyse sémantique, des risques détectés et du contexte des dépendances.
 */
@Service
@RequiredArgsConstructor
public class DynamicPromptBuilderService {

    private static final String DEFAULT_BASE_PACKAGE = "com.migration.target";

    private final MigrationFileStatusRepository fileStatusRepository;
    private final ProjectConfigurationRepository projectConfigRepository;

    /**
     * Assemble dynamiquement le prompt de conversion pour un fichier donné et ses dépendances.
     *
     * @param fileStatus        Statut et métadonnées de pré-analyse du fichier source.
     * @param extraContextPaths Chemins supplémentaires ajoutés manuellement dans la modale.
     * @return Prompt structuré prêt à être affiché, édité et exécuté par le LLM.
     */
    public String buildConversionPrompt(MigrationFileStatus fileStatus, List<String> extraContextPaths) {
        if (fileStatus == null) {
            throw new IllegalArgumentException("fileStatus ne doit pas être null");
        }

        String basePackage = resolveBasePackage();
        String basePackagePath = basePackage.replace('.', '/');
        String role = normalize(fileStatus.getArchitecturalRole());
        String targetArtifact = normalize(fileStatus.getTargetSpringArtifact());

        StringBuilder sb = new StringBuilder();
        sb.append("### Contexte de migration du fichier : ").append(safe(fileStatus.getSourceFile())).append("\n");
        appendMetadataSection(sb, fileStatus, basePackage);
        appendRoleDirectives(sb, role, targetArtifact, fileStatus.getTargetSpringArtifact(), basePackage);
        appendRiskGuardrails(sb, fileStatus.getDetectedRisks());
        appendDependencyContext(sb, fileStatus, extraContextPaths);
        appendMultiFileSplittingRules(sb, basePackagePath);

        return sb.toString().trim();
    }

    private void appendMetadataSection(StringBuilder sb, MigrationFileStatus fileStatus, String basePackage) {
        sb.append("- **Rôle architectural détecté** : ").append(orDefault(fileStatus.getArchitecturalRole(), "NON_ANALYSE")).append("\n");
        sb.append("- **Artefact Spring cible** : ").append(orDefault(fileStatus.getTargetSpringArtifact(), "À déterminer")).append("\n");
        sb.append("- **Complexité estimée** : ").append(orDefault(fileStatus.getMigrationComplexity(), "INCONNUE")).append("\n");
        if (isNotBlank(fileStatus.getSuggestedFunctionalLot())) {
            sb.append("- **Lot fonctionnel suggéré** : ").append(fileStatus.getSuggestedFunctionalLot().trim()).append("\n");
        }
        sb.append("- **Package racine cible** : `").append(basePackage).append("`\n");
        if (isNotBlank(fileStatus.getFunctionalSummary())) {
            sb.append("\n#### Résumé fonctionnel et technique\n")
              .append(fileStatus.getFunctionalSummary().trim()).append("\n");
        }
        sb.append("\n");
    }

    private void appendRoleDirectives(StringBuilder sb, String role, String targetArtifact, String rawTargetArtifact, String basePackage) {
        sb.append("### Directives architecturales ciblées\n");

        if ("JDBC_DAO".equals(role) || targetArtifact.contains("REPOSITORY")) {
            sb.append("- **Extraction SQL & Persistance (`JDBC_DAO`)** : Extrais toutes les requêtes SQL vers un `@Repository` Spring Data JPA ou `JdbcClient` (paramètres nommés obligatoires, aucun `Connection`/`Statement`/`ResultSet` manuel).\n");
            sb.append("- **Projections & DTO** : Crée des `record` Java immuables dédiés pour toutes les projections SQL, critères de recherche et résultats retournés.\n");
        } else if ("STRUTS_ACTION".equals(role) || "SERVLET".equals(role) || targetArtifact.contains("CONTROLLER")) {
            sb.append("- **Séparation HTTP / Métier (`").append(role.isEmpty() ? "CONTROLLER" : role).append("`)** : Sépare strictement le routage HTTP (`@RestController` ou `@Controller` avec `@RequestMapping`) de la logique métier (déléguée à un `@Service` dédié).\n");
            sb.append("- **Contrats d'API** : Définis des `record` DTO distincts pour les requêtes entrantes (avec validation Jakarta `@Valid` si pertinent) et les réponses sortantes.\n");
        } else if ("UTILITY".equals(role) || "SINGLETON".equals(role)) {
            sb.append("- **Suppression du Singleton / Utilitaire stateful (`").append(role).append("`)** : Convertis cette classe en `@Component` ou `@Service` Spring géré par injection de dépendances.\n");
            sb.append("- **Stateless obligatoire** : Aucun état mutable partagé (`static` non `final` strictement interdit).\n");
        } else if ("DTO_MODEL".equals(role) || targetArtifact.contains("ENTITY") || targetArtifact.contains("DTO")) {
            sb.append("- **Modèle immuable / Entité (`DTO_MODEL`)** : Convertis directement ce modèle en `record` Java immuable. Si une persistance base de données est détectée, produis une classe JPA `@Entity` proprement annotée (constructeur sans argument, identifiant `@Id`, sans logique métier) et un `record` DTO associé.\n");
        } else if ("BUSINESS_SERVICE".equals(role)) {
            sb.append("- **Service métier (`BUSINESS_SERVICE`)** : Produis un `@Service` Spring Boot avec injection par constructeur (`final` fields), transactions `@Transactional` là où nécessaire, et des `record` DTO pour les échanges.\n");
        } else if ("JSP_VIEW".equals(role) || targetArtifact.contains("THYMELEAF")) {
            sb.append("- **Vue Web (`JSP_VIEW`)** : Migre la vue JSP vers un template Thymeleaf propre (`src/main/resources/templates/...`) sans scriptlets Java.\n");
        } else if ("CONFIG_XML".equals(role) || targetArtifact.contains("CONFIGURATION")) {
            sb.append("- **Configuration (`CONFIG_XML`)** : Traduis la configuration XML legacy en classe `@Configuration` Java ou propriétés `application.yml` Spring Boot.\n");
        } else {
            sb.append("- Convertis ce composant vers une architecture Spring Boot 3 idiomatique sous le package `")
              .append(basePackage).append("`, en respectant le principe de responsabilité unique.\n");
        }

        if (isNotBlank(rawTargetArtifact)) {
            sb.append("- Artefact principal attendu : `").append(rawTargetArtifact.trim()).append("`.\n");
        }
        sb.append("\n");
    }

    private void appendRiskGuardrails(StringBuilder sb, String detectedRisks) {
        sb.append("### Garde-fous et risques à corriger impérativement\n");
        if (isNotBlank(detectedRisks)) {
            sb.append("Les risques legacy suivants ont été identifiés lors de la pré-analyse et **DOIVENT** être éliminés dans le code généré :\n");
            for (String risk : parseRisks(detectedRisks)) {
                sb.append("- ").append(risk).append("\n");
            }
            sb.append("- Directives associées : supprime tout couplage à `HttpSession` / `ServletContext`, remplace toute concaténation SQL par des paramètres nommés (`:param` / `?`), et supprime tout singleton manuel (`getInstance()`, champs `static` mutables).\n");
        } else {
            sb.append("- Aucun risque spécifique remonté en pré-analyse ; applique les standards de sécurité Spring Boot 3 (requêtes paramétrées, stateless, injection par constructeur).\n");
        }
        sb.append("\n");
    }

    private void appendDependencyContext(StringBuilder sb, MigrationFileStatus fileStatus, List<String> extraContextPaths) {
        Set<String> allContextPaths = new LinkedHashSet<>();
        if (fileStatus.getContextFiles() != null) {
            for (ContextReference ref : fileStatus.getContextFiles()) {
                if (ref != null && isNotBlank(ref.getFilePath())) {
                    allContextPaths.add(ref.getFilePath().trim());
                }
            }
        }
        if (extraContextPaths != null) {
            for (String extra : extraContextPaths) {
                if (isNotBlank(extra)) {
                    allContextPaths.add(extra.trim());
                }
            }
        }

        if (allContextPaths.isEmpty()) {
            return;
        }

        sb.append("### Contexte des dépendances et fichiers déjà migrés\n");
        for (String path : allContextPaths) {
            Optional<MigrationFileStatus> depOpt = fileStatusRepository.findByAbsolutePath(path);
            sb.append("- **").append(path).append("**");
            if (depOpt.isPresent()) {
                MigrationFileStatus dep = depOpt.get();
                if (isNotBlank(dep.getFunctionalSummary())) {
                    sb.append("\n  - Résumé fonctionnel : ").append(dep.getFunctionalSummary().trim());
                }
                List<String> migratedPaths = extractMigratedTargetPaths(dep);
                if (!migratedPaths.isEmpty()) {
                    sb.append("\n  - Fichiers cibles déjà générés : ").append(String.join(", ", migratedPaths));
                }
            } else {
                sb.append(" (fichier de référence externe)");
            }
            sb.append("\n");
        }
        sb.append("\n");
    }

    private void appendMultiFileSplittingRules(StringBuilder sb, String basePackagePath) {
        sb.append("### Règle de découpage multi-fichiers (1 -> N)\n");
        sb.append("- Tu **DOIS** produire autant de fichiers distincts que l'architecture Spring Boot 3 l'exige (séparation stricte `Controller` / `Service` / `Repository` / `DTO` / `Entity` / `Config`), chacun dans son package cible cohérent.\n");
        sb.append("- Exemple de chemins cibles (`relativeTargetPath`) : `src/main/java/").append(basePackagePath)
          .append("/service/...`, `src/main/java/").append(basePackagePath)
          .append("/dto/...`, `src/main/java/").append(basePackagePath)
          .append("/repository/...`, `src/main/java/").append(basePackagePath)
          .append("/controller/...`.\n");
        sb.append("- Pour chaque fichier généré, renseigne `relativeTargetPath`, `artifactType` (`CONTROLLER`, `SERVICE`, `REPOSITORY`, `DTO`, `ENTITY`, `CONFIG`, `VIEW`, `TEST`) et `code` complet et compilable.\n");
    }

    private List<String> extractMigratedTargetPaths(MigrationFileStatus dep) {
        if (dep.getGeneratedArtifacts() != null && !dep.getGeneratedArtifacts().isEmpty()) {
            return dep.getGeneratedArtifacts().stream()
                    .map(GeneratedArtifact::getRelativeTargetPath)
                    .filter(this::isNotBlank)
                    .collect(Collectors.toList());
        }
        if (dep.getTargetFiles() != null && !dep.getTargetFiles().isEmpty()) {
            return dep.getTargetFiles().stream()
                    .filter(this::isNotBlank)
                    .collect(Collectors.toList());
        }
        return List.of();
    }

    private List<String> parseRisks(String detectedRisks) {
        List<String> result = new ArrayList<>();
        String cleaned = detectedRisks.trim();
        if (cleaned.startsWith("[") && cleaned.endsWith("]")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }
        for (String part : cleaned.split("[;\n,]")) {
            String item = part.trim().replaceAll("^[\"']+|[\"']+$", "").trim();
            if (!item.isEmpty()) {
                result.add(item);
            }
        }
        if (result.isEmpty() && !detectedRisks.trim().isEmpty()) {
            result.add(detectedRisks.trim());
        }
        return result;
    }

    private String resolveBasePackage() {
        return projectConfigRepository.findById(1L)
                .map(config -> isNotBlank(config.getTargetGroupId())
                        ? config.getTargetGroupId()
                        : config.getTargetBasePackage())
                .filter(this::isNotBlank)
                .map(String::trim)
                .orElse(DEFAULT_BASE_PACKAGE);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String orDefault(String value, String fallback) {
        return isNotBlank(value) ? value.trim() : fallback;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private boolean isNotBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
