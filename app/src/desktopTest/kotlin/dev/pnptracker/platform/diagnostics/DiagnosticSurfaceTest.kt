package dev.pnptracker.platform.diagnostics

import dev.pnptracker.domain.backup.restore.BackupPlace
import dev.pnptracker.domain.diagnostics.DiagnosticArea
import dev.pnptracker.domain.diagnostics.DiagnosticEvent
import dev.pnptracker.domain.diagnostics.DiagnosticLevel
import dev.pnptracker.domain.diagnostics.DiagnosticPlace
import dev.pnptracker.domain.diagnostics.DiagnosticRecord
import dev.pnptracker.domain.diagnostics.Diagnostics
import dev.pnptracker.domain.diagnostics.ExceptionClassName
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * What the diagnostic API cannot be given, proved from its shape rather than
 * from any one call (PLAN 14.7.1), and where it is — and is not yet — used.
 */
class DiagnosticSurfaceTest {
    /** Every type a value on its way into a record is allowed to have. */
    private val allowedParameterTypes: Set<Class<*>> =
        setOf(
            DiagnosticRecord::class.java,
            DiagnosticEvent::class.java,
            DiagnosticLevel::class.java,
            DiagnosticArea::class.java,
            Enum::class.java,
            BackupPlace::class.java,
            Throwable::class.java,
            java.lang.Integer::class.java,
            java.lang.Long::class.java,
            Int::class.javaPrimitiveType!!,
            Class.forName("kotlin.jvm.internal.DefaultConstructorMarker"),
        )

    @Test
    fun `nothing that builds or takes a record accepts text, paths, identifiers or bytes`() {
        val surfaces =
            listOf(DiagnosticRecord::class.java, Diagnostics::class.java, ExceptionClassName::class.java, DiagnosticPlace::class.java)
        val offenders = mutableListOf<String>()

        surfaces.forEach { type ->
            type.constructors
                .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
                .forEach { constructor ->
                    constructor.parameterTypes.filterNot { it in allowedParameterTypes }.forEach {
                        offenders += "${type.simpleName}(${it.name})"
                    }
                }
            (type.methods.toList() + (type.declaredClasses.flatMap { it.methods.toList() }))
                .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic && it.declaringClass.name.startsWith("dev.pnptracker") }
                .filterNot { it.name in setOf("equals") }
                .forEach { method ->
                    method.parameterTypes.filterNot { it in allowedParameterTypes }.forEach {
                        offenders += "${type.simpleName}.${method.name}(${it.name})"
                    }
                }
        }

        assertEquals(emptyList(), offenders)
        // And the one place free text could have hidden is not a constructor anybody can call.
        assertTrue(ExceptionClassName::class.java.constructors.none { Modifier.isPublic(it.modifiers) && !it.isSynthetic })
        assertTrue(DiagnosticPlace::class.java.constructors.none { Modifier.isPublic(it.modifiers) && !it.isSynthetic })
    }

    @Test
    fun `a record keeps no reference to the failure it was given`() {
        val fields = DiagnosticRecord::class.java.declaredFields.map { it.type }

        assertTrue(fields.none { Throwable::class.java.isAssignableFrom(it) }, "a record holds on to a Throwable")
        assertTrue(fields.none { BackupPlace::class.java.isAssignableFrom(it) }, "a record holds on to a raw place")
    }

    @Test
    fun `the diagnostics code never reads an exception's message or its stack`() {
        val forbidden = listOf(".message", "localizedMessage", "stackTrace", "printStackTrace", "suppressed")
        val offenders =
            diagnosticSources().flatMap { file ->
                Files.readAllLines(file).mapIndexedNotNull { index, line ->
                    val code = line.trim()
                    if (code.startsWith("*") || code.startsWith("/*") || code.startsWith("//")) return@mapIndexedNotNull null
                    forbidden.firstOrNull { it in code }?.let { "${file.fileName}:${index + 1} $it" }
                }
            }

        assertEquals(emptyList(), offenders)
    }

    @Test
    fun `no production failure path records anything yet — that is the next slice`() {
        val users =
            productionSources()
                .filterNot { it.startsWith(moduleRoot().resolve(DIAGNOSTICS_COMMON)) }
                .filterNot { it.startsWith(moduleRoot().resolve(DIAGNOSTICS_DESKTOP)) }
                .filter { file ->
                    val text = Files.readString(file)
                    listOf("DiagnosticRecord", "Diagnostics", "diagnostics.record").any { it in text }
                }.map { it.fileName.toString() }

        // Main builds the writer and closes it; it records nothing.
        assertEquals(listOf("Main.kt"), users)
        val main = Files.readString(moduleRoot().resolve("src/desktopMain/kotlin/dev/pnptracker/Main.kt"))
        assertTrue("QueuedDiagnostics.inDirectory(paths.logsDirectory" in main)
        assertTrue(".record(" !in main, "Main records something")
        assertTrue("diagnostics.close()" in main)
    }

    private fun diagnosticSources(): List<Path> =
        listOf(DIAGNOSTICS_COMMON, DIAGNOSTICS_DESKTOP).flatMap { relative ->
            Files.list(moduleRoot().resolve(relative)).use { files -> files.filter { it.toString().endsWith(".kt") }.toList() }
        }

    private fun productionSources(): List<Path> =
        listOf("src/commonMain/kotlin", "src/desktopMain/kotlin")
            .map { moduleRoot().resolve(it) }
            .flatMap { root -> Files.walk(root).use { paths -> paths.filter { it.toString().endsWith(".kt") }.toList() } }

    private fun moduleRoot(): Path {
        var candidate: Path? = Path.of("").toAbsolutePath().normalize()
        while (candidate != null) {
            listOf(candidate, candidate.resolve("app"))
                .firstOrNull { Files.isDirectory(it.resolve("src/commonMain/kotlin")) }
                ?.let { return it }
            candidate = candidate.parent
        }
        fail("could not find the module root")
    }

    private companion object {
        const val DIAGNOSTICS_COMMON = "src/commonMain/kotlin/dev/pnptracker/domain/diagnostics"
        const val DIAGNOSTICS_DESKTOP = "src/desktopMain/kotlin/dev/pnptracker/platform/diagnostics"
    }
}
