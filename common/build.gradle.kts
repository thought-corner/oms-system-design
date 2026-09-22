plugins {
    jacoco
    `java-test-fixtures`
    kotlin("jvm")
    kotlin("plugin.spring")
    id("io.spring.dependency-management")
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

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:4.1.1")
    }
}

dependencies {
    compileOnly("org.springframework.boot:spring-boot-starter-webmvc")

    testFixturesApi("com.tngtech.archunit:archunit:1.4.1")
    testFixturesApi("io.kotest:kotest-runner-junit5:6.0.3")
    testFixturesApi("io.kotest:kotest-assertions-core:6.0.3")
    testFixturesCompileOnly("org.springframework.boot:spring-boot-starter-webmvc")
    testFixturesCompileOnly("org.springframework.boot:spring-boot-starter-data-jpa")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.kotest:kotest-runner-junit5:6.0.3")
    testImplementation("io.kotest:kotest-assertions-core:6.0.3")
    testImplementation("io.mockk:mockk:1.14.5")
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

tasks.withType<Test> {
    useJUnitPlatform()
    System.getProperty("kotest.tags")?.let { systemProperty("kotest.tags", it) }
    finalizedBy(tasks.jacocoTestReport)
}

jacoco {
    toolVersion = "0.8.13"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.jacocoTestReport)
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
