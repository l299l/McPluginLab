plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":api"))
    implementation(libs.kaml)
    implementation(libs.pty4j)
    testImplementation(libs.kotlin.test)
}
