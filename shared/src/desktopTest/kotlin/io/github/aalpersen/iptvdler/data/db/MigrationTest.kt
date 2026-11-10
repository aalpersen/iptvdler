package io.github.aalpersen.iptvdler.data.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals

class MigrationTest {

    /** v5 (M6) keeps working after 5.sqm: old rows stay, the Xtream columns exist and are writable. */
    @Test
    fun v5ToV6AddsXtreamColumns() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        V5_TABLES.forEach { driver.execute(null, it, 0) }
        driver.execute(null, "INSERT INTO playlist(name, source, source_type, created_at) VALUES ('Old', 'http://x/list.m3u', 'URL', 1)", 0)
        driver.execute(null, "INSERT INTO channel(playlist_id, sort_index, name, url, group_title, content_type) VALUES (1, 0, 'Ch', 'http://x/1.ts', '', 'LIVE')", 0)

        IptvdlerDatabase.Schema.migrate(driver, 5, IptvdlerDatabase.Schema.version)
        val db = IptvdlerDatabase(driver)

        val old = db.playlistQueries.selectById(1).executeAsOne()
        assertEquals("Old", old.name)
        assertEquals(null, old.xtream_username)
        db.playlistQueries.markAccount(status = "Active", expiresAt = 42, id = 1)
        assertEquals(42, db.playlistQueries.selectById(1).executeAsOne().account_expires_at)
        assertEquals(null, db.channelQueries.selectById(1).executeAsOne().series_id)
    }

    private companion object {
        val V5_TABLES = listOf(
            """
            CREATE TABLE playlist (
                id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, source TEXT NOT NULL, source_type TEXT NOT NULL,
                refresh_interval_minutes INTEGER, last_refreshed_at INTEGER, last_attempt_at INTEGER,
                channel_count INTEGER NOT NULL DEFAULT 0, epg_url TEXT, last_error TEXT, created_at INTEGER NOT NULL
            )
            """,
            """
            CREATE TABLE channel (
                id INTEGER PRIMARY KEY AUTOINCREMENT, playlist_id INTEGER NOT NULL, sort_index INTEGER NOT NULL,
                name TEXT NOT NULL, url TEXT NOT NULL, logo TEXT, group_title TEXT NOT NULL, tvg_id TEXT, tvg_name TEXT,
                content_type TEXT NOT NULL, http_headers TEXT, catchup TEXT, catchup_source TEXT, duration_seconds REAL,
                search_name TEXT NOT NULL DEFAULT ''
            )
            """,
            """
            CREATE TABLE channel_staging (
                playlist_id INTEGER NOT NULL, sort_index INTEGER NOT NULL, name TEXT NOT NULL, url TEXT NOT NULL,
                logo TEXT, group_title TEXT NOT NULL, tvg_id TEXT, tvg_name TEXT, content_type TEXT NOT NULL,
                http_headers TEXT, catchup TEXT, catchup_source TEXT, duration_seconds REAL,
                search_name TEXT NOT NULL DEFAULT ''
            )
            """,
            "CREATE TABLE favorite (playlist_id INTEGER NOT NULL, channel_url TEXT NOT NULL, added_at INTEGER NOT NULL, PRIMARY KEY (playlist_id, channel_url))",
        )
    }
}
