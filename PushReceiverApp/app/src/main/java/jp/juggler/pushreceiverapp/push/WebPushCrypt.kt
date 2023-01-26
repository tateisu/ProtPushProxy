package jp.juggler.pushreceiverapp.push

import jp.juggler.util.encodeUTF8
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.ECPointUtil
import org.bouncycastle.jce.spec.ECNamedCurveSpec
import org.conscrypt.Conscrypt
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Provider
import java.security.Security
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class WebPushCrypt(
    private val provider: Provider = _conscryptProvider
) {
    companion object {
        @Suppress("ObjectPropertyName")
        private val _conscryptProvider by lazy {
            Conscrypt.newProvider().also { Security.addProvider(it) }
        }

        const val sha256Length = 32
        const val curveName = "secp256r1"

        // k=v;k=v;... を解釈する
        fun String.parseSemicoron() = split(";").map { pair ->
            pair.split("=", limit = 2).map { it.trim() }
        }.mapNotNull {
            when {
                it.isEmpty() -> null
                else -> it[0] to it.elementAtOrNull(1)
            }
        }.toMap()
    }

    /**
     * ECの鍵ペアを作成する
     */
    fun generateKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC", provider).apply {
            @Suppress("SpellCheckingInspection")
            initialize(ECGenParameterSpec(curveName))
        }.genKeyPair()
            ?: error("genKeyPair returns null")

    // 鍵全体をX509でエンコードしたものをデコードする
    fun decodePrivateKey(src: ByteArray): PrivateKey {
        return KeyFactory.getInstance("EC", provider)
            .generatePrivate(PKCS8EncodedKeySpec(src))
    }

    // ECPrivateKey のS値を32バイトの配列にコピーする
    // BigInteger.toByteArrayは可変長なので、長さの調節を行う
    fun encodePrivateKeyRaw(key: ECPrivateKey): ByteArray {
        val srcBytes = key.s.toByteArray()
        return when {
            srcBytes.size == 32 -> srcBytes
            // 32バイト以内なら先頭にゼロを詰めて返す
            srcBytes.size < 32 -> ByteArray(32).also {
                System.arraycopy(
                    srcBytes, 0,
                    it, it.size - srcBytes.size,
                    srcBytes.size
                )
            }
            // ビッグエンディアンの先頭に符号ビットが付与されるので、32バイトに収まらない場合がある
            // 末尾32バイト分を返す
            else -> ByteArray(32).also {
                System.arraycopy(
                    srcBytes, srcBytes.size - it.size,
                    it, 0,
                    it.size
                )
            }
        }
    }

    // JavaScriptのcreateECDHがエンコードした秘密鍵をデコードする
    // https://github.com/nodejs/node/blob/main/lib/internal/crypto/diffiehellman.js#L232
    // https://github.com/nodejs/node/blob/main/src/crypto/crypto_ec.cc#L265
    fun decodePrivateKeyRaw(srcBytes: ByteArray): ECPrivateKey {
        // 符号拡張が起きないように先頭に０を追加する
        val newBytes = ByteArray(srcBytes.size + 1).also {
            System.arraycopy(
                srcBytes, 0,
                it, it.size - srcBytes.size,
                srcBytes.size
            )
        }

        val s = BigInteger(newBytes)

        // テキトーに鍵を作る
        val keyPair = generateKeyPair()
        // params部分を取り出す
        val ecParameterSpec = (keyPair.private as ECPrivateKey).params
        // s値を指定して鍵を作る
        val privateKeySpec = ECPrivateKeySpec(s, ecParameterSpec)
        return KeyFactory.getInstance("EC", provider)
            .generatePrivate(privateKeySpec) as ECPrivateKey
    }

    /**
     * WebPushのp256dh、つまり公開鍵を X9.62 uncompressed format で符号化したバイト列を作る。
     * - 出力の長さは65バイト
     */
    fun encodeP256Dh(src: ECPublicKey): ByteArray = src.run {
        val bitsInByte = 8
        val keySizeBytes = (params.order.bitLength() + bitsInByte - 1) / bitsInByte
        return ByteArray(1 + 2 * keySizeBytes).also { dst ->
            var offset = 0
            dst[offset++] = 0x04
            w.affineX.toByteArray().let { x ->
                when {
                    x.size <= keySizeBytes ->
                        System.arraycopy(
                            x, 0,
                            dst, offset + keySizeBytes - x.size,
                            x.size
                        )

                    x.size == keySizeBytes + 1 && x[0].toInt() == 0 ->
                        System.arraycopy(
                            x, 1,
                            dst, offset,
                            keySizeBytes
                        )

                    else -> error("x value is too large")
                }
            }
            offset += keySizeBytes
            w.affineY.toByteArray().let { y ->
                when {
                    y.size <= keySizeBytes -> System.arraycopy(
                        y, 0,
                        dst, offset + keySizeBytes - y.size,
                        y.size
                    )

                    y.size == keySizeBytes + 1 && y[0].toInt() == 0 -> System.arraycopy(
                        y, 1,
                        dst, offset,
                        keySizeBytes
                    )
                    else -> error("y value is too large")
                }
            }
        }
    }

    /**
     * p256dh(65バイト)から公開鍵を復元する
     */
    fun decodeP256dh(src: ByteArray): ECPublicKey = src.run {
        val spec = ECNamedCurveTable.getParameterSpec(curveName)
        val params = ECNamedCurveSpec(curveName, spec.curve, spec.g, spec.n)
        val pubKeySpec = ECPublicKeySpec(
            ECPointUtil.decodePoint(params.curve, this),
            params
        )
        return KeyFactory.getInstance("EC", provider)
            .generatePublic(pubKeySpec) as ECPublicKey
    }

    fun sharedKeyBytes(receiverPrivate: PrivateKey, serverKey: ECPublicKey): ByteArray {
        val keyAgreement = KeyAgreement.getInstance("ECDH", provider)
        keyAgreement.init(receiverPrivate)
        keyAgreement.doPhase(serverKey, true)
        return keyAgreement.generateSecret()
    }

    // Simplified HKDF, returning keys up to 32 bytes long
    fun hkdf(
        salt: ByteArray,
        ikm: ByteArray,
        info: ByteArray,
        length: Int,
    ): ByteArray {
        if (length > 32) {
            error("Cannot return keys of more than 32 bytes, $length requested")
        }
        val key = Mac.getInstance("HMacSHA256").let {
            it.init(SecretKeySpec(salt, "HMacSHA256"))
            it.doFinal(ikm)
        }
        val digest = Mac.getInstance("HMacSHA256").let {
            it.init(SecretKeySpec(key, "HMacSHA256"))
            it.update(info)
            it.doFinal(ByteArray(1) { 1 })
        }
        return digest.copyOfRange(0, length)
    }

    /**
     *
     */
    fun createInfo(
        type: String,
        clientPublicKey: ByteArray, // 65 byte
        serverPublicKey: ByteArray, // 65 byte
    ): ByteArray {
        // For the purposes of push encryption the length of the keys will
        // always be 65 bytes.

        // The start index for each element within the buffer is:
        // value               | length | start    |
        // -----------------------------------------
        // 'Content-Encoding: '| 18     | 0        |
        // type                | len    | 18       |
        // nul byte            | 1      | 18 + len |
        // 'P-256'             | 5      | 19 + len |
        // nul byte            | 1      | 24 + len |
        // client key length   | 2      | 25 + len |
        // client key          | 65     | 27 + len |
        // server key length   | 2      | 92 + len |
        // server key          | 65     | 94 + len |
        val info = ByteArrayOutputStream(120)
        fun writeNullTerminateString(s: String) {
            info.write(s.encodeUTF8())
            info.write(0)
        }

        fun writeLengthAndBytes(b: ByteArray) {
            val len = b.size
            info.write(len.shr(8).and(255))
            info.write(len.and(255))
            info.write(b)
        }
        // The string 'Content-Encoding: ', as utf-8
        // The 'type' of the record, a utf-8 string
        // A single null-byte
        writeNullTerminateString("Content-Encoding: $type")
        // The string 'P-256', declaring the elliptic curve being used
        // A single null-byte
        writeNullTerminateString("P-256")
        // The length of the client's public key as a 16-bit integer
        // Now the actual client public key
        writeLengthAndBytes(clientPublicKey)
        // Length of server public key
        // The key itself
        writeLengthAndBytes(serverPublicKey)
        return info.toByteArray()
    }

    //        val decipher = crypto.createCipheriv('id-aes128-GCM', contentEncryptionKey, nonce);
    /**
     * JavaScriptの createCipheriv
     * const decipher = crypto.createCipheriv('id-aes128-GCM', contentEncryptionKey,nonce);
     * result = decipher.update(body)
     */
    fun decipher(
        contentEncryptionKey: ByteArray,
        nonce: ByteArray,
        body: ByteArray,
    ): ByteArray {
        val tagBits = 128 // Must be one of {128, 120, 112, 104, 96}
        val cip = Cipher.getInstance(
            "AES/GCM/NoPadding",
            provider
        )
        cip.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(contentEncryptionKey, "AES"),
            GCMParameterSpec(tagBits, nonce),
        )
        return cip.doFinal(body)
//        val blockSize=cip.blockSize
//        val bao = ByteArrayOutputStream()
//        val tmp = ByteArray(1024)
//        val end = body.size
//        var i=0
//        while(i<end) {
//            val step = min(blockSize, end - i)
//            AdbLog.i("decipher step=$step pos=$i/$end")
//            val delta = cip.update(body, i, step, tmp, 0)
//            AdbLog.i("delta=$delta")
//            if (delta > 0){
//                bao.write(tmp, 0, delta)
//
//            }
//            i+=step
//        }
//        run{
//            val delta = cip.doFinal(body,end,0,tmp,0)
//            AdbLog.i("final: delta=$delta")
//            if (delta > 0) bao.write(tmp, 0, delta)
//        }
//        return bao.toByteArray()
    }

    fun removePadding(src: ByteArray): ByteArray = src.run {
        val start = when {
            size >= 3 && this[2].toInt() == 0 -> {
                // remove padding and GCM auth tag
                val b0 = this[0].toInt().and(255).shl(8)
                val b1 = this[1].toInt().and(255)
                b0.or(b1) + 2
            }
            else -> {
                // 先頭の空白を除去する
                var i = 0
                while (i < src.size && src[i].toInt() <= 0x20) ++i
                i
            }
        }
        // java版では末尾のパディング除去は不要
        copyOfRange(start, size)
    }

    fun decodeBody(
        body: ByteArray,
        saltBytes: ByteArray,
        // User agent private key (ua_private)
        receiverPrivateBytes: ByteArray,
        // User agent public key (ua_public)
        // 購読時に指定する
        receiverPublicBytes: ByteArray,
        // サーバの公開鍵 65バイト
        senderPublicBytes: ByteArray,
        // Authentication secret (auth_secret)
        // 購読時に指定する
        authSecret: ByteArray,
    ): ByteArray {

        val receiverPrivate = decodePrivateKey(receiverPrivateBytes)
        val senderPublic = decodeP256dh(senderPublicBytes)

        // 共有秘密鍵を作成する (エンコード時とデコード時で使う鍵が異なる)
        val sharedKeyBytes = sharedKeyBytes(
            receiverPrivate = receiverPrivate,
            serverKey = senderPublic
        )

        val prk = hkdf(
            salt = authSecret,
            ikm = sharedKeyBytes,
            info = "Content-Encoding: auth\u0000".encodeUTF8(),
            length = sha256Length
        )

        // Derive the Content Encryption Key
        val contentEncryptionKeyInfo = createInfo(
            type = "aesgcm",
            clientPublicKey = receiverPublicBytes,
            serverPublicKey = senderPublicBytes,
        )

        val contentEncryptionKey = hkdf(
            salt = saltBytes,
            ikm = prk,
            info = contentEncryptionKeyInfo,
            length = 16
        )

        // Derive the Nonce
        val nonceInfo = createInfo(
            type = "nonce",
            clientPublicKey = receiverPublicBytes,
            serverPublicKey = senderPublicBytes
        )

        val nonce = hkdf(
            salt = saltBytes,
            ikm = prk,
            info = nonceInfo,
            length = 12,
        )

        val result = decipher(
            contentEncryptionKey = contentEncryptionKey,
            nonce = nonce,
            body = body,
        )

        return removePadding(result)
    }
}
