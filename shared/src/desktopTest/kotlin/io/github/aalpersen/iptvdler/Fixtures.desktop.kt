package io.github.aalpersen.iptvdler

actual fun readFixture(name: String): String {
    val resource = Thread.currentThread().contextClassLoader?.getResource("fixtures/$name")
        ?: error("Missing test fixture: fixtures/$name")
    return resource.readBytes().decodeToString()
}
