package io.github.aalpersen.iptvdler.core.download

import io.github.aalpersen.iptvdler.core.storage.DownloadStorage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.Collections
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.delay
import okio.Buffer
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.ForwardingSink
import okio.ForwardingSource
import okio.Sink
import okio.Source
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

class FakeDownloadStorage(var freeBytes: Long? = null) : DownloadStorage {
    val fake = FakeFileSystem()

    /** FakeFileSystem isn't thread-safe (the real one is); segment workers use it concurrently. */
    override val fileSystem: FileSystem = SynchronizedFileSystem(fake)
    private val root = "/downloads".toPath()

    override fun downloadsDirectory(): Path = root.also { fake.createDirectories(it) }
    override fun freeBytes(path: Path): Long? = freeBytes
}

class SynchronizedFileSystem(delegate: FileSystem) : ForwardingFileSystem(delegate) {
    private val lock = Any()

    override fun metadataOrNull(path: Path) = synchronized(lock) { super.metadataOrNull(path) }
    override fun list(dir: Path) = synchronized(lock) { super.list(dir) }
    override fun listOrNull(dir: Path) = synchronized(lock) { super.listOrNull(dir) }
    override fun createDirectory(dir: Path, mustCreate: Boolean) = synchronized(lock) { super.createDirectory(dir, mustCreate) }
    override fun atomicMove(source: Path, target: Path) = synchronized(lock) { super.atomicMove(source, target) }
    override fun delete(path: Path, mustExist: Boolean) = synchronized(lock) { super.delete(path, mustExist) }
    override fun source(file: Path): Source = synchronized(lock) { LockedSource(super.source(file)) }
    override fun sink(file: Path, mustCreate: Boolean): Sink = synchronized(lock) { LockedSink(super.sink(file, mustCreate)) }
    override fun appendingSink(file: Path, mustExist: Boolean): Sink = synchronized(lock) { LockedSink(super.appendingSink(file, mustExist)) }

    private inner class LockedSource(d: Source) : ForwardingSource(d) {
        override fun read(sink: Buffer, byteCount: Long) = synchronized(lock) { super.read(sink, byteCount) }
        override fun close() = synchronized(lock) { super.close() }
    }

    private inner class LockedSink(d: Sink) : ForwardingSink(d) {
        override fun write(source: Buffer, byteCount: Long) = synchronized(lock) { super.write(source, byteCount) }
        override fun close() = synchronized(lock) { super.close() }
    }
}

/**
 * Mock HTTP server for downloads: static resources with Range support, plus per-URL failure
 * injection and optional latency. Records every requested URL.
 */
class TestServer {
    private val resources = HashMap<String, ByteArray>()
    private val failures = HashMap<String, ArrayDeque<HttpStatusCode>>()
    private val contentTypes = HashMap<String, String>()
    val requests: MutableList<String> = Collections.synchronizedList(ArrayList())
    var ignoreRange = false
    var latencyMillis = 0L

    fun put(url: String, bytes: ByteArray, contentType: String? = null) {
        resources[url] = bytes
        contentType?.let { contentTypes[url] = it }
    }

    fun put(url: String, text: String) = put(url, text.encodeToByteArray())

    /** The next [statuses] requests to [url] fail with these codes, in order. */
    fun failNext(url: String, vararg statuses: HttpStatusCode) = synchronized(failures) {
        failures.getOrPut(url) { ArrayDeque() }.addAll(statuses)
    }

    fun requestCount(url: String) = requests.count { it == url }

    val client = HttpClient(MockEngine) {
        install(HttpTimeout)
        engine {
            addHandler { request ->
                val url = request.url.toString()
                requests += url
                if (latencyMillis > 0) delay(latencyMillis)
                synchronized(failures) { failures[url]?.removeFirstOrNull() }?.let { return@addHandler respond("", it) }
                val body = resources[url] ?: return@addHandler respond("", HttpStatusCode.NotFound)
                val type = contentTypes[url]?.let { arrayOf(HttpHeaders.ContentType to listOf(it)) } ?: emptyArray()

                val range = request.headers[HttpHeaders.Range]?.removePrefix("bytes=")
                if (range == null || ignoreRange) {
                    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength to listOf(body.size.toString()), *type))
                } else {
                    val start = range.substringBefore('-').toInt()
                    val end = range.substringAfter('-').toIntOrNull() ?: (body.size - 1)
                    respond(
                        body.copyOfRange(start, end + 1),
                        HttpStatusCode.PartialContent,
                        headersOf(
                            HttpHeaders.ContentRange to listOf("bytes $start-$end/${body.size}"),
                            HttpHeaders.ContentLength to listOf((end - start + 1).toString()),
                            *type,
                        ),
                    )
                }
            }
        }
    }
}

object TestCrypto {
    fun encrypt(plain: ByteArray, key: ByteArray, iv: ByteArray): ByteArray =
        Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            doFinal(plain)
        }
}

/** Deterministic pseudo-media bytes for segment [index]. */
fun segmentBytes(index: Int, size: Int = 1000 + index * 37): ByteArray = ByteArray(size) { ((it * 31 + index * 7) % 251).toByte() }
