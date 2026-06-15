# PoC Plan: Auteur

## Project Classification
- **Type:** web-app (AI-powered video production platform)
- **Key Technologies:** Java 21, Spring Boot 3.3, Vue 3, MySQL 8.0, ffmpeg, Flyway migrations
- **ODH Relevance:** Demonstrates an AI agent orchestration platform that can leverage LLM serving infrastructure on OpenShift AI for automated content production workflows.

## PoC Objectives
1. Verify that Auteur's backend and frontend can be containerized with UBI base images
2. Deploy the full 3-service stack (MySQL, backend, frontend) on OpenShift
3. Validate Spring Boot Actuator health endpoints and frontend proxy routing
4. Confirm the web UI loads and the API responds correctly

## Infrastructure Requirements
- **Resource Profile:** medium (backend needs 1Gi RAM for JVM)
- **GPU Required:** No
- **Persistent Storage:** 2Gi PVC for MySQL data, 1Gi PVC for backend storage
- **Sidecar Containers:** None (MySQL is a separate deployment)
- **Deployment Model:** deployment (long-running)
- **Listens On Port:** backend=8082, frontend=8080
- **LLM API Dependency:** No (LLM API is configured at runtime via UI, not required for startup)

## Test Scenarios

### Scenario 1: backend-health
- **Description:** Verify Spring Boot Actuator health endpoint
- **Type:** http
- **Endpoint:** /actuator/health (port 8082)
- **Expected:** Returns 200 with {"status":"UP"}
- **Timeout:** 60 seconds (JVM startup)

### Scenario 2: frontend-load
- **Description:** Verify Vue 3 SPA loads via nginx
- **Type:** http
- **Endpoint:** / (port 8080)
- **Expected:** Returns 200 with HTML content
- **Timeout:** 30 seconds

### Scenario 3: api-proxy
- **Description:** Verify nginx proxies /api to backend
- **Type:** http
- **Endpoint:** /api/actuator/health (port 8080 via frontend proxy)
- **Expected:** Returns 200 with health data from backend
- **Timeout:** 30 seconds

## Dockerfile Considerations
- Backend: Multi-stage Maven 3.9 + JDK 21 build, UBI9 OpenJDK 21 runtime, install ffmpeg and CJK fonts
- Frontend: Multi-stage Node 20 build, UBI9 nginx runtime, custom nginx.conf for SPA routing and API proxy
- Both need non-root user (1001) and group 0 permissions

## Deployment Considerations
- MySQL: Use a StatefulSet or Deployment with PVC
- Backend: Deployment with readiness probe on /actuator/health, PVC for storage
- Frontend: Deployment with nginx serving static files and proxying /api to backend service
- ConfigMap/Secret for MySQL credentials
