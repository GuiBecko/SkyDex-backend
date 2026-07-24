plugins {
	kotlin("jvm") version "1.9.23"
	kotlin("plugin.spring") version "1.9.23"
	kotlin("plugin.jpa") version "1.9.23"
	id("org.springframework.boot") version "3.2.4"
	id("io.spring.dependency-management") version "1.1.4"
}

group = "com.skydex.api"
version = "1.0-SNAPSHOT"

java {
	sourceCompatibility = JavaVersion.VERSION_17
}

repositories {
	mavenCentral()
}

dependencies {
	// 1. Spring Web: Para criar os endpoints REST da API [cite: 7, 43]
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
	implementation("org.jetbrains.kotlin:kotlin-reflect")

	// 2. Spring Data JPA: Para a comunicação com o banco de dados [cite: 8, 43]
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")

	// 3. PostgreSQL Driver: Para conectar ao banco [cite: 9, 43]
	runtimeOnly("org.postgresql:postgresql")

	// 4. Hibernate Spatial: Essencial para entender os tipos geográficos (Point) do PostGIS [cite: 9, 43]
	implementation("org.hibernate.orm:hibernate-spatial")

	testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
	compilerOptions {
		freeCompilerArgs.add("-Xjsr305=strict")
		jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
}
