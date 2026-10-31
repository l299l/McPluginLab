package dev.mpl.gui

private val home = System.getProperty("user.home")

val String.shortPath: String get() = if (startsWith(home)) "~" + removePrefix(home) else this
