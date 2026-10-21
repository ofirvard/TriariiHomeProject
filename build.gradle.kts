plugins {
    id("org.springframework.boot") version "3.5.6" apply false
}

val springBootVersion = "3.5.6"
val testcontainersVersion = "1.21.4"

allprojects {
    group = "com.triarii.rf"
    version = "0.1.0"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")

    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    dependencies {
        "implementation"(platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))
        "testImplementation"(platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))
        "testImplementation"(platform("org.testcontainers:testcontainers-bom:$testcontainersVersion"))
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}
