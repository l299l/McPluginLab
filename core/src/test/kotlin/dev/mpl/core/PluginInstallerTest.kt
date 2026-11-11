package dev.mpl.core

import dev.mpl.core.plugins.PluginInstaller
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.outputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PluginInstallerTest {
    private val build = Files.createTempDirectory("mpl-build")
    private val plugins = Files.createTempDirectory("mpl-plugins").resolve("plugins").createDirectories()

    private fun jar(dir: Path, file: String, entry: String? = null, content: String = "") = dir.resolve(file).also { path ->
        ZipOutputStream(path.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(entry ?: "META-INF/MANIFEST.MF"))
            zip.write(content.toByteArray())
            zip.closeEntry()
        }
    }

    private fun pluginJar(dir: Path, file: String, name: String) = jar(dir, file, "plugin.yml", "name: $name\nversion: '1'\nmain: a.B\n")

    private fun jars() = plugins.listDirectoryEntries("*.jar").map { it.name }.sorted()

    @Test
    fun keepsUnrelatedJarWithSamePrefix() {
        pluginJar(plugins, "NewBedwars-Panel-2.6-beta.jar", "NewBedwars-Panel")
        pluginJar(plugins, "NBWEventLogger.jar", "NBWEventLogger")
        PluginInstaller.install(pluginJar(build, "NewBedwars-2.6-beta.jar", "NewBedwars"), "NewBedwars", plugins)
        assertEquals(listOf("NBWEventLogger.jar", "NewBedwars-2.6-beta.jar", "NewBedwars-Panel-2.6-beta.jar"), jars())
    }

    @Test
    fun replacesOlderVersionOfSamePlugin() {
        pluginJar(plugins, "NewBedwars-2.5-beta.jar", "NewBedwars")
        pluginJar(plugins, "NewBedwars-Panel-2.5-beta.jar", "NewBedwars-Panel")
        PluginInstaller.install(pluginJar(build, "NewBedwars-2.6-beta.jar", "NewBedwars"), "NewBedwars", plugins)
        assertEquals(listOf("NewBedwars-2.6-beta.jar", "NewBedwars-Panel-2.5-beta.jar"), jars())
    }

    @Test
    fun jarWithoutMetadataIsOnlyRemovedWhenPreviouslyDeployed() {
        jar(plugins, "Tool-0.9.jar")
        PluginInstaller.install(jar(build, "Tool-1.0.jar"), "Tool", plugins)
        assertEquals(listOf("Tool-0.9.jar", "Tool-1.0.jar"), jars())

        PluginInstaller.install(jar(build, "Tool-1.1.jar"), "Tool", plugins)
        assertEquals(listOf("Tool-0.9.jar", "Tool-1.1.jar"), jars())
    }

    @Test
    fun recordIsKeptPerLink() {
        PluginInstaller.install(jar(build, "A-1.jar"), "A", plugins)
        PluginInstaller.install(jar(build, "B-1.jar"), "B", plugins)
        PluginInstaller.install(jar(build, "A-2.jar"), "A", plugins)
        assertEquals(listOf("A-2.jar", "B-1.jar"), jars())
        assertTrue(plugins.resolve(PluginInstaller.RECORD_FILE).exists())
    }

    @Test
    fun readsPluginNameFromDescriptors() {
        assertEquals("Shop", PluginInstaller.pluginName(jar(build, "a.jar", "paper-plugin.yml", "version: 1\nname: 'Shop' # x\n")))
        assertEquals("Lobby", PluginInstaller.pluginName(jar(build, "b.jar", "plugin.yml", "name: Lobby # comment\n")))
        assertEquals("proxyplugin", PluginInstaller.pluginName(jar(build, "c.jar", "velocity-plugin.json", """{"id":"proxyplugin","name":"Proxy"}""")))
        assertNull(PluginInstaller.pluginName(jar(build, "d.jar")))
        assertNull(PluginInstaller.pluginName(build.resolve("missing.jar")))
    }
}
