@echo off
echo =======================================================
echo     Demarrage de l'Outil de Migration IA
echo =======================================================
echo.

echo [ETAPE 1] Demarrage du Backend Spring Boot...
start "Backend Spring Boot" cmd /k "mvn spring-boot:run"

echo.
echo Attente de l'initialisation du serveur (15 secondes)...
timeout /t 15 > nul

echo.
echo =======================================================
echo     Initialisation terminee !
echo =======================================================
echo Application accessible sur : http://localhost:8080/dashboard
echo.
echo Ouverture du navigateur...
start http://localhost:8080/dashboard

echo Le script a termine son execution. Vous pouvez fermer cette fenetre.
pause > nul
