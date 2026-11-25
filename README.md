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
