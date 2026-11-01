package dev.mpl.api

object Network {
    fun proxyOf(instances: List<InstanceDef>): InstanceDef? = instances.firstOrNull { it.type.isProxy }

    fun backendsOf(instances: List<InstanceDef>): List<InstanceDef> =
        instances.filter { it.type.isServer && it.proxied }.sortedByDescending { it.name.contains("lobby", ignoreCase = true) }

    fun usesModernForwarding(proxy: InstanceDef, backends: List<InstanceDef>): Boolean =
        proxy.type == InstanceType.VELOCITY && backends.all { it.type.isPaperBased && atLeast(it.version, 13) }

    fun atLeast(version: String, minor: Int): Boolean {
        val parts = version.split('.', '-').mapNotNull { it.toIntOrNull() }
        val major = parts.getOrNull(0) ?: return true
        return major > 1 || (parts.getOrNull(1) ?: 0) >= minor
    }
}
