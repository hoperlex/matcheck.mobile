package com.example.matcheckmobile.domain.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разбор госномера, распознанного с фото машины (PlateParsing.kt).
 *
 * Номера синтетические: настоящие из боевой БД в репозиторий не кладём.
 *
 * Три регрессии, ради которых написана бо́льшая часть тестов:
 *
 * 1. Один номер приходит из ML Kit несколькими путями сразу. Без группировки кандидатов
 *    порог «в 1,3 раза крупнее» сравнил бы два вхождения одного номера и отверг верный.
 * 2. В бой уехал `М583МУ792` вместо `М583МУ799`: склейка соседних по списку элементов без
 *    проверки расстояния приварила к номеру постороннюю цифру.
 * 3. Строгое «подставлять только при совпадении обоих проходов» отсекало вместе с ошибками
 *    и годные прочтения — инспекторы сообщали, что распознавание срабатывает через раз.
 */
class PlateParsingTest {

    // --- фикстуры -----------------------------------------------------------

    private fun word(text: String, x: Int, width: Int, y: Int = 100, height: Int = 40) =
        OcrElement(text, OcrRect(x, y, x + width, y + height))

    private fun lineOf(vararg words: OcrElement): OcrLine {
        val bounds = words.map { it.bounds }.reduce(OcrRect::union)
        return OcrLine(words.joinToString(" ") { it.text }, bounds, words.toList())
    }

    private fun blockOf(vararg lines: OcrLine) = listOf(OcrBlock(lines.toList()))

    private fun plateOf(blocks: List<OcrBlock>): String? =
        pickPlate(buildCandidates(blocks))?.canonical

    private fun selected(text: String, weight: Int = 40, wide: Boolean = false): SelectedPlate =
        SelectedPlate(
            match = canonicalisePlate(text)!!,
            bounds = OcrRect(0, 0, 100, weight),
            weight = weight,
            wideGlue = wide,
        )

    // --- каталог форматов ---------------------------------------------------

    @Test
    fun `гост легковой в обеих длинах региона`() {
        assertEquals("О123ВС77", canonicalisePlate("O123BC77")?.canonical)
        assertEquals("О123ВС777", canonicalisePlate("O123BC777")?.canonical)
        assertEquals("ru_car", canonicalisePlate("O123BC77")?.formatId)
    }

    @Test
    fun `кириллица на входе не ломает гост`() {
        assertEquals("Х456УА199", canonicalisePlate("Х456УА199")?.canonical)
    }

    @Test
    fun `рф с цифрами впереди — прицепы и спецтехника`() {
        val m = canonicalisePlate("0029TC797")
        assertEquals("0029ТС797", m?.canonical)
        assertEquals("ru_digits_first", m?.formatId)
        assertTrue("формат российский, подставляем сами", m!!.autoFillable)
    }

    @Test
    fun `рф с буквами впереди`() {
        val m = canonicalisePlate("AB123456")
        assertEquals("ru_letters_first", m?.formatId)
        assertEquals("АВ123456", m?.canonical)
    }

    @Test
    fun `белорусский легковой — с дефисом и только подсказкой`() {
        val m = canonicalisePlate("4633КА6")
        assertEquals("4633КА-6", m?.canonical)
        assertEquals("by_1", m?.formatId)
        assertFalse("маска с цифрами впереди легко собирается из мусора", m!!.autoFillable)
    }

    @Test
    fun `белорусский грузовой — вторая наблюдаемая форма`() {
        val m = canonicalisePlate("АМ53515")
        assertEquals("АМ5351-5", m?.canonical)
        assertEquals("by_2", m?.formatId)
    }

    @Test
    fun `буква I в белорусском номере не превращается в единицу`() {
        // I стоит в общей карте путаницы как I -> 1, но на буквенной позиции
        // конвертируются только цифры.
        assertEquals("2971OI-4", canonicalisePlate("2971OI4")?.canonical)
    }

    @Test
    fun `казахстанский номер в обеих раскладках`() {
        // Кириллическая Л в таблице омоглифов отсутствует — раньше такой номер
        // разваливался на смесь раскладок.
        assertEquals("067АЛК04", canonicalisePlate("067АЛК04")?.canonical)
        assertEquals("067ALK04", canonicalisePlate("067ALK04")?.canonical)
        assertEquals("kz", canonicalisePlate("067АЛК04")?.formatId)
    }

    @Test
    fun `дефис и пробелы на входе не мешают`() {
        assertEquals("4633КА-6", canonicalisePlate("4633 КА-6")?.canonical)
        assertEquals("А777АА99", canonicalisePlate("A 777 AA 99 RUS")?.canonical)
    }

    @Test
    fun `мусор не превращается в номер`() {
        assertNull("рекламная надпись", canonicalisePlate("ГРУЗОПЕРЕВОЗКИ"))
        assertNull("телефон на борту", canonicalisePlate("8 800 555 35 35"))
        assertNull("слишком коротко", canonicalisePlate("A123BC"))
        assertNull("пусто", canonicalisePlate(""))
    }

    // --- починка по маске ---------------------------------------------------

    @Test
    fun `ноль на буквенной позиции чинится в букву`() {
        val m = canonicalisePlate("O123BC7O")
        assertEquals("О123ВС70", m?.canonical)
        assertEquals("одна правка: O в регионе — это ноль", 1, m?.corrections)
    }

    @Test
    fun `буква на цифровой позиции формата с цифрами впереди чинится в цифру`() {
        // O на первой позиции здесь обязана стать нулём, а не остаться буквой.
        val m = canonicalisePlate("O029TC797")
        assertEquals("ru_car", m?.formatId)
        assertEquals("точное совпадение бьёт исправленное", 0, m?.corrections)
    }

    // --- арбитраж форматов --------------------------------------------------

    @Test
    fun `строка подходит двум маскам — выигрывает вариант без исправлений`() {
        // 0123BC77 — это и ГОСТ (после починки 0 -> O), и формат с цифрами впереди
        // вообще без починки. Побеждает второй, независимо от порядка в каталоге.
        val m = canonicalisePlate("0123BC77")
        assertEquals("ru_digits_first", m?.formatId)
        assertEquals(0, m?.corrections)
        assertEquals("0123ВС77", m?.canonical)
        assertFalse(m!!.ambiguous)
    }

    @Test
    fun `равное число исправлений помечается как неоднозначность`() {
        val tie = arbitrate(
            listOf(
                FormatMatch("АААА", "ru_car", corrections = 1),
                FormatMatch("ББББ", "by_1", corrections = 1),
            ),
        )
        assertTrue("выбрать безопасно нельзя", tie!!.ambiguous)
        assertFalse("а значит и подставлять нельзя", tie.autoFillable)
    }

    @Test
    fun `арбитраж не зависит от порядка списка`() {
        val a = FormatMatch("X", "ru_car", corrections = 2)
        val b = FormatMatch("Y", "by_1", corrections = 0)
        assertEquals(arbitrate(listOf(a, b))?.formatId, arbitrate(listOf(b, a))?.formatId)
        assertEquals("by_1", arbitrate(listOf(a, b))?.formatId)
    }

    @Test
    fun `единственное совпадение неоднозначным не считается`() {
        assertFalse(arbitrate(listOf(FormatMatch("X", "ru_car", 1)))!!.ambiguous)
    }

    // --- уровни доверия -----------------------------------------------------

    @Test
    fun `совпавшие проходы автозаполняемого формата подставляются сами`() {
        val r = decideReading(selected("O123BC77"), selected("O123BC77"))
        assertEquals(PlateTier.AUTO, r?.tier)
        assertEquals(PlateReasonCode.AGREED, r?.reason)
        assertEquals("О123ВС77", r?.text)
    }

    @Test
    fun `расхождение проходов даёт подсказку по результату второго`() {
        val r = decideReading(selected("O123BC77"), selected("O123BC79"))
        assertEquals(PlateTier.SUGGESTION, r?.tier)
        assertEquals(PlateReasonCode.DISAGREED, r?.reason)
        assertEquals("второй проход читает в полном разрешении", "О123ВС79", r?.text)
    }

    @Test
    fun `пустой второй проход даёт подсказку по первому`() {
        val r = decideReading(selected("O123BC77"), null)
        assertEquals(PlateTier.SUGGESTION, r?.tier)
        assertEquals(PlateReasonCode.SECOND_EMPTY, r?.reason)
    }

    @Test
    fun `белорусский формат не подставляется даже при полном совпадении`() {
        val r = decideReading(selected("4633КА6"), selected("4633КА6"))
        assertEquals(PlateTier.SUGGESTION, r?.tier)
        assertEquals(PlateReasonCode.FORMAT_NOT_AUTO, r?.reason)
        assertEquals("4633КА-6", r?.text)
    }

    @Test
    fun `одинаковая ложная склейка обоих проходов не становится авто`() {
        // Модель и парсер у проходов одни и те же, поэтому согласие само по себе
        // ложную склейку не отсеивает.
        val r = decideReading(selected("O123BC77", wide = true), selected("O123BC77", wide = true))
        assertEquals(PlateTier.SUGGESTION, r?.tier)
        assertEquals(PlateReasonCode.WIDE_GLUE, r?.reason)
    }

    @Test
    fun `оба прохода пусты — тишина`() {
        assertNull(decideReading(null, null))
    }

    // --- геометрическая связность -------------------------------------------

    @Test
    fun `далёкая цифра не приваривается к номеру`() {
        // Регресс на М583МУ792: «79» — конец номера, «2» — посторонняя надпись
        // на другом конце борта.
        val blocks = blockOf(
            lineOf(word("A123BC79", x = 100, width = 180), word("2", x = 900, width = 20)),
        )
        val plate = plateOf(blocks)
        assertNotEquals("приваренная двойка", "А123ВС792", plate)
        assertEquals("А123ВС79", plate)
    }

    @Test
    fun `номер и регион соседними словами собираются вместе`() {
        val blocks = blockOf(
            lineOf(word("A123BC", x = 100, width = 140), word("77", x = 260, width = 40)),
        )
        assertEquals("А123ВС77", plateOf(blocks))
    }

    @Test
    fun `номер и регион в разных строках одного блока собираются вместе`() {
        val blocks = blockOf(
            lineOf(word("A123BC", x = 100, width = 140, y = 100, height = 40)),
            lineOf(word("77", x = 110, width = 40, y = 145, height = 35)),
        )
        assertEquals("А123ВС77", plateOf(blocks))
    }

    @Test
    fun `строки из разных концов кадра не склеиваются`() {
        val blocks = blockOf(
            lineOf(word("A123BC", x = 100, width = 140, y = 100, height = 40)),
            lineOf(word("77", x = 900, width = 40, y = 145, height = 35)),
        )
        assertNull(plateOf(blocks))
    }

    // --- дедупликация и неоднозначность по весу -----------------------------

    @Test
    fun `один номер, найденный и строкой и словом, не считается неоднозначностью`() {
        val blocks = blockOf(lineOf(word("A123BC77", x = 100, width = 200)))
        assertEquals("А123ВС77", plateOf(blocks))
    }

    @Test
    fun `два разных номера близкого размера — не подставляем ничего`() {
        val blocks = blockOf(
            lineOf(word("A123BC77", x = 100, width = 200, y = 100, height = 40)),
            lineOf(word("X456YA199", x = 100, width = 220, y = 400, height = 38)),
        )
        assertNull(plateOf(blocks))
    }

    @Test
    fun `заметно более крупный номер выигрывает у мелкого`() {
        val blocks = blockOf(
            lineOf(word("A123BC77", x = 100, width = 300, y = 100, height = 60)),
            lineOf(word("X456YA199", x = 100, width = 120, y = 400, height = 20)),
        )
        assertEquals("А123ВС77", plateOf(blocks))
    }

    @Test
    fun `нулевой вес не выигрывает у другого номера`() {
        val blocks = blockOf(
            lineOf(word("A123BC77", x = 0, width = 0, y = 0, height = 0)),
            lineOf(word("X456YA199", x = 0, width = 0, y = 0, height = 0)),
        )
        assertNull(plateOf(blocks))
    }

    // --- геометрия кропа ----------------------------------------------------

    @Test
    fun `рамка пересчитывается в координаты оригинала и расширяется`() {
        val crop = cropRect(
            bounds = OcrRect(100, 100, 200, 140),
            decodedWidth = 1000, decodedHeight = 750,
            originalWidth = 2000, originalHeight = 1500,
        )
        requireNotNull(crop)
        assertTrue(crop.left < 200)
        assertTrue(crop.right > 400)
    }

    @Test
    fun `кроп обрезается по границам оригинала`() {
        val crop = cropRect(
            bounds = OcrRect(0, 0, 1000, 750),
            decodedWidth = 1000, decodedHeight = 750,
            originalWidth = 1000, originalHeight = 750,
        )
        requireNotNull(crop)
        assertTrue(crop.left >= 0 && crop.top >= 0)
        assertTrue(crop.right <= 1000 && crop.bottom <= 750)
    }

    @Test
    fun `непропорциональное уменьшение считается раздельно по осям`() {
        val crop = cropRect(
            bounds = OcrRect(10, 10, 20, 20),
            decodedWidth = 100, decodedHeight = 100,
            originalWidth = 1000, originalHeight = 200,
            expandRatio = 0.0,
        )
        requireNotNull(crop)
        assertEquals(100, crop.left)
        assertEquals(20, crop.top)
        assertEquals(200, crop.right)
        assertEquals(40, crop.bottom)
    }

    @Test
    fun `битые габариты не роняют кроп`() {
        assertNull(cropRect(OcrRect(0, 0, 10, 10), 0, 0, 100, 100))
        assertNull(cropRect(OcrRect(0, 0, 10, 10), 100, 100, 0, 0))
    }

    // --- приоритет ручного ввода --------------------------------------------

    @Test
    fun `пустое нетронутое поле заполняется`() {
        assertEquals("А123ВС77", plateAfterOcr("", editedByUser = false, recognised = "А123ВС77"))
    }

    @Test
    fun `набранный руками номер не затирается`() {
        assertNull(plateAfterOcr("Х456УА199", editedByUser = true, recognised = "А123ВС77"))
    }

    @Test
    fun `очищенное вручную поле остаётся пустым`() {
        assertNull(plateAfterOcr("", editedByUser = true, recognised = "А123ВС77"))
    }

    @Test
    fun `второй результат не переписывает первый`() {
        assertNull(plateAfterOcr("А123ВС77", editedByUser = false, recognised = "Х456УА199"))
    }

    // --- гонки нескольких снимков и однократная телеметрия -------------------

    @Test
    fun `результат предыдущего снимка не перезаписывает свежий`() {
        // Два фото обрабатываются параллельно; первое закончилось позже второго.
        val stale = isStaleOcrResult(
            attemptId = "первое",
            latestAttemptId = "второе",
            photoPath = "/photos/1.jpg",
            photoPaths = listOf("/photos/1.jpg", "/photos/2.jpg"),
        )
        assertTrue(stale)
    }

    @Test
    fun `результат удалённого фото не применяется`() {
        val stale = isStaleOcrResult(
            attemptId = "последнее",
            latestAttemptId = "последнее",
            photoPath = "/photos/1.jpg",
            photoPaths = emptyList(),
        )
        assertTrue("кадр уже убрали с формы", stale)
    }

    @Test
    fun `результат последнего живого снимка применяется`() {
        val stale = isStaleOcrResult(
            attemptId = "последнее",
            latestAttemptId = "последнее",
            photoPath = "/photos/2.jpg",
            photoPaths = listOf("/photos/1.jpg", "/photos/2.jpg"),
        )
        assertFalse(stale)
    }

    @Test
    fun `правка распознанного пишется один раз, а не на каждый символ`() {
        assertTrue(shouldReportPlateEdit(alreadyReported = false, origin = PlateOrigin.OCR_AUTO))
        assertFalse(shouldReportPlateEdit(alreadyReported = true, origin = PlateOrigin.OCR_AUTO))
    }

    @Test
    fun `правка вручную набранного номера ошибкой распознавания не считается`() {
        assertFalse(shouldReportPlateEdit(alreadyReported = false, origin = PlateOrigin.MANUAL))
    }

    @Test
    fun `правка принятой подсказки тоже считается ошибкой распознавания`() {
        assertTrue(shouldReportPlateEdit(alreadyReported = false, origin = PlateOrigin.OCR_SUGGESTED))
    }

    // --- код страны на кадре ------------------------------------------------

    @Test
    fun `белорусский номер с префиксом BY не становится российским прицепом`() {
        // Коллизия, ради которой писался весь этот блок: BY2971OI4 ложится на
        // ru_letters_first — «B» и «Y» есть в наборе омоглифов, — и после починки
        // O -> 0, I -> 1 дал бы ВУ2971014 с автозаполнением. Тело без префикса
        // ложится на by_1 вообще без правок и побеждает арбитраж.
        val m = canonicalisePlate("BY2971OI4")
        assertEquals("by_1", m?.formatId)
        assertEquals("2971OI-4", m?.canonical)
        assertTrue("префикс снят — значит номер прочитан не полностью", m!!.countryAffixStripped)
        assertFalse("и подставлять его нельзя", m.autoFillable)
    }

    @Test
    fun `снятый префикс даёт подсказку даже при совпадении проходов`() {
        val r = decideReading(selected("BY2971OI4"), selected("BY2971OI4"))
        assertEquals(PlateTier.SUGGESTION, r?.tier)
        assertEquals(PlateReasonCode.COUNTRY_AFFIX, r?.reason)
        assertEquals("2971OI-4", r?.text)
    }

    @Test
    fun `причины различимы — префикс страны это не широкая склейка`() {
        val prefix = decideReading(selected("BY2971OI4"), selected("BY2971OI4"))
        val wide = decideReading(selected("O123BC77", wide = true), selected("O123BC77", wide = true))
        assertNotEquals(
            "иначе по журналу не отличить одну причину от другой",
            prefix?.reason,
            wide?.reason,
        )
    }

    @Test
    fun `российская серия ВУ не страдает от разбора префикса`() {
        // ВУ — допустимая серия ru_letters_first, и латинское BY выглядит так же.
        // Строка обязана остаться российским номером, а не превратиться в обрывок.
        val m = canonicalisePlate("ВУ123456")
        assertEquals("ru_letters_first", m?.formatId)
        assertEquals("ВУ123456", m?.canonical)
        assertFalse("но этот формат теперь только подсказкой", m!!.autoFillable)
    }

    @Test
    fun `хвостовой RUS не понижает обычный российский номер до подсказки`() {
        // Суффикс стоит справа от знака и ничего не значит: демотировать из-за него
        // нельзя, иначе половина потока уедет в подсказки.
        val m = canonicalisePlate("A 777 AA 99 RUS")
        assertEquals("ru_car", m?.formatId)
        assertEquals("А777АА99", m?.canonical)
        assertFalse("суффикс — не спорный префикс", m!!.countryAffixStripped)
        assertTrue(m.autoFillable)
        assertEquals(PlateTier.AUTO, decideReading(selected("A 777 AA 99 RUS"), selected("A 777 AA 99 RUS"))?.tier)
    }

    @Test
    fun `префикс страны ограничивает выбор её форматами`() {
        // KZ + тело казахстанской формы разбирается казахстанским форматом.
        assertEquals("kz", canonicalisePlate("KZ067ALK04")?.formatId)
        // А тело без префикса на казахстанские маски не попадает.
        assertEquals("ru_car", canonicalisePlate("O123BC77")?.formatId)
    }

    @Test
    fun `украинский префикс не снимается — форматов нет`() {
        // Снятие UA породило бы обрывок, способный лечь на чужую маску.
        val m = canonicalisePlate("UA1234AB5")
        assertNull("ни один формат такую строку принимать не должен", m)
    }

    @Test
    fun `варианты разбора строятся предсказуемо`() {
        val plain = candidateVariants("O123BC77")
        assertEquals(1, plain.size)
        assertNull(plain[0].country)

        val withPrefix = candidateVariants("BY2971OI4")
        assertEquals("исходная строка и тело без префикса", 2, withPrefix.size)
        assertEquals("BY2971OI4", withPrefix[0].body)
        assertEquals("2971OI4", withPrefix[1].body)
        assertEquals("BY", withPrefix[1].country)
    }

    // --- фильтр региона -----------------------------------------------------
    //
    // 1.0.38 записал в прод четыре номера с испорченной последней цифрой ТРЁХЗНАЧНОГО
    // региона (797→792, 799→792, 977→972). Номера здесь синтетические: настоящие полные
    // номера, идентификаторы приёмок и почты инспекторов в код не переносим.

    private fun tierOf(text: String): PlateTier? =
        decideReading(selected(text), selected(text))?.tier

    @Test
    fun `несуществующий трёхзначный регион не автозаполняется`() {
        assertEquals(PlateTier.SUGGESTION, tierOf("A111AA792"))
        assertEquals(PlateTier.SUGGESTION, tierOf("A111AA972"))
    }

    @Test
    fun `причина у несуществующего региона своя`() {
        val r = decideReading(selected("A111AA792"), selected("A111AA792"))
        assertEquals(PlateReasonCode.RU_REGION_NOT_AUTO, r?.reason)
        assertNotEquals(
            "иначе в телеметрии сольётся с «формат не автозаполняемый»",
            PlateReasonCode.FORMAT_NOT_AUTO,
            r?.reason,
        )
    }

    @Test
    fun `верные регионы с тех же фото автозаполняются`() {
        assertEquals(PlateTier.AUTO, tierOf("A111AA797"))
        assertEquals(PlateTier.AUTO, tierOf("A111AA799"))
        assertEquals(PlateTier.AUTO, tierOf("A111AA977"))
    }

    @Test
    fun `легитимные редкие двузначные регионы фильтр не трогает`() {
        // Северная Осетия, Калмыкия, Еврейская АО — встречались в той же выборке,
        // что и четыре ошибки, и все три настоящие.
        assertEquals(PlateTier.AUTO, tierOf("A111AA15"))
        assertEquals(PlateTier.AUTO, tierOf("A111AA08"))
        assertEquals(PlateTier.AUTO, tierOf("A111AA79"))
    }

    @Test
    fun `нулевой регион не автозаполняется`() {
        // Двузначные принимаются как 01-99, нуля среди них нет.
        assertEquals(PlateTier.SUGGESTION, tierOf("A111AA00"))
    }

    @Test
    fun `фильтр не трогает нероссийские форматы`() {
        // У белорусского регион устроен иначе, и он и так только подсказкой.
        assertTrue(regionAllowsAutoFill("2971OI-4", "by_1"))
        assertTrue(regionAllowsAutoFill("067АЛК04", "kz"))
    }

    @Test
    fun `Z на цифровой позиции больше не превращается в двойку`() {
        // Единственный путь, которым разбор мог синтезировать ту самую двойку.
        assertNull(canonicalisePlate("A111AA79Z"))
    }

    @Test
    fun `остальные правки путаницы работают как раньше`() {
        assertEquals("О123ВС70", canonicalisePlate("O123BC7O")?.canonical)
        assertEquals("А111АА15", canonicalisePlate("A111AA1S")?.canonical)
    }
}
