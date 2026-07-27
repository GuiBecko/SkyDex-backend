plugins {
	kotlin("jvm") version "1.9.23"
	kotlin("plugin.spring") version "1.9.23"
	kotlin("plugin.jpa") version "1.9.23"
	id("org.springframework.boot") version "3.2.4"
	// Atualizado para 1.1.5 para garantir compatibilidade com as versões novas do Gradle
	id("io.spring.dependency-management") version "1.1.5"
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

	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("me.paulschwarz:spring-dotenv:4.0.0")

	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("com.auth0:java-jwt:4.4.0")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	testImplementation("org.springframework.security:spring-security-test")

	implementation("org.springframework.boot:spring-boot-starter-data-jpa")

	runtimeOnly("org.postgresql:postgresql")

	implementation("org.hibernate.orm:hibernate-spatial")

	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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