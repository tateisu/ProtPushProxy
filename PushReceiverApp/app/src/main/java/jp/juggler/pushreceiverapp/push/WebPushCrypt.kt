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
    // HKDF from RFC 5869: `HKDF-Expand(HKDF-Extract(salt, ikm), info, length)`.
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
    }

    fun decipherAes128Gcm(
        contentEncryptionKey: ByteArray,
        nonce: ByteArray,
        body: ByteArray,
    ): ByteArray {
        // https://zditect.com/code/js-and-java-docking-aes128gcm-encryption-and-decryption-algorithm.html

        val tagBits = 128 // Must be one of {128, 120, 112, 104, 96}
        val cip = Cipher.getInstance(
            "AES/GCM/PKCS5Padding",
            provider
        )
        cip.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(contentEncryptionKey, "AES"),
            GCMParameterSpec(tagBits, nonce),
        )
        return cip.doFinal(body)
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

        // Derive the Content Encryption Key
        val contentEncryptionKeyInfo = createInfo(
            type = "aesgcm",
            clientPublicKey = receiverPublicBytes,
            serverPublicKey = senderPublicBytes,
        )

        val prk = hkdf(
            salt = authSecret,
            ikm = sharedKeyBytes,
            info = "Content-Encoding: auth\u0000".encodeUTF8(),
            length = sha256Length
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

    //////////////////////////////////////////////


    @Suppress("ArrayInDataClass")
    data class ByteRange(
        val ba:ByteArray,
        val start:Int,
        val end:Int,
    ) {
        val size get()= end-start
    }

    class Aes128gcmPayload(
        val salt:ByteArray,
        val recordSize:Int,
        val keyId:ByteArray,
        var cipherText:ByteArray
    )

    /**
     * Content-Encoding: aes128gcm
     * のヘッダを読む
     * https://asnokaze.hatenablog.com/entry/20170202/1486046514
     */
    fun parseAes128gcmPayload(
        payload:ByteArray,
    ) :Aes128gcmPayload {
        val end = payload.size
        var pos = 0
        fun readBytes(size: Int): ByteArray {
            if (pos + size > end) error("unexpected end.")
            val rv = payload.copyOfRange(pos,pos+size)
            pos += size
            return rv
        }
        fun readUInt8(): Int {
            if (pos >= end) error("unexpected end.")
            val b = payload[pos++]
            return b.toInt().and(255)
        }
        fun readUInt32(): Int {
            if (pos + 4 > end) error("unexpected end.")
            // Big Endian
            val b0 = payload[pos].toInt().and(255).shl(24)
            val b1 = payload[pos + 1].toInt().and(255).shl(16)
            val b2 = payload[pos + 2].toInt().and(255).shl(8)
            val b3 = payload[pos + 3].toInt().and(255)
            pos += 4
            return b0.or(b1).or(b2).or(b3)
        }

        val salt = readBytes(16)
        val recordSize = readUInt32()
        val keyIdLen = readUInt8()
        val keyId = readBytes(keyIdLen)
        return Aes128gcmPayload(
            salt = salt,
            recordSize = recordSize,
            keyId = keyId,
            cipherText = readBytes(end-pos)
        )
    }



    // The "aes128gcm" IKM info string is "WebPush: info\0", followed by the
// receiver and sender public keys.
    val ECE_WEBPUSH_AES128GCM_IKM_INFO_PREFIX = "WebPush: info\u0000".encodeUTF8()
    val ECE_WEBPUSH_AES128GCM_IKM_INFO_LENGTH = 144 // 64*2 + prefix(14)
    val ECE_WEBPUSH_IKM_LENGTH = 32
    fun createInfoAes128Gcm(
        prefix:ByteArray,
        receiverPublicKey:ByteArray,
        senderPublicKey:ByteArray,
    ) = ByteArrayOutputStream(ECE_WEBPUSH_AES128GCM_IKM_INFO_LENGTH).apply {
        // Copy the prefix. 14 bytes
        write(prefix)

        // Copy the receiver public key. 65 bytes.
        write(receiverPublicKey)
//        const EC_GROUP* recvGrp = EC_KEY_get0_group(recvKey);
//        const EC_POINT* recvPubKeyPt = EC_KEY_get0_public_key(recvKey);
//        EC_POINT_point2oct(recvGrp, recvPubKeyPt, POINT_CONVERSION_UNCOMPRESSED,
//            &info[offset], ECE_WEBPUSH_PUBLIC_KEY_LENGTH, NULL);

        // Copy the sender public key.  65 bytes.
        write(senderPublicKey)
//        const EC_GROUP* senderGrp = EC_KEY_get0_group(senderKey);
//        const EC_POINT* senderPubKeyPt = EC_KEY_get0_public_key(senderKey);
//        EC_POINT_point2oct(senderGrp, senderPubKeyPt, POINT_CONVERSION_UNCOMPRESSED,
//            &info[offset], ECE_WEBPUSH_PUBLIC_KEY_LENGTH, NULL);

    }.toByteArray()

    val ECE_AES128GCM_KEY_INFO = "Content-Encoding: aes128gcm\u0000".encodeUTF8()
    val ECE_AES128GCM_NONCE_INFO = "Content-Encoding: nonce\u0000".encodeUTF8()
    val ECE_AES_KEY_LENGTH =16
    val ECE_NONCE_LENGTH =12
    val ECE_TAG_LENGTH = 16


//    /**
//     * ヘッダ
//     */
//    fun aes128gcmDecrypt(
//        receiverPrivate: PrivateKey,
//        senderPublic:ECPublicKey,
//        receiverPrivateKeyBytes:ByteArray,
//        receiverPublicKeyBytes:ByteArray,
//        senderPublicKeyBytes:ByteArray,
//        authSecret:ByteArray,
//        salt:ByteArray,
//        recordSize:Int,
//        padSize:Int,
//        ciphertext:ByteRange,
//        unpad:Any,
//    ):ByteArray{
//        if( authSecret.size != 0) error("incorrect authSecret.size")
//        if( salt.size != 16) error("incorrect salt.size")
//        if( ciphertext.size ==0) error("ciphertext salt.size")
//
//        // ※ aes128gcm は trailerの処理は必要ない
//
//        val sharedSecretBytes = sharedKeyBytes(receiverPrivate ,senderPublic )
//
//        // The new "aes128gcm" scheme includes the sender and receiver public keys in
//        // the info string when deriving the Web Push IKM.
//        // For decryption, the local static private key is the receiver key, and the
//        // remote ephemeral public key is the sender key.
//        val ikmInfo =  ece_webpush_aes128gcm_generate_info(
//            ECE_WEBPUSH_AES128GCM_IKM_INFO_PREFIX,
//            receiverPublicKeyBytes,
//            senderPublicKeyBytes
//        )
//
//        val  prk = hkdf(
//            salt = authSecret,
//            ikm = sharedSecretBytes,
//            info = ikmInfo,
//            length = 32
//        )
//
//        val contentEncryptionKey = hkdf(
//            salt = salt,
//            ikm=prk,
//            info =  ECE_AES128GCM_KEY_INFO,
//            length = ECE_AES_KEY_LENGTH
//        )
//        val nonce = hkdf(
//            salt = salt,
//            ikm = prk,
//            info = ECE_AES128GCM_NONCE_INFO,
//            length =ECE_NONCE_LENGTH
//        )
//
//        val rs = recordSize
//
//
//        // The offset at which to start reading the ciphertext.
//        var ciphertextStart = 0;
//
//        // The offset at which to start writing the plaintext.
//        var plaintextStart = 0;
//        val ciphertextLen = ciphertext.size
//        var counter = 0
//        while( ciphertextStart < ciphertext.end) {
//            run {
//                var ciphertextEnd = if (rs > ciphertextLen - ciphertextStart) {
//                    // This check is equivalent to `ciphertextStart + rs > ciphertextLen`;
//                    // it's written this way to avoid an integer overflow.
//                    ciphertextLen;
//                } else {
//                    ciphertextStart + rs;
//                }
//                assert(ciphertextEnd > ciphertextStart);
//
//                // The full length of the encrypted record.
//                var recordLen = ciphertextEnd -ciphertextStart;
//                if (recordLen <= ECE_TAG_LENGTH) {
//                    error("short block")
//                }
//
//                // Generate the IV for this record using the nonce.
//                uint8_t iv [ECE_NONCE_LENGTH];
//                ece_generate_iv(nonce, counter, iv);
//
//                // Decrypt the record.
//                ece_decrypt_record(
//                    contentEncryptionKey,
//                    iv,
//                    & ciphertext [ciphertextStart],
//                    recordLen,
//                    &plaintext[plaintextStart]
//                )
//
//                // `unpad` sets `blockLen` to the actual plaintext block length, without
//                // the padding delimiter and padding.
//                val lastRecord = ciphertextEnd >= ciphertextLen;
//                val blockLen = recordLen -ECE_TAG_LENGTH;
//                if (blockLen < padSize) {
//                    error("DECRYPT_PADDING")
//                }
//                err = unpad(& plaintext [plaintextStart], lastRecord, &blockLen);
//                if (err) {
//                    goto end;
//                }
//                ciphertextStart = ciphertextEnd;
//                plaintextStart += blockLen;
//            }
//            ++counter
//        }
//
//                }
//
//                    // Finally, set the actual plaintext length.
//                    *plaintextLen = plaintextStart;
//
//                    end:
//                    EVP_CIPHER_CTX_free(ctx);
//                    return err;
//                }
//
//
//    }


}
