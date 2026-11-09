plugins {
    java
    id("org.springframework.boot") version "4.1.2"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.diffplug.spotless") version "8.11.0"
}

group = "mx.velora"
version = "0.0.1-SNAPSHOT"
description = "Backend de Velora"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

extra["springModulithVersion"] = "2.1.1"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    // Garmin FIT SDK: FIT Protocol License (propietaria). No redistribuir su código fuente; ver ADR-009.
    implementation("com.garmin:fit:21.221.0")
    // Requerido por Argon2PasswordEncoder (ADR-010). Licencia MIT.
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
    testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    // Validación de respuestas y solicitudes contra openapi/openapi-v1.yaml (Apache-2.0).
    testImplementation("com.atlassian.oai:swagger-request-validator-mockmvc:3.0.0")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.modulith:spring-modulith-bom:${property("springModulithVersion")}")
    }
}

spotless {
    java {
        target("src/**/*.java")
        palantirJavaFormat("2.103.0")
        formatAnnotations()
    }
}

tasks.test {
    useJUnitPlatform {
        excludeTags("benchmark", "local-data")
    }
}

// Benchmarks reproducibles (no corren en `build` ni en CI). Resultados en build/benchmark/.
tasks.register<Test>("benchmark") {
    group = "verification"
    description = "Ejecuta los benchmarks etiquetados con @Tag(\"benchmark\")."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("benchmark")
    }
    outputs.upToDateWhen { false }
    testLogging {
        showStandardStreams = true
    }
}

// Carga variables de `.env` (no versionado) en las tareas de desarrollo local.
fun org.springframework.boot.gradle.tasks.run.BootRun.loadDotEnv() {
    val envFile = file(".env")
    if (envFile.exists()) {
        envFile
            .readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
            .forEach { line ->
                val (key, value) = line.split("=", limit = 2)
                environment(key.trim(), value.trim())
            }
    }
    systemProperty("spring.profiles.active", "local")
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    loadDotEnv()
}

// Crea el usuario propietario una sola vez (ADR-010). Lee VELORA_OWNER_EMAIL y VELORA_OWNER_PASSWORD.
tasks.register<org.springframework.boot.gradle.tasks.run.BootRun>("bootstrapOwner") {
    group = "velora"
    description = "Crea el usuario propietario; falla si ya existe un usuario."
    val bootRun = tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun").get()
    mainClass.set(bootRun.mainClass)
    classpath = bootRun.classpath
    args("--velora.bootstrap-owner", "--spring.main.web-application-type=none")
    loadDotEnv()
}

// Análisis de archivos reales del usuario, solo en local: ./gradlew analyzeLocalFiles -PlocalFiles=/ruta.
// Nunca versionar esos archivos ni el reporte (build/local-data/).
tasks.register<Test>("analyzeLocalFiles") {
    group = "verification"
    description = "Calibra parsers y métricas derivadas con archivos reales locales (sin imprimir coordenadas)."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("local-data")
    }
    systemProperty("velora.localFiles", (project.findProperty("localFiles") ?: "").toString())
    outputs.upToDateWhen { false }
    testLogging {
        showStandardStreams = true
    }
}
