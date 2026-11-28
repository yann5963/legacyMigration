# Assistant de Migration Architecturale Human-in-the-Loop

![Java](https://img.shields.io/badge/Java-19-orange)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.x-brightgreen)
![Spring AI](https://img.shields.io/badge/Spring_AI-Enabled-blue)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-pgvector-blue)
![JavaParser](https://img.shields.io/badge/JavaParser-AST-yellow)

## 1. Description du Projet

L'**Assistant de Migration Architecturale** est un outil innovant conçu pour faciliter et sécuriser la transition de systèmes monolithiques legacy (J2EE / Informix) vers des architectures modernes (Spring Boot / PostgreSQL).

Contrairement aux solutions de conversion "boîte noire", cet outil adopte une approche **Human-in-the-Loop** (workflow de type Copilote). Il combine intelligemment :
*   **L'analyse statique déterministe** basée sur l'Abstract Syntax Tree (AST) via JavaParser.
*   **L'intelligence artificielle générative** via Spring AI.

L'objectif est d'offrir au développeur un contrôle total sur le processus de refactoring, en permettant la validation manuelle, la correction itérative et la gestion intelligente des limites techniques des modèles d'IA.

## 2. Fonctionnalités Principales (Workflow)

*   ⚙️ **Configuration Dynamique :** Un écran dédié permet de paramétrer finement le projet, incluant la définition des technologies et versions cibles (ex: Java 19, Spring Boot 3.x, framework de test).
*   🔍 **Initialisation & Scan :** Analyse récursive du projet source legacy. L'état d'avancement, les dépendances et la priorisation de chaque fichier sont extraits et stockés dans une base de données relationnelle.
*   📊 **Tableau de Bord de Migration :** Une interface utilisateur centralisée permettant de suivre et de valider la migration fichier par fichier. En cas d'erreur ou d'hallucination du LLM, l'utilisateur peut relancer la conversion d'un fichier en fournissant un prompt correctif personnalisé (principe de *Few-Shot Learning* interactif).
*   🧠 **Chunking Sémantique (Map-Reduce) :** Traitement spécialisé des "God Classes" (fichiers volumineux). Pour éviter la saturation de la fenêtre de contexte du LLM :
    *   **Map :** Découpage intelligent du fichier source au niveau des méthodes via JavaParser. Chaque méthode est encapsulée dans un DTO (`MethodChunk`) et traduite individuellement par l'IA.
    *   **Reduce :** Réassemblage programmatique et déterministe des méthodes converties directement dans l'AST de la classe cible Spring Boot, garantissant la validité syntaxique finale.
*   📚 **RAG Dynamique (Retrieval-Augmented Generation) :** Enrichissement automatique du contexte fourni au LLM (schémas de base de données, interfaces partagées, patterns validés précédemment) grâce à une recherche vectorielle sémantique, améliorant drastiquement la pertinence du code généré.

## 3. Stack Technique

### Finalisation et compilation du projet cible

Le bouton **Finaliser et Compiler** du dashboard ouvre `/finalization`. Configurez d'abord
le chemin du projet cible ; il doit être distinct du répertoire de l'outil.

1. **Préparer le terrain** écrit les artefacts en base dans le répertoire cible, aligne les
   packages et retire les imports `javax.servlet` / `org.apache.struts` par JavaParser.
   Le squelette manquant est créé (Spring Boot 3, Java 21, classe principale et configuration).
   Les dépendances Maven manquantes sont déduites des artefacts, sans remplacer le POM existant.
   Cette étape réécrit les fichiers générés : réconciliez préalablement toute modification manuelle sur disque.
2. **Lancer le Build Maven** exécute `mvn test-compile -B` dans ce répertoire
   (Maven et un JDK compatible avec le projet cible doivent être disponibles dans le PATH du serveur).
   Un délai de dix minutes interrompt le processus Maven et ses descendants.
3. **Corriger par saut de puce** effectue au plus le nombre de sauts demandé (1–50),
   avec un diagnostic initial puis un build après chaque correction, en ordre
   ENTITY/DTO → REPOSITORY → SERVICE → CONTROLLER et au maximum deux tentatives IA
   cumulées par artefact en automatique. Les relances manuelles restent possibles.

Les statuts, dates, erreurs par fichier, compteurs, derniers prompts et le résumé du
dernier build sont persistés en base (`ddl-auto: update`). Le suivi de l'opération en
cours est en mémoire et consulté par polling ; un redémarrage du serveur l'interrompt.
Les artefacts verrouillés ne sont pas corrigés par l'IA.

Le LLM ne reçoit que les erreurs du fichier, les imports/déclarations, les fenêtres
de ±15 lignes et les signatures compactes des dépendances citées. Les prompts sont
externalisés dans `migration.prompts.finalization` dans `prompts.yml`. Les réponses
doivent être des patches JSON de remplacements exacts et uniques, pas des fichiers
complets. Un contrôle AST refuse les suppressions de méthodes (y compris les
surcharges et méthodes non publiques), de champs et les changements de types/packages.
Ce garde-fou structurel ne prouve pas l'équivalence sémantique : une revue métier reste nécessaire.

API (les actions longues retournent HTTP 202 ; une seconde action simultanée retourne 409) :
`GET /api/migration/finalization`, `GET /api/migration/finalization/status`,
`POST /api/migration/finalization/prepare`, `POST /api/migration/finalization/build`,
`POST /api/migration/finalization/auto-fix?maxIterations=10`,
`GET /api/migration/finalization/artifacts/{id}/fix-prompt`,
`POST /api/migration/finalization/artifacts/{id}/fix` avec
`{"customPrompt":"...","modelOverride":"..."}` (champs optionnels).

### Points d'entrée et accessibilité

La page **Configuration** permet d'activer la détection automatique (par défaut) et de saisir des chemins relatifs manuels, un par ligne ou séparés par des virgules. Le scan détecte les descripteurs `web.xml`, `struts-config.xml`, `faces-config.xml`, `applicationContext.xml`, les méthodes `public static void main(String[] args)` et les classes dérivant de `HttpServlet`, `Action` ou `DispatchAction`.

L'accessibilité suit les dépendances sortantes Java et les références XML vers Java. Chaque fichier atteint conserve tous ses points d'entrée racines, y compris dans les graphes cycliques. Sans point d'entrée, tous les fichiers restent atteignables pour préserver le comportement historique.

Les fichiers non atteints sont des **candidats** au code mort, pas une preuve de code inutilisé (réflexion, routage dynamique et autres liens implicites ne sont pas exhaustivement résolus). La pré-analyse les classe `DEAD_CODE_CANDIDATE` / `ToDelete` sans appel au LLM ; aucune suppression n'est effectuée. Les autres fichiers sont analysés en commençant par les points d'entrée puis par couche décroissante. Les prompts reçoivent les flux racines et distinguent les orientations batch et Web.

Le dashboard affiche les badges **ENTRY POINT**, **NON ATTEINT (Code mort ?)** et les flux racines. Le filtre **Masquer le code non atteint**, activé par défaut, peut être décoché pour examiner ces candidats. Les fichiers non atteints ne bloquent pas les couches atteignables.

L'API `POST /api/project/scan` conserve ses champs existants et accepte aussi `manualEntryPoints` (tableau de chemins) et `autoDetectEntryPoints` (booléen, `true` si omis). La signature Java historique `scanProject(String)` reste disponible.

### Backend & Logique Métier
*   **Langage :** Java 19
*   **Framework Core :** Spring Boot 3.x
*   **IA Integration :** Spring AI
*   **Analyse de Code :** JavaParser (manipulation profonde de l'AST)

### Base de Données & Stockage
*   **Relationnel :** PostgreSQL (Suivi d'état, historique de migration)
*   **Vectoriel :** pgvector (Base de données vectorielle pour le système RAG)

Le modèle d'embedding `nomic-embed-text:v1.5` produit **768 dimensions**. La propriété
`spring.ai.vectorstore.pgvector.dimensions` et le type de la colonne
`vector_store.embedding` doivent donc tous deux être à 768. Avec Spring AI 0.8.1,
modifier cette propriété ne redimensionne pas une table existante : une ancienne
table créée en `vector(1536)` reste incompatible.

Au démarrage, le synchroniseur vérifie le type réel de la colonne. Si la table est
vide, il ajuste la dimension dans une transaction avec verrouillage. Si elle contient
des données incompatibles, il arrête le démarrage avec un diagnostic explicite,
sans supprimer ni tronquer de vecteurs. Il faut alors sauvegarder les contenus et
métadonnées puis recalculer les embeddings avec le modèle choisi ; réduire un vecteur
1536 à ses 768 premières valeurs ne constitue pas une migration valide.
Ne configurez pas `remove-existing-vector-store-table: true` pour contourner ce problème :
cette option supprime les données.

Le test `PgVectorDimensionIntegrationTest` s'active avec les variables
`PGVECTOR_TEST_URL`, `PGVECTOR_TEST_USER` et `PGVECTOR_TEST_PASSWORD`.
Il utilise un schéma PostgreSQL unique, supprimé après le test, pour vérifier la
réparation, l'insertion et la recherche de similarité sans toucher aux données applicatives.

### Intelligence Artificielle
*   **Modèles Supportés :** Ollama (Inférence locale performante) ou APIs Cloud LLM (OpenAI, Anthropic, etc.) configurables via Spring AI.

### Infrastructure & Déploiement
*   **Conteneurisation :** Docker (Base de données, Vector Store, modèles de ML locaux)

## 4. Instructions d'Installation et d'Exécution

### Prérequis
*   Java 19 JDK
*   Maven 3.8+
*   Docker & Docker Compose

### Étape 1 : Démarrage de l'Infrastructure
L'application repose sur des services externes conteneurisés (Base de données PostgreSQL/pgvector, base Informix legacy simulée, instances Ollama si applicables). Vous devez démarrer ces services manuellement avant de lancer l'application.

```bash
# Démarrer les services d'infrastructure en arrière-plan
docker-compose up -d
```

*Note : Assurez-vous que les conteneurs sont pleinement opérationnels avant de passer à l'étape suivante.*

### Étape 2 : Lancement de l'Application (Backend & Frontend)
Le projet inclut un script utilitaire `_run.bat` conçu pour simplifier l'exécution locale. Ce script se charge de démarrer simultanément le backend Spring Boot et l'interface utilisateur dans des terminaux séparés, **sans** interférer avec les conteneurs Docker préalablement lancés.

```bash
# Sous Windows
.\_run.bat

# Sous Linux/Mac (si un équivalent .sh est présent, sinon lancer via Maven)
mvn clean spring-boot:run
```

L'interface de configuration et le tableau de bord seront alors accessibles via votre navigateur (par défaut sur `http://localhost:8080`).
