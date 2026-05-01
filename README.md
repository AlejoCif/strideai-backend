# StrideAI Backend

Kotlin + Spring Boot backend para StrideAI — integración con Strava API y Anthropic AI.

## Stack

- **Kotlin 1.9** + **Spring Boot 3.2**
- **Spring MVC** (REST API bloqueante)
- **Spring Data JPA** + **PostgreSQL**
- **Spring Security** + **OAuth2 Client** (Strava)
- **WebClient** para llamadas a Strava y Anthropic

## Setup local

### 1. Requisitos
- JDK 21 (`sdk install java 21-tem` con SDKMAN)
- PostgreSQL corriendo localmente
- IntelliJ IDEA (recomendado)

### 2. Base de datos
```sql
CREATE DATABASE strideai;
```

### 3. Variables de entorno
```bash
cp .env.example .env
# Edita .env con tus credenciales reales
```

### 4. Correr en desarrollo
```bash
./gradlew bootRun
```
El servidor levanta en `http://localhost:3001`

## Endpoints

| Método | Ruta | Descripción |
|--------|------|-------------|
| GET | `/api/health` | Health check |
| GET | `/api/athlete` | Perfil del atleta desde Strava |
| GET | `/api/activities?limit=20&page=1` | Actividades recientes |
| GET | `/api/activities/stats` | Stats semanales + CTL/ATL/TSB |
| POST | `/api/ai/chat` | Chat con entrenador IA |
| POST | `/api/ai/plan` | Generar plan semanal con IA |
| GET | `/api/ai/analysis` | Análisis de rendimiento con IA |

### Ejemplo: Chat con IA
```bash
curl -X POST http://localhost:3001/api/ai/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "¿Cómo fue mi semana de entrenamiento?"}'
```

### Ejemplo: Generar plan
```bash
curl -X POST http://localhost:3001/api/ai/plan \
  -H "Content-Type: application/json" \
  -d '{"goal": "Mejorar resistencia aeróbica", "weeksToEvent": 8}'
```

## Deploy en Railway

```bash
# 1. Instala Railway CLI
npm i -g @railway/cli

# 2. Login
railway login

# 3. Crea proyecto
railway new

# 4. Agrega PostgreSQL
railway add --plugin postgresql

# 5. Configura variables de entorno en Railway dashboard:
#    STRAVA_CLIENT_ID, STRAVA_CLIENT_SECRET, STRAVA_REFRESH_TOKEN
#    ANTHROPIC_API_KEY, FRONTEND_URL

# 6. Deploy
railway up
```

## Estructura del proyecto

```
src/main/kotlin/com/strideai/
├── Application.kt              ← Entry point
├── config/
│   ├── AppConfig.kt            ← WebClient, ObjectMapper beans
│   └── SecurityConfig.kt       ← CORS, OAuth2, Spring Security
├── controller/
│   └── Controllers.kt          ← HealthController, AthleteController,
│                                  ActivitiesController, AIController
├── service/
│   ├── StravaService.kt        ← Token refresh + Strava API calls
│   └── AIService.kt            ← Anthropic chat + plan generation
├── model/
│   └── Models.kt               ← User, Activity, TrainingPlan entities
├── repository/
│   └── Repositories.kt         ← JPA repositories
└── dto/
    └── DTOs.kt                 ← Request/Response data classes
```
