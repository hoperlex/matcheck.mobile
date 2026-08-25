package com.example.matcheckmobile.data.remote.net

import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Набор пинов боевого хоста.
 *
 * Главное, что здесь проверяется, — что закреплены КОРНИ, а не промежуточные. Пин на
 * промежуточном (E7) один раз уже подвёл: Let's Encrypt сменил иерархию выпуска, E7 из
 * цепочки исчез, и связь с сервером повисла на единственном оставшемся совпадении.
 *
 * **Границы теста.** CertificatePinner сверяет SPKI сертификатов ПЕРЕДАННОЙ ему цепочки,
 * а саму цепочку на устройстве строит Android — и до какого якоря он её достроит, зависит
 * от trust store конкретного планшета. Здесь мы лишь моделируем возможные очищенные
 * цепочки. Поэтому тест ловит регрессию набора пинов, но НЕ заменяет проверку на живых
 * планшетах перед выкаткой.
 *
 * Фикстуры — официальные PEM с https://letsencrypt.org/certs/ в `test/resources/certs`.
 */
class CertificatePinsTest {

    private val pinner = CertificatePins.build()
    private val host = CertificatePins.PROD_HOST

    @Test
    fun `поколение Y - планшет доверяет самому Root YE`() {
        // Тот самый сценарий, ради которого всё и правится: Android доставил Root YE
        // в trust store, путь обрывается на нём, X2 в цепочку уже не входит.
        pinner.check(host, chain("int-ye2", "root-ye"))
    }

    @Test
    fun `поколение Y - путь достроен до X2 кросс-подписью (сегодняшний прод)`() {
        pinner.check(host, chain("int-ye2", "root-ye", "isrg-root-x2"))
    }

    @Test
    fun `поколение Y - RSA-ветка через Root YR`() {
        pinner.check(host, chain("int-yr1", "root-yr"))
    }

    @Test
    fun `прежнее поколение - ветка под X1 всё ещё принимается`() {
        pinner.check(host, chain("e7", "isrgrootx1"))
    }

    @Test
    fun `один промежуточный без корня не проходит - intermediate мы не пиним`() {
        assertThrows(SSLPeerUnverifiedException::class.java) {
            pinner.check(host, chain("int-ye2"))
        }
    }

    @Test
    fun `пин на E7 снят - одного E7 в цепочке недостаточно`() {
        assertThrows(SSLPeerUnverifiedException::class.java) {
            pinner.check(host, chain("e7"))
        }
    }

    @Test
    fun `непиннуемый хост не проверяется - выгрузка фото в S3 не должна падать`() {
        pinner.check("s3.cloud.ru", chain("int-ye2"))
    }

    private fun chain(vararg names: String): List<X509Certificate> = names.map(::load)

    private fun load(name: String): X509Certificate {
        val stream = requireNotNull(javaClass.getResourceAsStream("/certs/$name.pem")) {
            "нет фикстуры /certs/$name.pem"
        }
        return stream.use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
    }
}
