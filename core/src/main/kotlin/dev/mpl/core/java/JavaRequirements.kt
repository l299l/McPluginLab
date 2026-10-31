package dev.mpl.core.java

import dev.mpl.api.InstanceType

object JavaRequirements {
    fun forInstance(type: InstanceType, version: String): Int = when (type.role) {
        InstanceType.Role.PROXY -> 21
        InstanceType.Role.BOTS -> 25
        else -> forMinecraft(version)
    }

    fun forMinecraft(version: String): Int {
        val parts = version.split('.', '-').mapNotNull { it.toIntOrNull() }
        val major = parts.getOrElse(0) { 26 }
        val minor = parts.getOrElse(1) { 0 }
        val patch = parts.getOrElse(2) { 0 }
        return when {
            major > 1 -> 25
            minor >= 21 || (minor == 20 && patch >= 5) -> 21
            minor >= 17 -> 17
            else -> 8
        }
    }
}
