package io.github.aalpersen.iptvdler

/** Reads `src/commonTest/resources/fixtures/<name>` exactly as stored (BOM and line endings included). */
expect fun readFixture(name: String): String
