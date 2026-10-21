plugins {
    `java-library`
}

dependencies {
    // Annotations only; the services that validate bring the implementation at runtime.
    compileOnly("jakarta.validation:jakarta.validation-api")
}
