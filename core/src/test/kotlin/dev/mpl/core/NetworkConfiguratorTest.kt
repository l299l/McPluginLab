package dev.mpl.core

import dev.mpl.api.InstanceDef
import dev.mpl.api.InstanceType
import dev.mpl.api.ProjectDef
import dev.mpl.core.network.NetworkConfigurator
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class NetworkConfiguratorTest {
    private val root = Files.createTempDirectory("mpl-network")
    private val project = ProjectDef("p", root.toString(), forwardingSecret = "secret")

    private fun instance(name: String, type: InstanceType, port: Int, proxied: Boolean = true) =
        InstanceDef("p", name, type, version = "26.3", dir = root.resolve(name).createDirectories().toString(), port = port, proxied = proxied)

    @Test
    fun excludedServersStayOutOfTheProxyAndRunStandalone() {
        val proxy = instance("proxy", InstanceType.VELOCITY, 25577)
        root.resolve("proxy/velocity.toml").writeText("bind = \"0.0.0.0:25565\"\n\n[servers]\n\n[forced-hosts]\n")
        val lobby = instance("lobby", InstanceType.PAPER, 25565)
        val solo = instance("solo", InstanceType.PAPER, 25566, proxied = false)
        root.resolve("solo/config").createDirectories().resolve("paper-global.yml").writeText("proxies:\n  velocity:\n    enabled: true\n")

        NetworkConfigurator.sync(project, listOf(proxy, lobby, solo))

        val velocity = root.resolve("proxy/velocity.toml").readText()
        assertContains(velocity, "lobby = \"127.0.0.1:25565\"")
        assertFalse("solo" in velocity)
        assertContains(root.resolve("lobby/config/paper-global.yml").readText(), "enabled: true")
        assertContains(root.resolve("solo/config/paper-global.yml").readText(), "enabled: false")
    }
}
