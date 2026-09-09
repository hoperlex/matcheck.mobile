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
    fun `две буквы и сплошные цифры номером больше не считаются`() {
        // Формат прицепа с буквами впереди убран из каталога: под него подходил любой
        // ряд из восьми цифр, стоило первым двум пройти через DIGIT_AS_LETTER.
        assertNull(canonicalisePlate("AB123456"))
        assertNull(canonicalisePlate("AB1234567"))
    }

    @Test
    fun `дата со штампа не превращается в номер`() {
        // Реальный случай: кадр со штампом «08.09.2026» давал «ОВ092026» (0 -> О, 8 -> В)
        // и, будучи набран крупнее знака, отбирал победу у настоящего номера в pickPlate.
        assertNull(canonicalisePlate("08092026"))
        assertNull(canonicalisePlate("08.09.2026"))
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
        // Коллизия, ради которой писался весь этот блок: пока в каталоге был прицепной
        // формат «две буквы + цифры», BY2971OI4 ложился на него целиком и после починки
        // O -> 0, I -> 1 давал ВУ2971014. Формат убран, но разбор префикса остаётся
        // единственным, что делает эту строку читаемой: тело ложится на by_1 без правок.
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
    fun `строка вида ВУ123456 не читается вовсе — осознанный размен`() {
        // Раньше здесь был российский прицепной номер. Вместе с форматом ушла и эта
        // строка: цена — два номера из 10 156 за всю историю, оба введённые руками.
        // Взамен ни один восьмизначный ряд на кадре больше не выдаёт себя за номер.
        assertNull(canonicalisePlate("ВУ123456"))
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
        assertEquals(
            PlateTier.AUTO,
            decideReading(selected("A 777 AA 99 RUS"), selected("A 777 AA 99 RUS"))?.tier,
        )
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

    // --- обрезанный регион и ширина склейки ---------------------------------
    //
    // Жалоба с объектов 09.09.2026: «в госномерах часто вбивается не тот регион, например
    // вместо 977 может вбить 02». Оба дефекта ниже дают ровно эту картину — серия верная,
    // регион короче либо чужой, — и оба доходили до автозаполнения молча.

    @Test
    fun `потерянная цифра региона не побеждает полное чтение`() {
        // ML Kit разбил регион на «97» и «7». Окно из двух даёт «О123ВС97», окно из трёх и
        // строка целиком — «О123ВС977»; оба ложатся на ru_car без правок, поэтому раньше
        // спорили по весу и порог 1,3 отвергал обоих.
        val plate = plateOf(
            blockOf(
                lineOf(
                    word("O123BC", x = 0, width = 120),
                    word("97", x = 125, width = 40),
                    word("7", x = 168, width = 18, height = 30),
                ),
            ),
        )
        assertEquals("О123ВС977", plate)
    }

    @Test
    fun `обрезанный кандидат отбрасывается, когда добавленный символ того же кегля`() {
        // Вес окна — минимальная высота символа в нём. Если добавленный символ не мельче,
        // вес длинного чтения не падает: это потерянная цифра знака, а не чужая надпись.
        val picked = pickPlate(
            listOf(
                PlateCandidate("O123BC97", OcrRect(0, 0, 100, 40), weight = 40),
                PlateCandidate("O123BC977", OcrRect(0, 0, 110, 40), weight = 40),
            ),
        )
        assertEquals("О123ВС977", picked?.canonical)
    }

    @Test
    fun `мелкий добавленный символ обрезкой не считается`() {
        // Тот же вход, но добавленный глиф вчетверо ниже номера. Раньше правило срабатывало
        // и здесь, отдавая выдуманный регион в AUTO; теперь оба кандидата спорят по весу.
        val picked = pickPlate(
            listOf(
                PlateCandidate("O123BC97", OcrRect(0, 0, 100, 40), weight = 40),
                PlateCandidate("O123BC977", OcrRect(0, 0, 110, 10), weight = 10),
            ),
        )
        assertEquals("О123ВС97", picked?.canonical)
    }

    @Test
    fun `настоящий двузначный регион не страдает`() {
        // Конкурента длиннее нет — кандидат обязан остаться.
        val picked = pickPlate(listOf(PlateCandidate("O123BC97", OcrRect(0, 0, 100, 40), 40)))
        assertEquals("О123ВС97", picked?.canonical)
    }

    @Test
    fun `правило обрезки не трогает разные номера`() {
        // Два ТС в кадре: ни один номер не является началом другого, спор решает вес —
        // ровно как раньше. Иначе защита от обрезки съела бы соседнюю машину.
        val picked = pickPlate(
            listOf(
                PlateCandidate("O123BC97", OcrRect(0, 0, 100, 60), weight = 60),
                PlateCandidate("X456YA199", OcrRect(0, 0, 110, 30), weight = 30),
            ),
        )
        assertEquals("О123ВС97", picked?.canonical)
    }

    @Test
    fun `посторонний мелкий глиф не приваривается к настоящему двузначному региону`() {
        // Дыра, найденная аудитом: правило обрезки, применённое безусловно, вычёркивало
        // верное «О123ВС97» из-за фабрикованного «О123ВС978», тот оставался единственным
        // кандидатом, порог 1,3 к нему не применялся — и выдуманный регион уходил в AUTO,
        // то есть писался молча. Список регионов тут бессилен: каждый реальный
        // трёхзначный код — это реальный двузначный плюс цифра.
        val picked = pickPlate(
            listOf(
                PlateCandidate("O123BC97", OcrRect(0, 0, 100, 60), weight = 60),
                PlateCandidate("O123BC97B", OcrRect(0, 0, 110, 30), weight = 30),
            ),
        )
        assertEquals("настоящий номер обязан выиграть у приварки", "О123ВС97", picked?.canonical)
    }

    @Test
    fun `спор с приваркой сопоставимого веса не даёт автозаполнения`() {
        // Когда посторонний глиф того же кегля, отличить его от потерянной цифры нечем.
        // Тогда работает обычный порог: победителя нет, номер не подставляется.
        val picked = pickPlate(
            listOf(
                PlateCandidate("O123BC97", OcrRect(0, 0, 100, 40), weight = 40),
                PlateCandidate("X456YA199", OcrRect(0, 0, 110, 36), weight = 36),
            ),
        )
        assertNull("две разные машины сопоставимого размера — выбирать нельзя", picked)
    }

    // --- сквозная проверка: автозаполнение реально происходит -----------------
    //
    // Аудит нашёл пробел: все проверки уровня шли через фикстуру selected(), которая
    // строит SelectedPlate напрямую. Цепочку buildCandidates -> pickPlate -> decideReading
    // не гонял ни один тест, поэтому регрессия «авто пропало совсем» осталась бы незамечена.

    private fun tierFromFrame(vararg words: OcrElement): PlateTier? {
        val picked = pickPlate(buildCandidates(blockOf(lineOf(*words))))!!
        return decideReading(picked, picked)?.tier
    }

    @Test
    fun `обычный номер из четырёх элементов всё ещё автозаполняется`() {
        // Ровно так ML Kit чаще всего и отдаёт знак. Порог по числу элементов забирал бы
        // такой номер в подсказку — поэтому от этого признака отказались.
        assertEquals(
            PlateTier.AUTO,
            tierFromFrame(
                word("O", x = 0, width = 20),
                word("123", x = 25, width = 60),
                word("BC", x = 90, width = 45),
                word("797", x = 140, width = 70),
            ),
        )
    }

    @Test
    fun `номер одним словом автозаполняется`() {
        assertEquals(PlateTier.AUTO, tierFromFrame(word("O123BC797", x = 0, width = 200)))
    }

    @Test
    fun `номер с хвостом RUS автозаполняется`() {
        assertEquals(
            PlateTier.AUTO,
            tierFromFrame(
                word("O123BC", x = 0, width = 120),
                word("797", x = 125, width = 70),
                word("RUS", x = 200, width = 45),
            ),
        )
    }

}
