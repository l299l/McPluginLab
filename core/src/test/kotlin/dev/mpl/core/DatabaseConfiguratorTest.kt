package dev.mpl.core

import dev.mpl.api.DatabaseInfo
import dev.mpl.api.DatabaseSettings
import dev.mpl.api.DbBinding
import dev.mpl.api.InstanceDef
import dev.mpl.api.InstanceType
import dev.mpl.api.PluginLink
import dev.mpl.api.ProjectDef
import dev.mpl.core.database.DatabaseConfigurator
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class DatabaseConfiguratorTest {
    private val root = Files.createTempDirectory("mpl-db")
    private val info = DatabaseInfo("127.0.0.1", 3307, "lab", "mpl", "s3cret", "jdbc:mariadb://127.0.0.1:3307/lab")
    private val binding = DbBinding(
        "plugins/Shop/config.yml",
        mapOf("storage.host" to "{host}", "storage.port" to "{port}", "storage.password" to "{password}", "storage.url" to "{jdbc}"),
    )

    private fun server(name: String) = InstanceDef("p", name, InstanceType.PAPER, dir = root.resolve(name).createDirectories().toString(), port = 25565)

    @Test
    fun patchesExistingYamlAndKeepsOtherKeys() {
        val dir = root.resolve("lobby").resolve("plugins/Shop").createDirectories()
        dir.resolve("config.yml").writeText("storage:\n  host: localhost\n  port: 1\n  user: x\n")

        DatabaseConfigurator.apply(root.resolve("lobby"), binding, info)

        val text = dir.resolve("config.yml").readText()
        assertContains(text, "host: '127.0.0.1'")
        assertContains(text, "port: 3307")
        assertContains(text, "password: 's3cret'")
        assertContains(text, "user: x")
    }

    @Test
    fun missingConfigFileIsLeftAlone() {
        DatabaseConfigurator.apply(root.resolve("lobby"), binding, info)
        assertFalse(root.resolve("lobby/plugins/Shop/config.yml").exists())
    }

    @Test
    fun propertiesFilesAreUpdated() {
        val file = root.resolve("a/plugins/P/db.properties").also { it.parent.createDirectories() }
        file.writeText("url=old\nother=1\n")
        DatabaseConfigurator.apply(root.resolve("a"), DbBinding("plugins/P/db.properties", mapOf("url" to "{jdbc}")), info)
        assertEquals(listOf("url=jdbc:mariadb://127.0.0.1:3307/lab", "other=1"), file.readText().lines().filter { it.isNotBlank() })
    }

    @Test
    fun rejectsFilesOutsideTheServerFolder() {
        assertFailsWith<IllegalArgumentException> {
            DatabaseConfigurator.apply(root.resolve("a"), DbBinding("../b/config.yml", mapOf("k" to "v")), info)
        }
    }

    @Test
    fun syncTargetsOnlyLinkedServers() {
        val lobby = server("lobby")
        val other = server("other")
        val db = InstanceDef("p", "db", InstanceType.MARIADB, dir = root.resolve("db").createDirectories().toString(), port = 3307,
            database = DatabaseSettings("lab", password = "s3cret"))
        listOf(lobby, other).forEach { root.resolve(it.name).resolve("plugins/Shop").createDirectories().resolve("config.yml").writeText("storage:\n  host: x\n") }
        val project = ProjectDef("p", root.toString(), plugins = listOf(PluginLink("Shop", "x.jar", targets = listOf("lobby"), db = binding)))

        DatabaseConfigurator.sync(project, listOf(lobby, other, db)) { info }

        assertContains(root.resolve("lobby/plugins/Shop/config.yml").readText(), "host: '127.0.0.1'")
        assertContains(root.resolve("other/plugins/Shop/config.yml").readText(), "host: x")
    }

    @Test
    fun explicitlyLinkedProxiesAreBoundToo() {
        val proxy = InstanceDef("p", "proxy", InstanceType.VELOCITY, dir = root.resolve("proxy").createDirectories().toString(), port = 25577)
        val db = InstanceDef("p", "db", InstanceType.MARIADB, dir = root.resolve("db").createDirectories().toString(), port = 3307,
            database = DatabaseSettings("lab", password = "s3cret"))
        root.resolve("proxy/plugins/Px").createDirectories().resolve("config.yml").writeText("mysql:\n  host: \"x\"\n  port: 1\n")
        val link = PluginLink("Px", "x.jar", targets = listOf("proxy"), db = DbBinding("plugins/Px/config.yml", mapOf("mysql.host" to "{host}", "mysql.port" to "{port}")))

        DatabaseConfigurator.sync(ProjectDef("p", root.toString(), plugins = listOf(link)), listOf(proxy, db)) { info }

        val text = root.resolve("proxy/plugins/Px/config.yml").readText()
        assertContains(text, "host: '127.0.0.1'")
        assertContains(text, "port: 3307")
    }
}
