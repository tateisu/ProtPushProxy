package jp.juggler.pushreceiverapp.push

import android.content.Context
import jp.juggler.pushreceiverapp.api.PushSubscriptionApi
import jp.juggler.pushreceiverapp.db.SavedAccount
import jp.juggler.pushreceiverapp.db.appDatabase
import jp.juggler.util.AdbLog
import jp.juggler.util.cast
import jp.juggler.util.decodeBase64Url
import jp.juggler.util.encodeBase64Url
import org.conscrypt.Conscrypt
import org.spongycastle.jce.ECNamedCurveTable
import org.spongycastle.jce.ECPointUtil
import org.spongycastle.jce.spec.ECNamedCurveSpec
import org.unifiedpush.android.connector.UnifiedPush
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Security
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement

class PushRepo(
    val api: PushSubscriptionApi,
    val accountAccess: SavedAccount.Access,
) {
    companion object {
        private val conscryptProvider by lazy {
            Conscrypt.newProvider().also { Security.addProvider(it) }
        }

        /**
         * 「public key in X.509/SPKI format (i.e. not a raw public key)」 をデコードする
         */
        private fun ByteArray.decodePublicKey1() =
            KeyFactory.getInstance("EC", conscryptProvider)
                .generatePublic(X509EncodedKeySpec(this)) as ECPublicKey

        /**
         * WebPushのp256dh、つまり公開鍵を X9.62 uncompressed format で符号化したバイト列を作る。
         */
        private fun ECPublicKey.encodeP256Dh(): ByteArray {
            val bitsInByte = 8
            val keySizeBytes = (params.order.bitLength() + bitsInByte - 1) / bitsInByte
            return ByteArray(1 + 2 * keySizeBytes).also { uncompressedPoint ->
                var offset = 0
                uncompressedPoint[offset++] = 0x04
                w.affineX.toByteArray().let { x ->
                    when {
                        x.size <= keySizeBytes ->
                            System.arraycopy(
                                x, 0,
                                uncompressedPoint, offset + keySizeBytes - x.size,
                                x.size
                            )

                        x.size == keySizeBytes + 1 && x[0].toInt() == 0 ->
                            System.arraycopy(
                                x, 1,
                                uncompressedPoint, offset,
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
                            uncompressedPoint, offset + keySizeBytes - y.size,
                            y.size
                        )

                        y.size == keySizeBytes + 1 && y[0].toInt() == 0 -> System.arraycopy(
                            y, 1,
                            uncompressedPoint, offset,
                            keySizeBytes
                        )
                        else -> error("y value is too large")
                    }
                }
            }
        }

        private fun generateKeyPair(): KeyPair =
            KeyPairGenerator.getInstance("EC", conscryptProvider)
                .apply {
                    @Suppress("SpellCheckingInspection")
                    initialize(ECGenParameterSpec("secp256r1"))
                }.genKeyPair().also {
                    // 公開鍵の試験
                    AdbLog.i("publicKey=${it.public}")
                    // X.509/SPKI format エンコードとデコード
                    val reloaded = it.public.encoded?.decodePublicKey1()
                    AdbLog.i("reloaded=${reloaded}")
                    // サーバに渡す部分
                    val p256dh = it.public.cast<ECPublicKey>()?.encodeP256Dh()
                    AdbLog.i("p256dh=${p256dh}")
                } ?: error("genKeyPair returns null")
    }

//    @Throws(NoSuchAlgorithmException::class, InvalidKeySpecException::class)
//    private fun getPublicKeyFromBytes(pubKey: ByteArray): PublicKey? {
//        val publicKeyA =
//
//        val spec: ECNamedCurveParameterSpec = ECNamedCurveTable.getParameterSpec("secp256r1")
//        val kf = KeyFactory.getInstance(
//        val params =
//            ECNamedCurveSpec("secp256r1", spec.getCurve(), spec.getG(), spec.getN())
//        val point: ECPoint = ECPointUtil.decodePoint(params.getCurve(), pubKey)
//        val pubKeySpec = ECPublicKeySpec(point, params)
//        return kf.generatePublic(pubKeySpec)
//    }

    suspend fun switchDistributor(context: Context, packageName: String?) {
        // UPの全インスタンスの登録解除
        // ブロードキャストを何もなげておらず、イベントは発生しない
        UnifiedPush.forceRemoveDistributor(context)
        if (packageName.isNullOrBlank()) {
            // アカウントのプッシュ登録情報を解除する
            accountAccess.load().onEach {
                it.pushServerKey = null
                it.pushAuthSecret = null
                it.pushKeyPrivate = null
                it.pushKeyPublic = null
            }.toTypedArray().let {
                accountAccess.update(*it)
            }
        } else {
            // UPは全体で同じdistributorを持つ
            UnifiedPush.saveDistributor(context, packageName)
            // instance別に登録し直す
            accountAccess.load().forEach { a ->
                val instance = a.acct
                UnifiedPush.registerApp(context, instance = instance)
            }
        }
    }

    private suspend fun findAccountFromUpInstance(context: Context,instance: String):SavedAccount{
        val cols = instance.split("@")
        if (cols.size != 2) error("not acct: $instance")
        return context.appDatabase.accountAccess().find(userName = cols[0], apDomain = cols[1])
            ?: error("missing account for $instance")
    }

    /**
     * UnifiedPushのエンドポイントが決まったら呼ばれる
     */
    suspend fun newEndpoint(context: Context, instance: String, endpoint: String) {
        val a = findAccountFromUpInstance(context,instance)

        val keyPair = generateKeyPair()
        AdbLog.i("private=${keyPair.private}, public=${keyPair.public.javaClass.simpleName} ${keyPair.public}")

        val auth = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val p256dh = keyPair.public.cast<ECPublicKey>()?.encodeP256Dh()
            ?: error("not ECPublicKey")

        val response = api.createPushSubscription(
            a = a,
            // REQUIRED String. The endpoint URL that is called when a notification event occurs.
            endpointUrl = endpoint,
            // REQUIRED String. User agent public key.
            // Base64 encoded string of a public key from a ECDH keypair using the prime256v1 curve.
            p256dh = p256dh.encodeBase64Url(),
            // REQUIRED String. Auth secret. Base64 encoded string of 16 bytes of random data.
            auth = auth.encodeBase64Url(),
            // map of alert type to boolean, true to receive for alert type. false? null?
            alerts = PushSubscriptionApi.alertTypes.associateWith { true },
            // whether to receive push notifications from all, followed, follower, or none users.
            policy = "all",
        )
        val serverKeyStr = response.string("server_key")
            ?.replace("""=""".toRegex(), "")
            ?: error("missing server_key. $instance")
        AdbLog.w("server_key=${serverKeyStr}")
        val serverKey = serverKeyStr.decodeBase64Url()!!
        AdbLog.i("p256dh=${p256dh.size}, auth=${auth.size}, serverKey=${serverKey.size}")
        // p256dhは65バイトのはず
        // authは16バイトのはず
        // serverKeyは65バイトのはず

        // 登録できたらアカウントに覚える
        a.pushKeyPrivate = keyPair.private.encoded
        a.pushKeyPublic = keyPair.public.encoded
        a.pushAuthSecret = auth
        a.pushServerKey = serverKey
        accountAccess.save(a)
    }

    suspend fun handleUpMessage(context: Context, instance: String, message: ByteArray) {
        val a = findAccountFromUpInstance(context, instance)

        // Authentication secret (auth_secret)
        // 購読時に指定する
        val authSecret = a.pushAuthSecret
            ?: error("missing pushAuthSecret")

        // User agent public key (ua_public)
        // 購読時に指定する
        val receiverPublic = a.pushKeyPublic?.decodePublicKey1()
            ?: error("missing pushKeyPublic")

        // User agent private key (ua_private)
        val receiverPrivate = a.pushKeyPrivate?.let {
            KeyFactory.getInstance("EC", conscryptProvider)
                .generatePrivate(PKCS8EncodedKeySpec(it))
        } ?: error("missing pushKeyPrivate")

        // サーバの公開鍵
        val serverKey = a.pushServerKey?.let {
            val spec = ECNamedCurveTable.getParameterSpec("prime256v1")
            val params = ECNamedCurveSpec("prime256v1", spec.curve, spec.g, spec.n)
            val pubKeySpec = ECPublicKeySpec(
                ECPointUtil.decodePoint(params.curve, it),
                params
            )
            KeyFactory.getInstance("EC", conscryptProvider)
                .generatePublic(pubKeySpec) as ECPublicKey
        } ?: error("missing pushServerKey")

        AdbLog.i("receiverPrivate $receiverPrivate")
        AdbLog.i("pushServerKey $serverKey")

//        // encryption ヘッダから
//        // salt = decodeBase64("pr1_1DFjrzX3RNvJPRngDA")
//
//        // 共有秘密鍵を作成する (エンコード時とデコード時で使う鍵が異なる)
//        val keyAgreement = KeyAgreement.getInstance("ECDH", conscryptProvider)
//        keyAgreement.init(receiverPrivate)
//        keyAgreement.doPhase(serverKey, true)
//
//        val sharedKeyBytes = keyAgreement.generateSecret()
//        return Base64.encodeToString(sharedKeyBytes, Base64.DEFAULT).replaceAll("\n", "")
//
//        val authInfo = "Content-Encoding: auth\u0000".getBytes(StandardCharsets.UTF_8)
//        val prk = hkdf(authSecret, sharedSecret, authInfo, 32);
//
//        // Derive the Content Encryption Key
//        val contentEncryptionKeyInfo = createInfo('aesgcm', receiver_public, sender_public);
//        val contentEncryptionKey = hkdf(salt, prk, contentEncryptionKeyInfo, 16);
//
//        // Derive the Nonce
//        val nonceInfo = createInfo('nonce', receiver_public, sender_public);
//        val nonce = hkdf(salt, prk, nonceInfo, 12);
//
//        val decipher = crypto.createCipheriv('id-aes128-GCM', contentEncryptionKey, nonce);
//        val result = decipher.update(message)
//
//        // remove padding and GCM auth tag
//        if (result.length >= 3 && result[2] == 0) {
//            var padLength = 2 + result.readUInt16BE(0)
//            result = result.slice(padLength, result.length - 16)
//        } else {
//            result
//        }
//
//        val text = message.toString(StandardCharsets.UTF_8)
//        AdbLog.i("text=$text")
    }
}
