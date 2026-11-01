import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

subprojects {
    group = "dev.mpl"
    version = "1.0.0"

    tasks.withType<Jar>().configureEach {
        manifest { attributes("Implementation-Title" to "McPluginLab", "Implementation-Version" to project.version) }
    }

    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }

    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<KotlinJvmProjectExtension> {
            compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
        }
        tasks.withType<JavaCompile>().configureEach { options.release.set(21) }
        tasks.withType<Test>().configureEach { useJUnitPlatform() }
    }
}
