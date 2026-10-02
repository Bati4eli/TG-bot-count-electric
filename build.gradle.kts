import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.compile.JavaCompile

plugins {
    kotlin("jvm") version "2.2.20"
    application
}

group = "ru.sokolniki"
version = "1.0.16"

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

/** Отправляет основной бот и независимый срез сервиса тарифов в их репозитории Amvera. */
tasks.register("DEPLOY_TO_AMVERA") {
    group = "deployment"
    description = "Публикует Telegram-бот и tariff-provider в Amvera."

    doLast {
        fun git(vararg arguments: String): String {
            val process = ProcessBuilder(listOf("git") + arguments)
                .directory(rootDir)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            check(process.waitFor() == 0) {
                "Команда git ${arguments.joinToString(" ")} завершилась с ошибкой."
            }
            print(output)
            return output
        }

        git("push", "amvera", "HEAD:master")
        val providerCommit = git("subtree", "split", "--prefix=tariff-provider")
            .lineSequence()
            .lastOrNull { it.matches(Regex("[0-9a-f]{40}")) }
            ?: error("Git не вернул хеш среза tariff-provider.")
        git("push", "mosenergo", "$providerCommit:master")
    }
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

