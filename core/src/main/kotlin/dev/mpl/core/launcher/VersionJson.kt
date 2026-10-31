package dev.mpl.core.launcher

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable data class VersionManifest(val latest: Latest, val versions: List<ManifestEntry>)
@Serializable data class Latest(val release: String, val snapshot: String)
@Serializable data class ManifestEntry(val id: String, val type: String, val url: String, val sha1: String)

@Serializable
data class VersionJson(
    val id: String,
    val type: String,
    val mainClass: String,
    val assetIndex: AssetIndexRef,
    val downloads: Downloads,
    val libraries: List<Library>,
    val arguments: Arguments? = null,
    val minecraftArguments: String? = null,
    val javaVersion: JavaVersion? = null,
)

@Serializable data class AssetIndexRef(val id: String, val sha1: String, val url: String)
@Serializable data class Downloads(val client: Artifact)
@Serializable data class Artifact(val path: String? = null, val sha1: String, val url: String)
@Serializable data class JavaVersion(val majorVersion: Int)
@Serializable data class Arguments(val game: List<JsonElement> = emptyList(), val jvm: List<JsonElement> = emptyList())

@Serializable
data class Library(
    val name: String,
    val downloads: LibraryDownloads? = null,
    val rules: List<Rule>? = null,
    val natives: Map<String, String>? = null,
)

@Serializable data class LibraryDownloads(val artifact: Artifact? = null, val classifiers: Map<String, Artifact>? = null)
@Serializable data class Rule(val action: String, val os: OsRule? = null, val features: Map<String, Boolean>? = null)
@Serializable data class OsRule(val name: String? = null, val arch: String? = null)

@Serializable data class AssetIndex(val objects: Map<String, AssetObject>)
@Serializable data class AssetObject(val hash: String, val size: Long)
