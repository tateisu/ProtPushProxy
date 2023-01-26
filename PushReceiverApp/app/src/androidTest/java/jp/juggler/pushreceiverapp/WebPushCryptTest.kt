package jp.juggler.pushreceiverapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import jp.juggler.pushreceiverapp.push.WebPushCrypt
import jp.juggler.pushreceiverapp.push.WebPushCrypt.Companion.parseSemicoron
import jp.juggler.util.AdbLog
import jp.juggler.util.decodeBase64
import jp.juggler.util.decodeJsonObject
import jp.juggler.util.decodeUTF8
import jp.juggler.util.encodeBase64
import jp.juggler.util.encodeUTF8
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.interfaces.ECPrivateKey

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class WebPushCryptTest {
    // https://developers.google.com/web/updates/2016/03/web-push-encryption

    @Test
    fun testGenerateKeyPair() {
        val crypt = WebPushCrypt()
        val pair = crypt.generateKeyPair()
        val privateKey = (pair.private as ECPrivateKey)
        val bytes = crypt.encodePrivateKeyRaw(privateKey)
        val newKey = crypt.decodePrivateKeyRaw(bytes)
        assertEquals(
            "s is same?",
            privateKey.s,
            newKey.s,
        )
    }

    @Test
    fun decryptMessage() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("jp.juggler.pushreceiverapp", appContext.packageName)

        // Authentication secret (auth_secret)
        // 購読時に指定する
        val authSecret = "BTBZMqHH6r4Tts7J_aSIgg".decodeBase64()

        // User agent public key (ua_public)
        // 購読時に指定する
        val receiverPublicBytes =
            "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4".decodeBase64()

        // User agent private key (ua_private)
        val receiverPrivateBytes = "q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94".decodeBase64()

        // encryption ヘッダから
        val saltBytes = "pr1_1DFjrzX3RNvJPRngDA".decodeBase64()

        // Application server public key (as_public)
        // crypto-key ヘッダの前半、dh=xxx; の中味
        // crypto-key: dh=BLJQjupjQyujhTC--_5xRUgcBfAP_zINsAGTlaDuEME7s9TVgQYsyrzrgbt1vqScmZkoj4BWfPit6EzzaXDW02I;p256ecdsa=BDmWlrZ3gvcv0R7sBhaSp_99FRSC3bBNn9CElRvbcviwYwVPL1Z-G9srAJS6lv_pMe5IkTmKgBWUCNefnN3QoeQ
        val senderPublicBytes =
            "BLJQjupjQyujhTC--_5xRUgcBfAP_zINsAGTlaDuEME7s9TVgQYsyrzrgbt1vqScmZkoj4BWfPit6EzzaXDW02I"
                .decodeBase64()

        val body =
            "pTTuh1jT8KJ4zaGwIWjg417KTDzh+eIVe472nMgett3XyhoM5pAz8Yu2RPBXJHE/AojoMA1g+/uzbByu3d1/AygBh99qJ6Xtjya+XBSYoVrNJqT7vq0cKU9bZ8NrEepnaZUc2HjFUDDXNyHi2xBtJnMk/hSZTzyaiCQS2KssGAwixgdK/dTP8Yg+Pul3tgOQvq5CbYFd7iwBQntVv80vO8X+5hyIglA21+6/2fq5lCZSMri5K9/WbSb6erLkxO//A92KjZTnuufE4pUwtIdYW1bFnw5xu6ozjsCsDLbQTSo+JmghOzc/iYx5hG+y5YViC1UXue4eKKlmjbVDRLH6WkEEIKH2cwd4Gf9ewhYwhH7oKKIc4tjvRunq2gtBirQgRYJahgfwykdYA44iyogBc1rFZPGbxr1ph4RxVhdBmIZ+yMN6GQSiDCS+8jKGsc5xnjxrSXXdFva1a2xc1lpiReypZlTTXFmF16Cf+Z6B0UvFTa2AcqEDD0BBlhhbMBoG7n4CRjr5ObE2lG5PBg+gqitx/O1S+X8a4N78L+eK1upEVM+HRQAdCmiqDNJF0/N/VWSMrNCl7HNgnhmYU9Z1aYepiEioz1Tu14UzY/2NOx5z4h4szyJW8s/diAyOhnh+RBRM3QLHtygpLZ3i7o6vVUc="
                .decodeBase64()

        val crypt = WebPushCrypt()

        val receiverPrivate = crypt.decodePrivateKeyRaw(receiverPrivateBytes)
        val senderPublic = crypt.decodeP256dh(senderPublicBytes)

        // 共有秘密鍵を作成する (エンコード時とデコード時で使う鍵が異なる)
        val sharedKeyBytes = crypt.sharedKeyBytes(
            receiverPrivate = receiverPrivate,
            serverKey = senderPublic
        )

        assertEquals(
            "sharedKeyBytes",
            "irnQ9JOfMP/kl/SB8LUHpvjmUjwlkYzypisDnlHVKSA=",
            sharedKeyBytes.encodeBase64()
        )

        val prk = crypt.hkdf(
            salt = authSecret,
            ikm = sharedKeyBytes,
            info = "Content-Encoding: auth\u0000".encodeUTF8(),
            length = 32
        )

        assertEquals(
            "prk",
            "Tq5bGvBQUWTQdqFfAK1WAE/etlIpcc07QLh+RNCAD9Y=",
            prk.encodeBase64()
        )

        // Derive the Content Encryption Key
        val contentEncryptionKeyInfo = crypt.createInfo(
            type = "aesgcm",
            clientPublicKey = receiverPublicBytes,
            serverPublicKey = senderPublicBytes,
        )

        assertEquals(
            "contentEncryptionKeyInfo",
            "Q29udGVudC1FbmNvZGluZzogYWVzZ2NtAFAtMjU2AABBBCVxsr7N/eNgVRqvHtD0zTZsEc6+VV+JvLexhqUzORcxaOzi6+AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4AQQSyUI7qY0Mro4Uwvvv+cUVIHAXwD/8yDbABk5Wg7hDBO7PU1YEGLMq864G7db6knJmZKI+AVnz4rehM82lw1tNi",
            contentEncryptionKeyInfo.encodeBase64()
        )

        val contentEncryptionKey = crypt.hkdf(
            salt = saltBytes,
            ikm = prk,
            info = contentEncryptionKeyInfo,
            length = 16
        )

        assertEquals(
            "contentEncryptionKey",
            "patR3W6rY0PG/5YnwY3/kA==",
            contentEncryptionKey.encodeBase64()
        )

        // Derive the Nonce
        val nonceInfo = crypt.createInfo(
            type = "nonce",
            clientPublicKey = receiverPublicBytes,
            serverPublicKey = senderPublicBytes,
        )

        assertEquals(
            "nonceInfo",
            "Q29udGVudC1FbmNvZGluZzogbm9uY2UAUC0yNTYAAEEEJXGyvs3942BVGq8e0PTNNmwRzr5VX4m8t7GGpTM5FzFo7OLr4BhZe9MEebhuPI+OztV3ylkYfpJGmQ22ggCLDgBBBLJQjupjQyujhTC++/5xRUgcBfAP/zINsAGTlaDuEME7s9TVgQYsyrzrgbt1vqScmZkoj4BWfPit6EzzaXDW02I=",
            nonceInfo.encodeBase64()
        )

        val nonce = crypt.hkdf(
            salt = saltBytes,
            ikm = prk,
            info = nonceInfo,
            length = 12,
        )

        assertEquals(
            "nonce",
            "E/GxDDwW9lfa7/by",
            nonce.encodeBase64()
        )

        var result = crypt.decipher(
            contentEncryptionKey = contentEncryptionKey,
            nonce = nonce,
            body = body,
        )

        // 末尾のパディングが異なるのでそのまま比較できなかった
        //        assertEquals(
        //            "result ",
        //            "AAB7InRpdGxlIjoi44GC44Gq44Gf44Gu44OI44Kl44O844OI44GMIHRhdGVpc3Ug8J+kuSDjgZXjgpPjgavjgYrmsJfjgavlhaXjgornmbvpjLLjgZXjgozjgb7jgZfjgZ8iLCJpbWFnZSI6bnVsbCwiYmFkZ2UiOiJodHRwczovL21hc3RvZG9uMi5qdWdnbGVyLmpwL2JhZGdlLnBuZyIsInRhZyI6ODQsInRpbWVzdGFtcCI6IjIwMTgtMDUtMTFUMTc6MDY6NDIuODg3WiIsImljb24iOiIvc3lzdGVtL2FjY291bnRzL2F2YXRhcnMvMDAwLzAwMC8wMDMvb3JpZ2luYWwvNzJmMWRhMzM1MzliZTExZS5qcGciLCJkYXRhIjp7ImNvbnRlbnQiOiI6ZW5lbXlfYnVsbGV0OiIsIm5zZnciOm51bGwsInVybCI6Imh0dHBzOi8vbWFzdG9kb24yLmp1Z2dsZXIuanAvd2ViL3N0YXR1c2VzLzk4NzkzMTIzMDgxNzc3ODQxIiwiYWN0aW9ucyI6W10sImFjY2Vzc190b2tlbiI6bnVsbCwibWVzc2FnZSI6IiV7Y291bnR9IOS7tuOBrumAmuefpSIsImRpciI6Imx0ciJ9feATI5X0Yp+LJJViDiIYkTw=",
        //            result.encodeBase64()
        //        )

        result = crypt.removePadding(result)

        assertEquals(
            "result2",
            "AAB7InRpdGxlIjoi44GC44Gq44Gf44Gu44OI44Kl44O844OI44GMIHRhdGVpc3Ug8J+kuSDjgZXjgpPjgavjgYrmsJfjgavlhaXjgornmbvpjLLjgZXjgozjgb7jgZfjgZ8iLCJpbWFnZSI6bnVsbCwiYmFkZ2UiOiJodHRwczovL21hc3RvZG9uMi5qdWdnbGVyLmpwL2JhZGdlLnBuZyIsInRhZyI6ODQsInRpbWVzdGFtcCI6IjIwMTgtMDUtMTFUMTc6MDY6NDIuODg3WiIsImljb24iOiIvc3lzdGVtL2FjY291bnRzL2F2YXRhcnMvMDAwLzAwMC8wMDMvb3JpZ2luYWwvNzJmMWRhMzM1MzliZTExZS5qcGciLCJkYXRhIjp7ImNvbnRlbnQiOiI6ZW5lbXlfYnVsbGV0OiIsIm5zZnciOm51bGwsInVybCI6Imh0dHBzOi8vbWFzdG9kb24yLmp1Z2dsZXIuanAvd2ViL3N0YXR1c2VzLzk4NzkzMTIzMDgxNzc3ODQxIiwiYWN0aW9ucyI6W10sImFjY2Vzc190b2tlbiI6bnVsbCwibWVzc2FnZSI6IiV7Y291bnR9IOS7tuOBrumAmuefpSIsImRpciI6Imx0ciJ9fQ==",
            result.encodeBase64()
        )

        val text = result.decodeUTF8()
        assertEquals(
            "text",
            """{"title":"あなたのトゥートが tateisu 🤹 さんにお気に入り登録されました","image":null,"badge":"https://mastodon2.juggler.jp/badge.png","tag":84,"timestamp":"2018-05-11T17:06:42.887Z","icon":"/system/accounts/avatars/000/000/003/original/72f1da33539be11e.jpg","data":{"content":":enemy_bullet:","nsfw":null,"url":"https://mastodon2.juggler.jp/web/statuses/98793123081777841","actions":[],"access_token":null,"message":"%{count} 件の通知","dir":"ltr"}}""",
            text
        )
    }

    /**
     * 今どきのMastodonはCrypto-Keyが異なる
     * dh=XXX;p256ecdsa=XXX
     */
    @Test
    fun test2() {
        // 購読時に分かった情報
        val receiverPrivateBytes =
            "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgN1UVSn5P4XC9zuuT3sW8TTikA8AhsjZKp9W6BwzSlW2hRANCAAQSFVIOe_wsdFuDCKjrNRM1yWIkwlfx8ZoQ3OyYJ2K5oeXpmjo5EAimVq0Fs0NuxA8Y6F1hTB_Orc4gR1WOK-W4"
                .decodeBase64()
        val receiverPublicBytes =
            "BBIVUg57_Cx0W4MIqOs1EzXJYiTCV_HxmhDc7JgnYrmh5emaOjkQCKZWrQWzQ27EDxjoXWFMH86tziBHVY4r5bg"
                .decodeBase64()
        val senderPublicBytesOld =
            "BLzIgsz-VRhTxuVgNoQljTwAFzxbanfGxNk8tldaruBztvsK9elES_2lE_8c91-RNOInEBEFUrCzDw-60bzUCr8"
                .decodeBase64()
        val authSecret = "WrgqiWu8r3D9Ql3qYGkXTw"
            .decodeBase64()

        // プッシュメッセージに含まれる情報
        val headerJson = """{
            |"Digest":"SHA-256=kN1u9yotvg3utprFOaJ4qUGJ6cRhxGNfICXAGLjx7uM=",
            |"Content-Encoding":"aesgcm",
            |"Encryption":"salt=xtC3F3tO3UvQBtq-4Q38AQ",
            |"Crypto-Key":"dh=BLivX-rukpGqww9YMqavS7o112MNTobqBqjzPX1ioUojdrDHKM1DwZKn6U2au0ddohfC4BGTDatjF96S7dOP2C8;p256ecdsa=BLzIgsz-VRhTxuVgNoQljTwAFzxbanfGxNk8tldaruBztvsK9elES_2lE_8c91-RNOInEBEFUrCzDw-60bzUCr8",
            |"Authorization":"WebPush eyJ0eXAiOiJKV1QiLCJhbGciOiJFUzI1NiJ9.eyJhdWQiOiJodHRwczovL21hc3RvZG9uLW1zZy5qdWdnbGVyLmpwIiwiZXhwIjoxNjc0ODQ3MjQ4LCJzdWIiOiJtYWlsdG86dGF0ZWlzdStqdWdnbGVyQGdtYWlsLmNvbSJ9.ksn_40KkHNcS0G3C8jMG-dsA6AcUydYYc1sA8JyXCCuDrNr-CLnl_-ezk1s-pC75yukNN0pIfqPJlJYtY_MQoQ",
            |"<>cameFrom":"unifiedPush"
            |}""".trimMargin().decodeJsonObject()

        val rawBody =
            "4BVejMFrVADAEzkonO1QqUnDLiWCGAlzSbWg1KkG23BMZNLxUcQPJtULTTEMdoIzg4zZwDmsanDp5fepH8BDKpb5IoQlyFJjN0pq3tebWj79QL9h7QafxPByGx9HF9-zKlOUbmPMc3yS1tfccz30HvVSsMNUtcpuy1hsPkknwRBPI_SoLN20LEtAQ6IKLQGpiNMDx2rND9bd9wLz0Cr4pxUgZVxv0VjpiKHyTup0_BRotGd5e719uMmyDE-hzrqQYRr2QFrvHa1tNk41tpbPpeSAqhihsxU6bXxc4Lbws1Q6DyZBYHSUr6Av4SldA2Kmi3EjLijRvz3YEDWnSGv65V0SJv-O7tmFjexqFmFcF0qfv4Udzl9prjmutztcaWRAH-v9JdP3o2kthpK8RM4NC6Y0yU3GhEQ0z_1cfsuWsAvRe3H8pVcx3GXHVdWJ7VfqAwI-anSZz355-cuBbxAyrsBSp1Ysfru2D_5g53SZ7iNduHsXfKno9e6BkQr6u5pleXVHpRHYimzsjj46wbA0IfvEkhBpp9CoJ3JzPFkBowxpzuzmN1_IDiSDoYWVMYl1GXtwWOmKMKaKOCh3ULk2FH1WdUzNlvMd1N4Kf-MXjHFhkE33jSlLnKRLHqgzDEJgofWEzfoV-zruHFQfKwiN_7_NJdLYuGVV3z0IFiw6g9RdIvrNy7OEsTCaXbWC-xOEelJAvALvu308t_0PxxRdhSLVivrubyLZEKz1R8d6MzJ9b3f_mg6oYiGJ1u5oMrUvUUeNCERq4t9f0WMLo-zLFkNXvGe2TxnKMO9ZsWlXS1OnKnN6olNPKfVAFW50RxD6nBdP3S2a9uI58D9ycanCU_-i8osFrKg-GbYZ2tvRZw"
                .decodeBase64()


        // Encryption からsaltを読む
        val saltBytes = headerJson.string("Encryption")?.parseSemicoron()
            ?.get("salt")?.decodeBase64()
            ?: error("missing Encryption.salt")

        // Crypt-Key から dh と p256ecdsa を見る
        val cryptKeys = headerJson.string("Crypto-Key")?.parseSemicoron()
            ?: error("missing Crypto-Key")
        val dh = cryptKeys["dh"]?.decodeBase64()
            ?: senderPublicBytesOld

        // JWT検証で使われるらしい
        // val p256ecdsa = cryptKeys["p256ecdsa"]?.decodeBase64()
        //   ?: error("missing p256ecdsa")

        val crypt = WebPushCrypt()
        val receiverPrivate = crypt.decodePrivateKey(receiverPrivateBytes)
        val senderPublic = crypt.decodeP256dh(dh)

        // 共有秘密鍵を作成する (エンコード時とデコード時で使う鍵が異なる)
        val sharedKeyBytes = crypt.sharedKeyBytes(
            receiverPrivate = receiverPrivate,
            serverKey = senderPublic
        )

        val prk = crypt.hkdf(
            salt = authSecret,
            ikm = sharedKeyBytes,
            info = "Content-Encoding: auth\u0000".encodeUTF8(),
            length = 32
        )

        // Derive the Content Encryption Key
        val contentEncryptionKeyInfo = crypt.createInfo(
            type = "aesgcm",
            clientPublicKey = receiverPublicBytes,
            serverPublicKey = dh,
        )

        val contentEncryptionKey = crypt.hkdf(
            salt = saltBytes,
            ikm = prk,
            info = contentEncryptionKeyInfo,
            length = 16
        )

        // Derive the Nonce
        val nonceInfo = crypt.createInfo(
            type = "nonce",
            clientPublicKey = receiverPublicBytes,
            serverPublicKey = dh,
        )

        val nonce = crypt.hkdf(
            salt = saltBytes,
            ikm = prk,
            info = nonceInfo,
            length = 12,
        )

        var result = crypt.decipher(
            contentEncryptionKey = contentEncryptionKey,
            nonce = nonce,
            body = rawBody,
        )

        result = crypt.removePadding(result)

        val text = result.decodeUTF8()
        AdbLog.i(text)
        assertEquals(
            "text",
            """{"access_token":"ON0yBbjmRS-QuSk5Uv7fjeKFVQESnYCZP39z71On68E","preferred_locale":"ja","notification_id":341159,"notification_type":"favourite","icon":"https://m1j.zzz.ac/accounts/avatars/000/008/939/original/112ed1e5343f2e7b.png","title":"tateisu⛏️@テスト鯖 :ct080:さんにお気に入りに登録されました","body":"クライアントアプリにAPIキーとシークレットを持たせるという考え方は、クライアント側でシークレットが如何に漏洩しやすいかを考えると悪手としかいいようがない。簡単に漏洩して、アプリと無関係なbotに流用/悪用される"}""",
            text
        )
    }
}
