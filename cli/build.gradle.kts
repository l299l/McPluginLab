plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

dependencies {
    implementation(project(":daemon"))
    implementation(libs.clikt)
    runtimeOnly(libs.slf4j.simple)
}

application {
    mainClass.set("dev.mpl.cli.MainKt")
    applicationName = "mpl"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}
