plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

dependencies {
    implementation(project(":daemon"))
    implementation(project(":gui"))
    implementation(project(":mcp"))
    implementation(libs.clikt)
    runtimeOnly(libs.slf4j.simple)
}

application {
    mainClass.set("dev.mpl.cli.MainKt")
    applicationName = "mpl"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}
