package dev.mpl.core

import dev.mpl.core.util.TomlPatch
import dev.mpl.core.util.YamlPatch
import kotlin.test.Test
import kotlin.test.assertEquals

class ConfigPatchTest {
    private val paperGlobal = """
        |# header
        |proxies:
        |  bungee-cord:
        |    online-mode: true
        |  proxy-protocol: false
        |  velocity:
        |    enabled: false
        |    online-mode: true
        |    secret: ''
        |scoreboards:
        |  track-plugin-scoreboards: false
    """.trimMargin()

    @Test
    fun replacesNestedYamlValue() {
        val patched = YamlPatch.set(paperGlobal, listOf("proxies", "velocity", "enabled"), "true")
        assertEquals(paperGlobal.replace("    enabled: false", "    enabled: true"), patched)
    }

    @Test
    fun insertsMissingYamlKeys() {
        val patched = YamlPatch.set("other: 1", listOf("proxies", "velocity", "secret"), "'abc'")
        assertEquals("other: 1\nproxies:\n  velocity:\n    secret: 'abc'", patched)
    }

    @Test
    fun insertsMissingChildInsideExistingBlock() {
        val patched = YamlPatch.set(paperGlobal, listOf("proxies", "velocity", "extra"), "1")
        assertEquals(paperGlobal.replace("    secret: ''\n", "    secret: ''\n    extra: 1\n"), patched)
    }

    @Test
    fun replacesTomlSection() {
        val toml = "bind = \"0.0.0.0:25565\"\n\n[servers]\nlobby = \"127.0.0.1:1\"\ntry = [\n  \"lobby\"\n]\n\n[advanced]\nx = 1"
        val patched = TomlPatch.replaceSection(TomlPatch.setTopLevel(toml, "bind", "\"0.0.0.0:25577\""), "servers", listOf("a = \"127.0.0.1:2\"", "try = [\"a\"]"))
        assertEquals("bind = \"0.0.0.0:25577\"\n\n[servers]\na = \"127.0.0.1:2\"\ntry = [\"a\"]\n\n[advanced]\nx = 1", patched)
    }
}
