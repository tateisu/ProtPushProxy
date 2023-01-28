package jp.juggler.pushreceiverapp.push

import jp.juggler.util.decodeBase64
import jp.juggler.util.encodeBase64
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.BufferedInputStream
import java.io.InputStream
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Security
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.EllipticCurve
import java.util.*
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.pow

object WebPushEncryption {
    private const val UNCOMPRESSED_POINT_INDICATOR: Byte = 0x04
    private val params = ECParameterSpec(
        EllipticCurve(
            ECFieldFp(
                BigInteger(
                    "FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF",
                    16
                )
            ), BigInteger(
                "FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFC",
                16
            ), BigInteger(
                "5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B",
                16
            )
        ), ECPoint(
            BigInteger(
                "6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296",
                16
            ), BigInteger(
                "4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5",
                16
            )
        ), BigInteger(
            "FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551",
            16
        ), 1
    )

    @Throws(Exception::class)
    @JvmStatic
    fun main(args: Array<String>) {
        Security.addProvider(BouncyCastleProvider())
        val endpoint = "https://updates.push.services.mozilla.com/push/v1/xxx"

        val alicePubKeyEnc = "base64 encoded public key ".decodeBase64()
        val alicePublicKeyBytes = alicePubKeyEnc.fromUncompressedPoint(params)

        val bobKeyPair = KeyPairGenerator.getInstance("ECDH", "BC").run {
            initialize(params)
            generateKeyPair()
        }
        val bobPrivateKey = bobKeyPair.private
        val bobPublicKey = bobKeyPair.public as ECPublicKey
        val bobPublicKeyBytes = bobPublicKey.toUncompressedPoint()

        val sharedSecret = KeyAgreement.getInstance("ECDH", "BC").run {
            init(bobPrivateKey)
            doPhase(alicePublicKeyBytes, true)
            generateSecret("AES")
        }

        val saltBytes = ByteArray(16).also {
            SecureRandom().nextBytes(it)
        }

        val prk = Mac.getInstance("HmacSHA256", "BC").run {
            init(SecretKeySpec(saltBytes, "HmacSHA256"))
            doFinal(sharedSecret.encoded)
        }

        // Expand
        val keyBytes16 = Mac.getInstance("HmacSHA256", "BC").run {
            init(SecretKeySpec(prk, "HmacSHA256"))
            //aes algorithm
            update("Content-Encoding: aesgcm128".toByteArray(StandardCharsets.US_ASCII))
            update(1.toByte())
            doFinal().copyOfRange(0, 16)
        }

        //nonce
        val nonceBytes12 = Mac.getInstance("HmacSHA256", "BC").run {
            reset()
            init(SecretKeySpec(prk, "HmacSHA256"))
            update("Content-Encoding: nonce".toByteArray(StandardCharsets.US_ASCII))
            update(1.toByte())
            doFinal().copyOfRange(0, 12)
        }

        val iv = generateNonce(nonceBytes12, 0)

        val cleartext = """{
      "message" : "great match41eeee!",
      "title" : "Portugal vs. Denmark4255",
      "icon" : "http://icons.iconarchive.com/icons/artdesigner/tweet-my-web/256/single-bird-icon.png",
   "tag" : "testtag1",
   "url" : "http://www.yahoo.com"
    }""".toByteArray()

        val cc = ByteArray(cleartext.size + 1).apply {
            this[0] = 0
            for (i in cleartext.indices) {
                this[i + 1] = cleartext[i]
            }
        }

        val ciphertext = Cipher.getInstance("AES/GCM/NoPadding", "BC").run {
            init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(keyBytes16, 0, 16, "AES-GCM"),
                IvParameterSpec(iv),
            )
            doFinal(cc)
        }

        val url = URL(endpoint)
        val urlConnection = url.openConnection() as HttpURLConnection
        urlConnection.requestMethod = "POST"
        urlConnection.setRequestProperty("Content-Length", ciphertext.size.toString() + "")
        urlConnection.setRequestProperty("Content-Type", "application/octet-stream")
        urlConnection.setRequestProperty(
            "encryption-key",
            "keyid=p256dh;dh=" + bobPublicKeyBytes.encodeBase64()
        )
        urlConnection.setRequestProperty(
            "encryption",
            "keyid=p256dh;salt=" + saltBytes.encodeBase64()
        )
        urlConnection.setRequestProperty("content-encoding", "aesgcm128")
        urlConnection.setRequestProperty("ttl", "60")
        urlConnection.doInput = true
        urlConnection.doOutput = true
        val outputStream = urlConnection.outputStream
        outputStream.write(ciphertext)
        outputStream.flush()
        outputStream.close()
        if (urlConnection.responseCode == 201) {
            val result = readStream(urlConnection.inputStream)
            println("PUSH OK: $result")
        } else {
            val errorStream = urlConnection.errorStream
            val error = readStream(errorStream)
            println("PUSHNot OK: $error")
        }
    }

    fun generateNonce(base: ByteArray, index: Int): ByteArray {
        val nonce = base.copyOfRange(0, 12)
        for (i in 0..5) {
            nonce[nonce.size - 1 - i] = (
                    nonce[nonce.size - 1 - i].toInt() xor
                            ((index / 256.0.pow(i.toDouble()))
                                .toInt()
                                .and(0xff)
                                    )
                    ).toByte()
        }
        return nonce
    }

    @Throws(Exception::class)
    private fun readStream(errorStream: InputStream): String {
        val bs = BufferedInputStream(errorStream)
        var i = 0
        val b = ByteArray(1024)
        val sb = StringBuilder()
        while (bs.read(b).also { i = it } != -1) {
            sb.append(String(b, 0, i))
        }
        return sb.toString()
    }

    @Throws(Exception::class)
    fun ByteArray.fromUncompressedPoint(params: ECParameterSpec): ECPublicKey {
        val uncompressedPoint = this
        var offset = 0
        require(uncompressedPoint[offset++] == UNCOMPRESSED_POINT_INDICATOR) {
            "Invalid uncompressedPoint encoding, no uncompressed point indicator"
        }
        val bitsPerByte = 8
        val keySizeBytes = ((params.order.bitLength() + bitsPerByte - 1) / bitsPerByte)
        require(uncompressedPoint.size == 1 + 2 * keySizeBytes) {
            "Invalid uncompressedPoint encoding, not the correct size"
        }
        val x = BigInteger(1, uncompressedPoint.copyOfRange(offset, offset + keySizeBytes))
        offset += keySizeBytes
        val y = BigInteger(1, uncompressedPoint.copyOfRange(offset, offset + keySizeBytes))
        val w = ECPoint(x, y)
        val ecPublicKeySpec = ECPublicKeySpec(w, params)
        val keyFactory = KeyFactory.getInstance("EC")
        return keyFactory.generatePublic(ecPublicKeySpec) as ECPublicKey
    }

    fun ECPublicKey.toUncompressedPoint(): ByteArray {
        val publicKey = this
        val bitsPerByte = 8
        val keySizeBytes = ((publicKey.params.order.bitLength() + bitsPerByte - 1) / bitsPerByte)
        val uncompressedPoint = ByteArray(1 + 2 * keySizeBytes)
        var offset = 0
        uncompressedPoint[offset++] = 0x04
        val x = publicKey.w.affineX.toByteArray()
        if (x.size <= keySizeBytes) {
            System.arraycopy(
                x, 0, uncompressedPoint, offset + keySizeBytes
                        - x.size, x.size
            )
        } else if (x.size == keySizeBytes + 1 && x[0].toInt() == 0) {
            System.arraycopy(x, 1, uncompressedPoint, offset, keySizeBytes)
        } else {
            error("x value is too large")
        }
        offset += keySizeBytes
        val y = publicKey.w.affineY.toByteArray()
        if (y.size <= keySizeBytes) {
            System.arraycopy(
                y, 0, uncompressedPoint, offset + keySizeBytes
                        - y.size, y.size
            )
        } else if (y.size == keySizeBytes + 1 && y[0].toInt() == 0) {
            System.arraycopy(y, 1, uncompressedPoint, offset, keySizeBytes)
        } else {
            error("y value is too large")
        }
        return uncompressedPoint
    }
}

// https://stackoverflow.com/questions/35228063/encrypt-message-for-web-push-api-in-java
