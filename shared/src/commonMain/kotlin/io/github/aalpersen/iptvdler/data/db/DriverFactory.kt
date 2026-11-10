package io.github.aalpersen.iptvdler.data.db

import app.cash.sqldelight.db.SqlDriver

/** Creates the platform SQLite driver for [IptvdlerDatabase]. Provided by each platform's Koin module. */
fun interface DriverFactory {
    fun createDriver(): SqlDriver
}

fun DriverFactory.createDatabase(): IptvdlerDatabase = IptvdlerDatabase(createDriver())
