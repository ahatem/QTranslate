package com.github.ahatem.qtranslate.plugins.systemocr.backend

import java.io.File

/** Finds an external executable. */
internal fun interface ExecutableLocator {
    fun locate(name: String): String?
}

/** Resolves an executable against the process `PATH` (and `PATHEXT` on Windows). */
internal object PathExecutableLocator : ExecutableLocator {

    override fun locate(name: String): String? = locate(
        name = name,
        path = System.getenv("PATH").orEmpty(),
        pathExt = System.getenv("PATHEXT"),
        osName = System.getProperty("os.name").orEmpty(),
    )

    internal fun locate(name: String, path: String, pathExt: String?, osName: String): String? {
        val isWindows = osName.startsWith("Windows", ignoreCase = true)
        val suffixes = when {
            !isWindows -> listOf("")
            name.contains('.') -> listOf("")
            else -> (pathExt ?: DEFAULT_PATHEXT).split(';').filter { it.isNotBlank() }
        }

        return path.split(File.pathSeparatorChar)
            .asSequence()
            .filter { it.isNotBlank() }
            .flatMap { directory -> suffixes.asSequence().map { suffix -> File(directory, name + suffix) } }
            .firstOrNull { it.isFile && it.canExecute() }
            ?.absolutePath
    }

    private const val DEFAULT_PATHEXT = ".COM;.EXE;.BAT;.CMD"
}
