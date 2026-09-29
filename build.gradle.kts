import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.compile.JavaCompile

plugins {
    kotlin("jvm") version "2.2.20"
    application
}

group = "ru.sokolniki"
version = "1.0.4"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":shared"))
    implementation("org.apache.poi:poi-ooxml:5.5.1")
    implementation("org.xerial:sqlite-jdbc:3.53.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
}

tasks.register("buildAllJars") {
    description = "Собирает исполняемый JAR-файл Telegram-бота."
    group = "build"
    dependsOn(tasks.jar)
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

application {
    mainClass.set("ru.sokolniki.electricity.MainKt")
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

