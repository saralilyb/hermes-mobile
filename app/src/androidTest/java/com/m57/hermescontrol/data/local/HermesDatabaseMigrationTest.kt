package com.m57.hermescontrol.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HermesDatabaseMigrationTest {
    init {
        System.loadLibrary("sqlcipher")
    }

    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            HermesDatabase::class.java,
            emptyList(),
            SupportOpenHelperFactory(TEST_PASSWORD.copyOf()),
        )

    @Test
    fun migrate4To5PreservesMessagesAndCanonicalDefaults() {
        helper.createDatabase(TEST_DATABASE, 4).apply {
            execSQL(
                """
                INSERT INTO chat_messages (
                    id, session_id, role, content, reasoning_text, timestamp,
                    tool_name, tool_status, is_streaming
                ) VALUES (
                    'message-1', 'session-1', 'assistant', 'answer', 'reasoning',
                    1234, 'tool', 'complete', 0
                )
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO chat_messages (
                    id, session_id, role, content, reasoning_text, timestamp,
                    tool_name, tool_status, is_streaming
                ) VALUES (
                    'message-2', 'session-1', 'user', 'question', '',
                    1235, NULL, NULL, 1
                )
                """.trimIndent(),
            )
            close()
        }

        val migrated =
            helper.runMigrationsAndValidate(
                TEST_DATABASE,
                5,
                true,
                HermesDatabase.MIGRATION_4_5,
            )

        migrated.query(
            """
            SELECT session_id, role, content, reasoning_text, timestamp,
                   tool_name, tool_status, is_streaming, attachments_json
            FROM chat_messages
            WHERE id = 'message-1'
            """.trimIndent(),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("session-1", cursor.getString(0))
            assertEquals("assistant", cursor.getString(1))
            assertEquals("answer", cursor.getString(2))
            assertEquals("reasoning", cursor.getString(3))
            assertEquals(1234L, cursor.getLong(4))
            assertEquals("tool", cursor.getString(5))
            assertEquals("complete", cursor.getString(6))
            assertEquals(0, cursor.getInt(7))
            assertEquals("[]", cursor.getString(8))
        }

        migrated.query(
            """
            SELECT role, content, reasoning_text, timestamp,
                   tool_name, tool_status, is_streaming, attachments_json
            FROM chat_messages
            WHERE id = 'message-2'
            """.trimIndent(),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("user", cursor.getString(0))
            assertEquals("question", cursor.getString(1))
            assertEquals("", cursor.getString(2))
            assertEquals(1235L, cursor.getLong(3))
            assertTrue(cursor.isNull(4))
            assertTrue(cursor.isNull(5))
            assertEquals(1, cursor.getInt(6))
            assertEquals("[]", cursor.getString(7))
        }

        migrated.query("PRAGMA table_info(`chat_messages`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val defaultIndex = cursor.getColumnIndexOrThrow("dflt_value")
            val defaults = mutableMapOf<String, String?>()
            while (cursor.moveToNext()) {
                defaults[cursor.getString(nameIndex)] = cursor.getString(defaultIndex)
            }
            assertEquals("''", defaults["reasoning_text"])
            assertEquals("'[]'", defaults["attachments_json"])
        }

        migrated.close()
    }

    @Test
    fun migrate5To6PreservesAttachmentsAndAddsNullableDisplayKind() {
        helper.createDatabase(TEST_DATABASE_FROM_5, 5).apply {
            execSQL(
                """
                INSERT INTO chat_messages (
                    id, session_id, role, content, reasoning_text, timestamp,
                    tool_name, tool_status, is_streaming, attachments_json
                ) VALUES (
                    'marker', 'session', 'USER', 'notice', 'reasoning', 42,
                    'tool', 'COMPLETED', 0, '[{"name":"kept"}]'
                )
                """.trimIndent(),
            )
            close()
        }

        val migrated =
            helper.runMigrationsAndValidate(
                TEST_DATABASE_FROM_5,
                6,
                true,
                HermesDatabase.MIGRATION_5_6,
            )

        migrated.query(
            "SELECT attachments_json, reasoning_text, tool_name, tool_status, " +
                "tool_call_id, display_kind FROM chat_messages",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("[{\"name\":\"kept\"}]", cursor.getString(0))
            assertEquals("reasoning", cursor.getString(1))
            assertEquals("tool", cursor.getString(2))
            assertEquals("COMPLETED", cursor.getString(3))
            assertTrue(cursor.isNull(4))
            assertTrue(cursor.isNull(5))
        }
        migrated.close()
    }

    @Test
    fun migrate2To5PreservesMessagesAndAddsDefaults() {
        helper.createDatabase(TEST_DATABASE_FROM_2, 2).apply {
            execSQL(
                """
                INSERT INTO chat_messages (
                    id, session_id, role, content, timestamp,
                    tool_name, tool_status, is_streaming
                ) VALUES (
                    'legacy-message', 'legacy-session', 'assistant',
                    'legacy-answer', 42, NULL, NULL, 0
                )
                """.trimIndent(),
            )
            close()
        }

        val migrated =
            helper.runMigrationsAndValidate(
                TEST_DATABASE_FROM_2,
                5,
                true,
                HermesDatabase.MIGRATION_2_3,
                HermesDatabase.MIGRATION_3_4,
                HermesDatabase.MIGRATION_4_5,
            )

        migrated.query(
            """
            SELECT session_id, content, reasoning_text, attachments_json
            FROM chat_messages
            WHERE id = 'legacy-message'
            """.trimIndent(),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("legacy-session", cursor.getString(0))
            assertEquals("legacy-answer", cursor.getString(1))
            assertEquals("", cursor.getString(2))
            assertEquals("[]", cursor.getString(3))
        }

        migrated.close()
    }

    @Test
    fun productionOpenMigratesEncryptedSchema4Database() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val productionHelper =
            MigrationTestHelper(
                instrumentation,
                HermesDatabase::class.java,
                emptyList(),
                SupportOpenHelperFactory(AuthManager.getDatabasePassword()),
            )
        var database: HermesDatabase? = null

        HermesDatabase.setForTest(null)
        context.deleteDatabase(PRODUCTION_DATABASE)
        try {
            productionHelper.createDatabase(PRODUCTION_DATABASE, 4).apply {
                execSQL(
                    """
                    INSERT INTO chat_messages (
                        id, session_id, role, content, reasoning_text, timestamp,
                        tool_name, tool_status, is_streaming
                    ) VALUES (
                        'startup-message', 'startup-session', 'assistant',
                        'survives startup', 'startup reasoning', 2026,
                        NULL, NULL, 0
                    )
                    """.trimIndent(),
                )
                close()
            }

            database = HermesDatabase.get(context)
            database.openHelper.writableDatabase.query(
                """
                SELECT content, reasoning_text, attachments_json
                FROM chat_messages
                WHERE id = 'startup-message'
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("survives startup", cursor.getString(0))
                assertEquals("startup reasoning", cursor.getString(1))
                assertEquals("[]", cursor.getString(2))
            }
        } finally {
            database?.close()
            HermesDatabase.setForTest(null)
            context.deleteDatabase(PRODUCTION_DATABASE)
        }
    }

    private companion object {
        const val PRODUCTION_DATABASE = "hermes_control.db"
        const val TEST_DATABASE = "hermes-migration-test"
        const val TEST_DATABASE_FROM_2 = "hermes-migration-test-from-2"
        const val TEST_DATABASE_FROM_5 = "hermes-migration-test-from-5"
        val TEST_PASSWORD = "room-migration-test-only".encodeToByteArray()
    }
}
