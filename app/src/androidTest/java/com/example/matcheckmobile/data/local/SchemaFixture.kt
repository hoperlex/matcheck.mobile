package com.example.matcheckmobile.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.json.JSONObject

/**
 * Замена `MigrationTestHelper` для тестов миграций на устройстве.
 *
 * Почему не он: `room-migration 2.8.2` собран под kotlinx-serialization 1.8,
 * а приложение — на 1.7.3, и AGP прижимает тестовый classpath к версиям
 * приложения. Helper падает `AbstractMethodError` ещё при чтении схемы и до
 * проверки данных не доходит.
 *
 * Что взамен:
 *  - [createDatabase] собирает базу старой версии из экспортированной схемы
 *    Room (`schemas/…/<N>.json` в assets теста) — тот же DDL, что был у
 *    планшета с этой версией, — и разбирает JSON встроенным `org.json`;
 *  - [openWithRoom] открывает файл так же, как `MatcheckDatabase.get` у
 *    инспектора: тот же список миграций, штатная проверка схемы Room. Только
 *    без `fallbackToDestructiveMigration` — ошибка миграции роняет тест, а не
 *    стирает базу молча.
 */
internal class SchemaFixture(
    /** Контекст приложения: здесь живут файлы баз. */
    private val target: Context,
    /** Контекст тестового APK: здесь лежат схемы. */
    private val assets: Context,
) {

    /** Пустая база версии [version] по экспортированной схеме. */
    fun createDatabase(name: String, version: Int): SupportSQLiteDatabase {
        target.deleteDatabase(name)
        val schema = JSONObject(
            assets.assets.open("$SCHEMA_DIR/$version.json").bufferedReader().use { it.readText() },
        ).getJSONObject("database")
        check(schema.getInt("version") == version) { "в $version.json другая версия схемы" }

        val callback = object : SupportSQLiteOpenHelper.Callback(version) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    val table = entity.getString("tableName")
                    db.execSQL(entity.getString("createSql").replace(TABLE_NAME, table))
                    val indices = entity.optJSONArray("indices") ?: continue
                    for (j in 0 until indices.length()) {
                        db.execSQL(indices.getJSONObject(j).getString("createSql").replace(TABLE_NAME, table))
                    }
                }
                val views = schema.optJSONArray("views")
                if (views != null) {
                    for (i in 0 until views.length()) {
                        val view = views.getJSONObject(i)
                        db.execSQL(view.getString("createSql").replace(VIEW_NAME, view.getString("viewName")))
                    }
                }
                // room_master_table с хешем этой версии — как у настоящего планшета.
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                error("база $name создаётся с нуля, апгрейд не ожидается")
        }
        val config = SupportSQLiteOpenHelper.Configuration.builder(target)
            .name(name)
            .callback(callback)
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
    }

    /**
     * Открыть файл путём приложения: миграции до текущей версии и проверка
     * схемы Room происходят здесь, на первом `writableDatabase`.
     */
    fun openWithRoom(name: String): MatcheckDatabase =
        Room.databaseBuilder(target, MatcheckDatabase::class.java, name)
            .addMigrations(*MatcheckDatabase.ALL_MIGRATIONS)
            .build()

    private companion object {
        const val SCHEMA_DIR = "com.example.matcheckmobile.data.local.MatcheckDatabase"
        const val TABLE_NAME = "\${TABLE_NAME}"
        const val VIEW_NAME = "\${VIEW_NAME}"
    }
}
