package com.example.matcheckmobile.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Незавершённая 1 Этап-приёмка: ввод и фото пользователя, ещё не отправленные
 * в DeliveryRepository.upsert. Сохраняется автоматически при наличии хотя бы
 * одного фото; удаляется при finalize или когда пользователь стирает все фото.
 *
 * `updId == null` — пустая приёмка (создаётся через «Создать приёмку» внизу
 * экрана 1 Этап). У такого draft нет соответствующей карточки УПД в списке,
 * поэтому отображаем его как псевдо-карточку в группе «Созданы вручную»
 * в самом конце списка (см. PartyGrouping.kt).
 *
 * UNIQUE индекс по updId гарантирует один draft на УПД (NULL не считается
 * уникальным в SQLite — пустых drafts может быть несколько). То же для groupId:
 * одна машина — один черновик.
 */
@Entity(
    tableName = "stage1_drafts",
    indices = [
        Index(value = ["updId"], unique = true),
        Index(value = ["groupId"], unique = true),
        Index(value = ["updatedAt"]),
    ],
)
data class Stage1DraftEntity(
    @PrimaryKey val localDraftId: String,
    /**
     * Якорный документ. Для группового черновика — первый документ машины в
     * порядке sortGroupDocs; вся логика формы «документ есть / документа нет»
     * продолжает опираться на него.
     */
    val updId: String?,
    val documentPhotoPathsJson: String,
    val cargoPhotoPathsJson: String,
    val vehicleTypeCode: String?,
    val materialsJson: String,
    val commentText: String,
    val licensePlate: String,
    val manualUpdText: String,
    /**
     * Транзит — чекбокс инспектора на 1 этапе. Сохраняется в draft, чтобы
     * пережить свернуть/открыть. На finalize отправляется в server.
     * Default false (миграция Room 20→21).
     */
    val inTransit: Boolean = false,
    /**
     * ОС — чекбокс «основные средства» рядом с Транзитом. Сохраняется в
     * draft, чтобы пережить свернуть/открыть. На finalize отправляется
     * в server. Default false (миграция Room 21→22).
     */
    val isAssets: Boolean = false,
    /**
     * «Машина» — id корневого пакета загрузки (RemoteSourceDocumentEntity.groupId).
     * null у черновиков по одиночному документу и у пустых приёмок, а также у
     * всех черновиков, начатых до этой миграции: такие форма подхватывает по
     * updId и дозаполняет groupId при первом же сохранении.
     */
    val groupId: String? = null,
    /**
     * Состав машины на момент, когда в форму были загружены позиции: JSON-массив
     * id документов. Вместе с [loadedGroupRevision] это снимок, с которым
     * finalize сверяет актуальное состояние — привязать к приёмке документ,
     * позиций которого инспектор не видел, нельзя.
     *
     * Пустой массив у черновиков без группы.
     */
    val loadedDocIdsJson: String = "[]",
    /** Версия состава группы на момент загрузки формы. См. [loadedDocIdsJson]. */
    val loadedGroupRevision: Int? = null,
    /** Момент создания draft = когда было добавлено первое фото («Начато»). */
    val createdAt: Long,
    val updatedAt: Long,
)
