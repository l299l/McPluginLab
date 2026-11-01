package dev.mpl.api

object MplVersion {
    val current: String = MplVersion::class.java.`package`?.implementationVersion ?: "dev"
}
