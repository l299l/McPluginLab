plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.compose)
}

dependencies {
    api(project(":api"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.jediterm.core)
    implementation(libs.jediterm.ui)
    implementation(libs.rsyntaxtextarea)
}
