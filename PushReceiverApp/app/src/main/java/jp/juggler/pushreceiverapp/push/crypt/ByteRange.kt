package jp.juggler.pushreceiverapp.push.crypt

import jp.juggler.util.decodeUTF8
import jp.juggler.util.encodeBase64
import jp.juggler.util.encodeBase64Url
import java.io.ByteArrayOutputStream

/**
 * バイト配列の一部を参照する
 */
class ByteRange(
    val ba: ByteArray,
    val start: Int,
    end: Int,
) {
    companion object {
        val empty = ByteRange(ByteArray(0), 0, 0)
    }

    val size = end - start
    val indices = 0 until size

    fun subRange(start: Int = 0, end: Int = size) =
        ByteRange(ba, start = this.start + start, end = this.start + end)

    operator fun get(pos: Int) = ba[start + pos]

    fun toByteArray() =
        ba.copyOfRange(start, start + size)

    fun elementAtOrNull(pos: Int) =
        if (pos in indices) ba.elementAtOrNull(start + pos) else null

    fun encodeBase64Url(): String =
        toByteArray().encodeBase64Url()

    fun encodeBase64(): String =
        toByteArray().encodeBase64()

    fun decodeUTF8() = toByteArray().decodeUTF8()

    fun copyElements(
        dst: ByteArray,
        dstOffset: Int = 0,
        srcOffset: Int = 0,
        length: Int = size - srcOffset
    ) = System.arraycopy(ba, start + srcOffset, dst, dstOffset, length)
}

fun ByteArray.toByteRange(start: Int = 0, end: Int = size) = ByteRange(this, start, end)

fun ByteArrayOutputStream.write(src: ByteRange, start: Int = 0, len: Int = src.size) =
    write(src.ba, src.start + start, len)

fun ByteArrayOutputStream.toByteRange(start: Int = 0, end: Int = size()) =
    toByteArray().toByteRange(start = start, end = end)
