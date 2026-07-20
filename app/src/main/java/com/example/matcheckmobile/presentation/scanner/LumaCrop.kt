package com.example.matcheckmobile.presentation.scanner

/**
 * Вырезает плотно упакованный прямоугольник из Y-плоскости кадра.
 *
 * Y-плоскость `YUV_420_888` — это уже градации серого, но она **не обязана быть
 * плотно упакованной**: строка занимает `rowStride` байт, который может быть
 * больше ширины (выравнивание буфера). Читать её как сплошной массив — классика
 * «картинка поехала косой лесенкой».
 *
 * Плюс работаем только по `cropRect`: в портрете полный буфер содержит полосы
 * слева и справа, которых оператор не видит из-за FILL_CENTER, и найденный там
 * контур стал бы рамкой-призраком.
 *
 * Функция чистая — никаких Android-типов, поэтому проверяется JVM-тестами.
 */
fun cropLuma(
    plane: ByteArray,
    rowStride: Int,
    pixelStride: Int,
    cropLeft: Int,
    cropTop: Int,
    cropWidth: Int,
    cropHeight: Int,
): ByteArray? {
    if (cropWidth <= 0 || cropHeight <= 0) return null
    if (rowStride <= 0 || pixelStride <= 0) return null
    if (cropLeft < 0 || cropTop < 0) return null

    val out = ByteArray(cropWidth * cropHeight)
    for (row in 0 until cropHeight) {
        val srcRowStart = (cropTop + row) * rowStride + cropLeft * pixelStride
        val dstRowStart = row * cropWidth
        for (col in 0 until cropWidth) {
            val src = srcRowStart + col * pixelStride
            // Обрезанный/короткий буфер лучше отвергнуть целиком, чем молча
            // отдать половину кадра и «найти» на ней несуществующий документ.
            if (src >= plane.size) return null
            out[dstRowStart + col] = plane[src]
        }
    }
    return out
}
