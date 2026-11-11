# Imagen de despliegue de velora-api (VPS). El desarrollo local sigue siendo `./gradlew bootRun`
# contra el PostgreSQL de `compose.yaml`; esta imagen solo la usa `compose.deploy.yaml`.

# --- Etapa de build ---
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

COPY gradlew .
COPY gradle gradle
COPY build.gradle.kts settings.gradle.kts ./
RUN chmod +x gradlew && ./gradlew --version --no-daemon

COPY src src
# Las pruebas (Testcontainers) y el formato ya los verifica el workflow CI antes de desplegar.
RUN ./gradlew bootJar -x test --no-daemon \
    && find build/libs -name '*.jar' ! -name '*-plain.jar' -exec cp {} /workspace/app.jar \;

# --- Etapa de ejecución ---
FROM eclipse-temurin:25-jre
WORKDIR /app

RUN groupadd --system velora && useradd --system --gid velora --home /app velora \
    # Carpeta de originales (ADR-013): un volumen dedicado se monta aquí y hereda este dueño.
    && mkdir -p /data/originals && chown -R velora:velora /data && chmod 700 /data/originals

COPY --from=build /workspace/app.jar app.jar
USER velora

ENV VELORA_STORAGE_ORIGINALS_PATH=/data/originals
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
