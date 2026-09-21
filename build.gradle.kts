plugins {
	jacoco
	kotlin("jvm") version "2.3.21"
	kotlin("plugin.spring") version "2.3.21"
	kotlin("plugin.jpa") version "2.3.21"
	id("org.springframework.boot") version "4.1.1"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "com.project"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(17)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("tools.jackson.module:jackson-module-kotlin")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("org.springframework.statemachine:spring-statemachine-core:4.0.1")
	runtimeOnly("com.mysql:mysql-connector-j")
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("io.kotest:kotest-runner-junit5:6.0.3")
	testImplementation("io.kotest:kotest-assertions-core:6.0.3")
	testImplementation("io.kotest:kotest-extensions-spring:6.0.3")
	testImplementation("io.mockk:mockk:1.14.5")
	testImplementation("com.tngtech.archunit:archunit:1.4.1")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

configurations.testImplementation {
	exclude(group = "org.mockito")
	exclude(group = "org.assertj")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
	}
}

allOpen {
	annotation("jakarta.persistence.Entity")
	annotation("jakarta.persistence.MappedSuperclass")
	annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
	useJUnitPlatform()
	System.getProperty("kotest.tags")?.let { systemProperty("kotest.tags", it) }
	finalizedBy(tasks.jacocoTestReport)
}

jacoco {
	toolVersion = "0.8.13"
}

val coverageExclusions = listOf(
	"com/project/msa/MsaApplication*",
	"com/project/msa/**/dto/**",
	"com/project/msa/config/**",
	"com/project/msa/init/**",
)

tasks.jacocoTestReport {
	dependsOn(tasks.test)
	reports {
		xml.required = true
		html.required = true
	}
	classDirectories.setFrom(classDirectories.files.map { fileTree(it) { exclude(coverageExclusions) } })
}

tasks.jacocoTestCoverageVerification {
	dependsOn(tasks.jacocoTestReport)
	classDirectories.setFrom(classDirectories.files.map { fileTree(it) { exclude(coverageExclusions) } })
	violationRules {
		rule {
			limit {
				counter = "LINE"
				value = "COVEREDRATIO"
				minimum = "0.90".toBigDecimal()
			}
		}
	}
}

tasks.check {
	dependsOn(tasks.jacocoTestCoverageVerification)
}
