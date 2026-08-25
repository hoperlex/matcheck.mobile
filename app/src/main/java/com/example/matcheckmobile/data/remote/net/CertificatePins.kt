package com.example.matcheckmobile.data.remote.net

import okhttp3.CertificatePinner

/**
 * Пины TLS для боевого хоста.
 *
 * Вынесено из [NetworkFactory] отдельным объектом по той же причине, что SyncPageWalk:
 * правило одно, ошибиться в нём дорого, а проверить его внутри фабрики нечем — она
 * тянет за собой TokenStorage, Retrofit и Sentry разом.
 *
 * **Пиним только корни.** Промежуточные — расходник: Let's Encrypt их чередует, и какой
 * подпишет очередное автопродление, заранее неизвестно. Ровно на этом обожглись
 * 21.08.2026: пин стоял на промежуточном E7, LE перевыпустил сертификат по иерархии
 * поколения Y (`mat.su10.ru → YE2 → Root YE → X2`), E7 из цепочки исчез — и вся защита
 * повисла на единственном совпадении X2. Да и то лишь потому, что Root YE пока
 * кросс-подписан X2, а в trust store устройств самого Root YE ещё нет: как только Android
 * его доставит, путь оборвётся на Root YE, X2 в проверенную цепочку не войдёт, и без
 * пинов ниже отвалилась бы ВСЯ сеть приложения — вход, sync, фото.
 *
 * Держим обе ветки (старую X1/X2 и новую YE/YR) и в каждой оба ключа, ECDSA и RSA. Какой
 * якорь окажется доверенным, решает trust store конкретного планшета, а он обновляется
 * отдельно от приложения (Google Play system updates) — то есть парк какое-то время живёт
 * на разных ветках одновременно.
 *
 * У кросс-подписанного корня пин тот же, что у самоподписанного (ключ один), поэтому
 * отдельные пины на кросс-подписи не нужны.
 *
 * Пины снимаются с официальных PEM https://letsencrypt.org/certs/ :
 * ```
 * openssl x509 -in root-ye.pem -pubkey -noout \
 *   | openssl pkey -pubin -outform der \
 *   | openssl dgst -sha256 -binary | openssl enc -base64
 * ```
 * Те же PEM лежат в `app/src/test/resources/certs/` — на них смотрит CertificatePinsTest.
 */
internal object CertificatePins {

    /**
     * Домен мигрировал с matcheck.fvds.ru (старый сервер на FirstVDS) на mat.su10.ru.
     * Пины ниже — корни Let's Encrypt, они валидируют любой выданный ею сертификат,
     * поэтому при смене хоста трогать их не нужно.
     */
    const val PROD_HOST: String = "mat.su10.ru"

    /** ISRG Root YE — ECDSA-корень поколения Y. Через него идёт текущая цепочка прода. */
    const val ISRG_ROOT_YE: String = "sha256/sCkq5UWXjg+7mKu9lMhhYF5bGLsy7VI/UNW3tccdR7w="

    /** ISRG Root YR — RSA-корень поколения Y. Страховка на случай ухода на RSA-ключ. */
    const val ISRG_ROOT_YR: String = "sha256/fk6IOKit1ild5647BH06ujSIq5XbCgqlbYl6ANhhi88="

    /** ISRG Root X1 — RSA-корень прежнего поколения. Откат на старую ветку. */
    const val ISRG_ROOT_X1: String = "sha256/C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M="

    /** ISRG Root X2 — ECDSA-корень прежнего поколения; сейчас именно им кросс-подписан Root YE. */
    const val ISRG_ROOT_X2: String = "sha256/diGVwiVYbubAI3RW4hB9xU8e/CH2GnkuvVFZE8zmgzI="

    fun build(): CertificatePinner = CertificatePinner.Builder()
        .add(PROD_HOST, ISRG_ROOT_YE)
        .add(PROD_HOST, ISRG_ROOT_YR)
        .add(PROD_HOST, ISRG_ROOT_X1)
        .add(PROD_HOST, ISRG_ROOT_X2)
        .build()
}
