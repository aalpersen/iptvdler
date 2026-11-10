package io.github.aalpersen.iptvdler.platform

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SingleInstanceTest {

    /** A second process must not get the lock while this one holds it (the OS frees it when we exit). */
    @Test
    fun secondProcessIsRefusedUntilTheFirstExits() {
        val dir = File("build/single-instance-test").apply { deleteRecursively(); mkdirs() }
        assertTrue(SingleInstance.acquire(dir), "this process takes the lock")

        assertEquals("locked", runLockProbe(dir), "another process must be refused")
    }

    /** Runs a tiny JVM that tries the same lock file and prints "acquired" or "locked". */
    private fun runLockProbe(dir: File): String {
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val source = File(dir, "Probe.java").apply {
            writeText(
                """
                import java.nio.channels.FileChannel;
                import java.nio.file.*;
                public class Probe {
                    public static void main(String[] a) throws Exception {
                        FileChannel c = FileChannel.open(Paths.get(a[0]), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                        System.out.print(c.tryLock() == null ? "locked" : "acquired");
                    }
                }
                """.trimIndent(),
            )
        }
        val process = ProcessBuilder(java, source.absolutePath, File(dir, "iptvdler.lock").absolutePath)
            .redirectErrorStream(true)
            .start()
        process.waitFor(60, TimeUnit.SECONDS)
        return process.inputStream.bufferedReader().readText().trim()
    }
}
