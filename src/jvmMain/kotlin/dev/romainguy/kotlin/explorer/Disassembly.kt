/*
 * Copyright (C) 2023 Romain Guy
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.romainguy.kotlin.explorer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors
import kotlin.io.path.extension

fun CoroutineScope.disassemble(
    toolPaths: ToolPaths,
    source: String,
    onDex: (String) -> Unit,
    onOat: (String) -> Unit,
    onStatusUpdate: (String) -> Unit
) {
    launch(Dispatchers.IO) {
        launch { onStatusUpdate("Compiling Kotlin…") }

        val directory = toolPaths.tempDirectory
        cleanupClasses(directory)

        val path = directory.resolve("KotlinExplorer.kt")
        Files.writeString(path, source)

        val kotlinc = process(
            *buildKotlincCommand(toolPaths, path),
            directory = directory
        )

        if (kotlinc.exitCode != 0) {
            launch {
                onDex(kotlinc.output.replace(path.parent.toString() + "/", ""))
            }
            return@launch
        }

        launch { onStatusUpdate("Optimizing with R8…") }

        writeR8Rules(directory)

        val r8 = process(
            *buildR8Command(toolPaths, directory),
            directory = directory
        )

        if (r8.exitCode != 0) {
            launch { onDex(r8.output) }
            return@launch
        }

        launch { onStatusUpdate("Disassembling DEX…") }

        val dexdump = process(
            toolPaths.buildToolsDirectory.resolve("dexdump").toString(),
            "-d",
            "classes.dex",
            directory = directory
        )

        if (dexdump.exitCode != 0) {
            launch { onDex(dexdump.output) }
            return@launch
        }

        launch { onDex(filterDex(dexdump.output)) }

        launch { onStatusUpdate("AOT compilation…") }

        val push = process(
            toolPaths.adb.toString(),
            "push",
            "classes.dex",
            "/sdcard/classes.dex",
            directory = directory
        )

        if (push.exitCode != 0) {
            launch { onOat(push.output) }
            return@launch
        }

        val dex2oat = process(
            toolPaths.adb.toString(),
            "shell",
            "dex2oat",
            "--dex-file=/sdcard/classes.dex",
            "--oat-file=/sdcard/classes.oat",
            directory = directory
        )

        if (dex2oat.exitCode != 0) {
            launch { onOat(dex2oat.output) }
            return@launch
        }

        launch { onStatusUpdate("Disassembling OAT…") }

        val oatdump = process(
            toolPaths.adb.toString(),
            "shell",
            "oatdump",
            "--oat-file=/sdcard/classes.oat",
            directory = directory
        )

        launch { onOat(filterOat(oatdump.output)) }

        if (oatdump.exitCode != 0) {
            return@launch
        }

        launch { onStatusUpdate("Ready") }
    }
}

private fun buildR8Command(toolPaths: ToolPaths, directory: Path): Array<String> {
    val command = mutableListOf(
        "java",
        "-classpath",
        toolPaths.d8.toString(),
        "com.android.tools.r8.R8",
        "--release",
        "--min-api",
        "21",
        "--pg-conf",
        "rules.txt",
        "--output",
        ".",
        "--lib",
        toolPaths.platform.toString()
    )

    val classFiles = Files
        .list(directory)
        .filter { path -> path.extension == "class" }
        .map { file -> file.fileName.toString() }
        .sorted()
        .collect(Collectors.toList())
    command.addAll(classFiles)

    toolPaths.kotlinLibs.forEach { jar ->
        command += jar.toString()
    }

    return command.toTypedArray()
}

private fun buildKotlincCommand(toolPaths: ToolPaths, path: Path): Array<String> {
    val command = mutableListOf(
        toolPaths.kotlinc.toString(),
        path.toString(),
        "-Xmulti-platform",
        "-classpath",
        toolPaths.kotlinLibs.joinToString(":") { jar -> jar.toString() }
            + ":${toolPaths.platform}"
    )

    return command.toTypedArray()
}

private fun writeR8Rules(directory: Path) {
    // Match $ANDROID_HOME/tools/proguard/proguard-android-optimize.txt
    Files.writeString(
        directory.resolve("rules.txt"),
        """-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*
        -optimizationpasses 5
        -allowaccessmodification
        -dontpreverify
        -dontobfuscate
        -keep,allowoptimization class !kotlin.**,!kotlinx.** {
          <methods>;
        }""".trimIndent()
    )
}

private fun cleanupClasses(directory: Path) {
    Files
        .list(directory)
        .filter { path -> path.extension == "class" }
        .forEach { path -> path.toFile().delete() }
}

private val BuiltInKotlinClass = Regex("^(kotlin|kotlinx|java|javax|org\\.(intellij|jetbrains))\\..+")

private val DexBytecodePattern = Regex("^[0-9a-fA-F]+:[^|]+\\|([0-9a-fA-F]+: .+)")
private val DexMethodNamePattern = Regex("^name\\s+:\\s+'(.+)'")
private val DexMethodTypePattern = Regex("^type\\s+:\\s+'(.+)'")
private val DexMethodProperty = Regex("^(\\s+[a-zA-Z ]+[:-].*)|([0-9a-fA-F]+:[^|]+\\|\\[.+)")
private val DexClassNamePattern = Regex("Class descriptor\\s+:\\s+'L(.+);'")

private val OatClassNamePattern = Regex("^\\d+: L([^;]+); \\(offset=[0-9a-zA-Zx]+\\) \\(type_idx=\\d+\\).+")
private val OatMethodPattern = Regex("^\\s+\\d+:\\s+(.+)\\s+\\(dex_method_idx=\\d+\\)")
private val OatCodePattern = Regex("^\\s+(0x[a-zA-Z0-9]+):\\s+[a-zA-Z0-9]+\\s+(.+)")

private fun filterOat(oat: String) = buildString {
    val indent = "        "
    val lines = oat.lineSequence().iterator()

    if (!lines.consumeUntil("OatDexFile:")) return@buildString

    var insideClass = false
    var insideMethod = false
    var firstMethod = false

    while (lines.hasNext()) {
        val line = lines.next()

        var match: MatchResult? = null

        if (insideClass) {
            if (insideMethod) {
                match = OatCodePattern.matchEntire(line)
                if (match != null && match.groupValues.isNotEmpty()) {
                    appendLine("$indent${match.groupValues[1]}: ${match.groupValues[2]}")
                }
            }

            if (match === null) {
                match = OatMethodPattern.matchEntire(line)
                if (match != null && match.groupValues.isNotEmpty()) {
                    val name = match.groupValues[1]
                    if (!firstMethod) appendLine()
                    firstMethod = false

                    appendLine("    $name")

                    if (!lines.consumeUntil("CODE: ")) break
                    insideMethod = true
                }
            }
        }

        if (match === null) {
            match = OatClassNamePattern.matchEntire(line)
            if (match != null && match.groupValues.isNotEmpty()) {
                val className = match.groupValues[1].replace('/', '.')

                val suppress = className.matches(BuiltInKotlinClass)
                if (!suppress) appendLine("class $className")

                insideMethod = false
                firstMethod = true
                insideClass = !suppress
            }
        }
    }
}

private fun filterDex(dex: String) = buildString {
    val lines = dex.lineSequence().iterator()

    while (lines.hasNext()) {
        if (!lines.consumeUntil("Class #")) break

        val line = lines.next()
        val className = extractDexClassName(line)

        val suppress = className.matches(BuiltInKotlinClass)
        if (!suppress) {
            appendLine("class $className")
        }

        if (!lines.consumeUntil("Direct methods")) break
        lines.extractDexMethods(if (suppress) StringBuilder() else this, className, "        ")
    }
}

private fun Iterator<String>.extractDexMethods(
    output: StringBuilder,
    className: String,
    indent: String
) {
    while (hasNext()) {
        var line = next()
        if (line.isEmpty()) break

        if (line.startsWith("    #")) {
            if (!hasNext()) break
            line = next()

            val name = extractDexMethodName(line)

            if (!hasNext()) break
            line = next()

            val type = extractDexMethodType(line)

            output.appendLine("    $name$type // $className.$name()")

            while (hasNext()) {
                line = next()

                val match = DexBytecodePattern.matchEntire(line)
                if (match != null && match.groupValues.isNotEmpty()) {
                    output.appendLine("$indent${match.groupValues[1]}")
                } else if (!DexMethodProperty.matches(line)) {
                    break
                }
            }

            output.appendLine()
        } else if (!line.trim().startsWith("Virtual methods")) {
            break
        }
    }
}

private fun extractDexMethodName(line: String): String {
    val match = DexMethodNamePattern.matchEntire(line.trim())
    if (match != null && match.groupValues.isNotEmpty()) {
        return match.groupValues[1]
    }
    return "<UNKNOWN>"
}

private fun extractDexMethodType(line: String): String {
    val match = DexMethodTypePattern.matchEntire(line.trim())
    if (match != null && match.groupValues.isNotEmpty()) {
        return match.groupValues[1]
    }
    return "<UNKNOWN>"
}

private fun extractDexClassName(line: String): String {
    val match = DexClassNamePattern.matchEntire(line.trim())
    if (match != null && match.groupValues.isNotEmpty()) {
        return match.groupValues[1].replace('/', '.')
    }
    return "<UNKNOWN>"
}

private fun Iterator<String>.consumeUntil(prefix: String): Boolean {
    while (hasNext()) {
        val line = next()
        if (line.trim().startsWith(prefix)) return true
    }
    return false
}
