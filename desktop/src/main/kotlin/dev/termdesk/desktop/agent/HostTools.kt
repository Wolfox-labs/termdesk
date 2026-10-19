package dev.termdesk.desktop.agent

import java.io.File

internal fun isWindowsHost(osName: String = System.getProperty("os.name")): Boolean =
    osName.startsWith("Windows", ignoreCase = true)

/** Find an executable in PATH without invoking a shell-specific lookup command. */
internal fun findOnPath(
    names: List<String>,
    environment: Map<String, String> = System.getenv(),
    osName: String = System.getProperty("os.name"),
    pathSeparator: String = File.pathSeparator,
): File? {
    val windows = isWindowsHost(osName)
    val path = environment.entries.firstOrNull {
        if (windows) it.key.equals("PATH", ignoreCase = true) else it.key == "PATH"
    }?.value ?: return null
    val extensions = if (windows) {
        environment.entries.firstOrNull { it.key.equals("PATHEXT", ignoreCase = true) }?.value
            ?.split(';')?.filter(String::isNotBlank)
            .orEmpty().ifEmpty { listOf(".COM", ".EXE", ".BAT", ".CMD") }
    } else listOf("")
    for (directory in path.split(pathSeparator).filter(String::isNotBlank)) {
        for (name in names) {
            val hasExtension = File(name).extension.isNotEmpty()
            val candidates = if (windows && !hasExtension) extensions.map { name + it } else listOf(name)
            for (candidate in candidates) {
                val file = File(directory, candidate)
                if (file.isFile && (windows || file.canExecute())) return file
            }
        }
    }
    return null
}

internal fun findNodeExecutable(
    environment: Map<String, String> = System.getenv(),
    osName: String = System.getProperty("os.name"),
): File? {
    val override = environment["TERMDESK_NODE"]?.takeIf(String::isNotBlank)?.let(::File)
    if (override?.isFile == true) return override

    val windows = isWindowsHost(osName)
    if (windows) {
        val installDirectories = listOfNotNull(
            environment.entries.firstOrNull { it.key.equals("ProgramFiles", ignoreCase = true) }?.value,
            environment.entries.firstOrNull { it.key.equals("LOCALAPPDATA", ignoreCase = true) }?.value
                ?.let { "$it/Programs" },
        )
        installDirectories.map { File(it, "nodejs/node.exe") }
            .firstOrNull(File::isFile)?.let { return it }
    }

    return findOnPath(if (windows) listOf("node.exe", "node") else listOf("node"), environment, osName)
}

internal fun findWindowsTaskkill(
    environment: Map<String, String> = System.getenv(),
    pathSeparator: String = File.pathSeparator,
): File? {
    val systemRoot = environment.entries.firstOrNull {
        it.key.equals("SystemRoot", ignoreCase = true) || it.key.equals("WINDIR", ignoreCase = true)
    }?.value?.takeIf(String::isNotBlank)
    val fromSystemRoot = systemRoot?.let { File(it, "System32/taskkill.exe") }
        ?.takeIf(File::isFile)
    return fromSystemRoot ?: findOnPath(listOf("taskkill.exe", "taskkill"), environment, "Windows", pathSeparator)
}
