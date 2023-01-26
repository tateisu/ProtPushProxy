const util = require('util')
const crypto = require('crypto')

function decodeBase64(src){
    return Buffer.from(src,'base64')
}

function dumpBase64(src){
	return Buffer.from(src).toString('base64')
}

// https://developers.google.com/web/updates/2016/03/web-push-encryption
// 

// Simplified HKDF, returning keys up to 32 bytes long
function hkdf(salt, ikm, info, length) {
  if (length > 32) {
    throw new Error('Cannot return keys of more than 32 bytes, ${length} requested');
  }
  console.log(`hkdf salt=${dumpBase64(salt)}`);
  console.log(`hkdf ikm=${dumpBase64(ikm)}`);
  console.log(`hkdf info=${dumpBase64(info)}`);
  console.log(`hkdf length=${length}`);

  // Extract
  const keyHmac = crypto.createHmac('sha256', salt);
  keyHmac.update(ikm);
  const key = keyHmac.digest();

  // Expand
  const infoHmac = crypto.createHmac('sha256', key);
  infoHmac.update(info);
  // A one byte long buffer containing only 0x01
  const ONE_BUFFER = Buffer.alloc(1).fill(1);
  infoHmac.update(ONE_BUFFER);
  const result = infoHmac.digest().slice(0, length);
  console.log(`hkdf result=${dumpBase64(result)}`);
  return result;
}

function createInfo(type, clientPublicKey, serverPublicKey) {
  const len = type.length;

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
  // For the purposes of push encryption the length of the keys will
  // always be 65 bytes.
  const info = Buffer.alloc(18 + len + 1 + 5 + 1 + 2 + 65 + 2 + 65);

  // The string 'Content-Encoding: ', as utf-8
  info.write('Content-Encoding: ');
  // The 'type' of the record, a utf-8 string
  info.write(type, 18);
  // A single null-byte
  info.write('\0', 18 + len);
  // The string 'P-256', declaring the elliptic curve being used
  info.write('P-256', 19 + len);
  // A single null-byte
  info.write('\0', 24 + len);
  // The length of the client's public key as a 16-bit integer
  info.writeUInt16BE(clientPublicKey.length, 25 + len);
  // Now the actual client public key
  clientPublicKey.copy(info, 27 + len);
  // Length of our public key
  info.writeUInt16BE(serverPublicKey.length, 92 + len);
  // The key itself
  serverPublicKey.copy(info, 94 + len);

  return info;
}

/////////////////////////////////////////////

// Authentication secret (auth_secret)
// 購読時に指定する
auth_secret = decodeBase64("BTBZMqHH6r4Tts7J_aSIgg")

// User agent public key (ua_public)
// 購読時に指定する
receiver_public = decodeBase64("BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4")

// User agent private key (ua_private)
receiver_private = decodeBase64("q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94")

// encryption ヘッダから
salt = decodeBase64("pr1_1DFjrzX3RNvJPRngDA")

// Application server public key (as_public)
// crypto-key ヘッダの前半、dh=xxx; の中味
// crypto-key: dh=BLJQjupjQyujhTC--_5xRUgcBfAP_zINsAGTlaDuEME7s9TVgQYsyrzrgbt1vqScmZkoj4BWfPit6EzzaXDW02I;p256ecdsa=BDmWlrZ3gvcv0R7sBhaSp_99FRSC3bBNn9CElRvbcviwYwVPL1Z-G9srAJS6lv_pMe5IkTmKgBWUCNefnN3QoeQ
sender_public = decodeBase64("BLJQjupjQyujhTC--_5xRUgcBfAP_zINsAGTlaDuEME7s9TVgQYsyrzrgbt1vqScmZkoj4BWfPit6EzzaXDW02I")

// 共有秘密鍵を作成する (エンコード時とデコード時で使う鍵が異なる)
receiver_curve = crypto.createECDH('prime256v1')
receiver_curve.setPrivateKey(receiver_private)
sharedSecret = receiver_curve.computeSecret( sender_public )
console.log(`sharedSecret=${dumpBase64(sharedSecret)}`)


const authInfo = Buffer.from('Content-Encoding: auth\0', 'utf8');
const prk = hkdf(auth_secret, sharedSecret, authInfo, 32);
console.log(`prk=${dumpBase64(prk)}`)

// Derive the Content Encryption Key
const contentEncryptionKeyInfo = createInfo('aesgcm', receiver_public, sender_public);
console.log(`contentEncryptionKeyInfo=${dumpBase64(contentEncryptionKeyInfo)}`)

const contentEncryptionKey = hkdf(salt, prk, contentEncryptionKeyInfo, 16);
console.log(`contentEncryptionKey=${dumpBase64(contentEncryptionKey)}`)

// Derive the Nonce
const nonceInfo = createInfo('nonce', receiver_public, sender_public);
console.log(`nonceInfo=${dumpBase64(nonceInfo)}`)

const nonce = hkdf(salt, prk, nonceInfo, 12);
console.log(`nonce=${dumpBase64(nonce)}`)

body = decodeBase64("pTTuh1jT8KJ4zaGwIWjg417KTDzh+eIVe472nMgett3XyhoM5pAz8Yu2RPBXJHE/AojoMA1g+/uzbByu3d1/AygBh99qJ6Xtjya+XBSYoVrNJqT7vq0cKU9bZ8NrEepnaZUc2HjFUDDXNyHi2xBtJnMk/hSZTzyaiCQS2KssGAwixgdK/dTP8Yg+Pul3tgOQvq5CbYFd7iwBQntVv80vO8X+5hyIglA21+6/2fq5lCZSMri5K9/WbSb6erLkxO//A92KjZTnuufE4pUwtIdYW1bFnw5xu6ozjsCsDLbQTSo+JmghOzc/iYx5hG+y5YViC1UXue4eKKlmjbVDRLH6WkEEIKH2cwd4Gf9ewhYwhH7oKKIc4tjvRunq2gtBirQgRYJahgfwykdYA44iyogBc1rFZPGbxr1ph4RxVhdBmIZ+yMN6GQSiDCS+8jKGsc5xnjxrSXXdFva1a2xc1lpiReypZlTTXFmF16Cf+Z6B0UvFTa2AcqEDD0BBlhhbMBoG7n4CRjr5ObE2lG5PBg+gqitx/O1S+X8a4N78L+eK1upEVM+HRQAdCmiqDNJF0/N/VWSMrNCl7HNgnhmYU9Z1aYepiEioz1Tu14UzY/2NOx5z4h4szyJW8s/diAyOhnh+RBRM3QLHtygpLZ3i7o6vVUc=")
console.log(body.toString('hex'))


const decipher = crypto.createCipheriv('id-aes128-GCM', contentEncryptionKey,nonce);
result = decipher.update(body)
console.log(`result=${dumpBase64(result)}`)

// remove padding and GCM auth tag
var pad_length = 0
if( result.length >= 3 && result[2]==0){
	pad_length = 2+ result.readUInt16BE(0)
}
result2 = result.slice(pad_length, result.length-16)
console.log(`result2=${dumpBase64(result2)}`)

console.log(result2.toString('UTF-8'))

/*
result is

{
	"title":"あなたのトゥートが tateisu ð¤¹ さんにお気に入り登録されました"
	,"image":null
	,"badge":"https://mastodon2.juggler.jp/badge.png"
	,"tag":84
	,"timestamp":"2018-05-11T17:06:42.887Z"
	,"icon":"/system/accounts/avatars/000/000/003/original/72f1da33539be11e.jpg"
	,"data":{
		"content":":enemy_bullet:",
		"nsfw":null,
		"url":"https://mastodon2.juggler.jp/web/statuses/98793123081777841",
		"actions":[],
		"access_token":null,
		"message":"%{count} 件の通知",
		"dir":"ltr"
	}
}


*/


const keyCurve = crypto.createECDH('prime256v1');
keyCurve.generateKeys();
const publicKey = keyCurve.getPublicKey();
const privateKey = keyCurve.getPrivateKey();

console.log( "public key="+ dumpBase64(publicKey));
console.log( "private key="+ dumpBase64(privateKey));

const auth = crypto.randomBytes(16)
console.log( "auth="+ dumpBase64(auth));
