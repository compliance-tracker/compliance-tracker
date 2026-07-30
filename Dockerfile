# Issue #52 - a real prerequisite for eventual ECS/Fargate deployment (#5, deliberately on hold
# for cost reasons, not this), but fully buildable/runnable locally right now with nothing but
# `docker build`/`docker run` - no AWS account needed to verify this actually works.

# ---- Build stage ----
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /app

# The wrapper + pom.xml alone, before any application source - Docker caches each layer by its
# inputs, so dependency resolution only re-runs when pom.xml (or the wrapper itself) actually
# changes, not on every source edit.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B dependency:go-offline

COPY src/ src/
# Tests already run in CI's own build-and-test job before this image is ever built in a real
# pipeline - skipping them here avoids re-running the same suite a second time inside the image
# build itself, not skipping verification entirely.
RUN ./mvnw -B package -DskipTests

# ---- Runtime stage ----
# A separate, much smaller JRE-only (not JDK) base image for the actual runtime - the Maven
# wrapper, the full JDK, and every dependency jar Maven downloaded to build the app have no
# business shipping in the image that actually runs it.
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# Runs as a real non-root user rather than the image's default root - a container escape or RCE
# in the app itself shouldn't also hand over root inside the container for free.
RUN groupadd --system spring && useradd --system --gid spring spring
USER spring:spring

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8081

# No Docker HEALTHCHECK directive here on purpose - a real deployment (ECS task definition/K8s
# probe) should point its own health check at GET /actuator/health (issue #44) directly, which
# already distinguishes liveness from readiness; baking an equivalent check into the image itself
# would just be a second, redundant place for that logic to drift out of sync with the real one.
ENTRYPOINT ["java", "-jar", "app.jar"]
