package com.example.matcheckmobile.data.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Миграция 26→27 против экспортированной схемы Room (schemas/…/27.json).
 *
 * Зачем на JVM: у базы fallbackToDestructiveMigration, и расхождение миграции
 * со схемой стирает на планшете ВСЁ — очередь, черновики, фото. Instrumented-
 * тест миграций требует устройства; эта сверка ловит главное без него.
 */
class MigrationSchemaTest {

    private fun entities(version: Int): Map<String, JsonObject> {
        val file = File("schemas/com.example.matcheckmobile.data.local.MatcheckDatabase/$version.json")
        val root = Json.parseToJsonElement(file.readText()).jsonObject
        return root["database"]!!.jsonObject["entities"]!!.jsonArray
            .map { it.jsonObject }
            .associateBy { it["tableName"]!!.jsonPrimitive.content }
    }

    @Test
    fun `DDL таблицы карантина дословно совпадает со схемой Room`() {
        val sql = entities(27).getValue("quarantined_operations")["createSql"]!!.jsonPrimitive.content
            .replace("\${TABLE_NAME}", "quarantined_operations")

        assertEquals(sql, MatcheckDatabase.QUARANTINED_OPERATIONS_DDL)
    }

    @Test
    fun `черновики 1 Этапа — две новые колонки NOT NULL и неуникальные индексы`() {
        val before = entities(26).getValue("stage1_drafts")
        val after = entities(27).getValue("stage1_drafts")
        fun columns(e: JsonObject) = e["fields"]!!.jsonArray.map { it.jsonObject["columnName"]!!.jsonPrimitive.content }

        // Множества, а не списки: Room сверяет колонки по имени, а ALTER TABLE
        // ADD COLUMN физически дописывает их в конец таблицы.
        assertEquals(
            (columns(before) + listOf("mergedVariantsJson", "parkedMaterialsJson")).toSet(),
            columns(after).toSet(),
        )
        after["fields"]!!.jsonArray.map { it.jsonObject }
            .filter { it["columnName"]!!.jsonPrimitive.content in setOf("mergedVariantsJson", "parkedMaterialsJson") }
            .forEach { assertTrue(it["notNull"]!!.jsonPrimitive.boolean) }

        val indices = after["indices"]!!.jsonArray.map { it.jsonObject }
            .associate { it["name"]!!.jsonPrimitive.content to it["unique"]!!.jsonPrimitive.boolean }
        assertFalse(indices.getValue("index_stage1_drafts_updId"))
        assertFalse(indices.getValue("index_stage1_drafts_groupId"))
        // У отгрузок ничего не меняли — там уникальность прежняя.
        val shipment = entities(27).getValue("shipment_stage1_drafts")["indices"]!!.jsonArray.map { it.jsonObject }
        assertTrue(shipment.first { it["name"]!!.jsonPrimitive.content == "index_shipment_stage1_drafts_groupId" }["unique"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `кроме карантина и черновиков 1 Этапа схема не изменилась`() {
        val before = entities(26)
        val after = entities(27)

        assertEquals(before.keys + "quarantined_operations", after.keys)
        for (table in before.keys - "stage1_drafts") {
            assertEquals(table, before.getValue(table)["createSql"], after.getValue(table)["createSql"])
            assertEquals(table, before.getValue(table)["indices"], after.getValue(table)["indices"])
        }
    }
}
