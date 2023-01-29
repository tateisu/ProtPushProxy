package jp.juggler.pushreceiverapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.juggler.util.Base128.decodeBase128
import jp.juggler.util.Base128.encodeBase128
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class Base128Test {

    @Test
    fun useBase128() {
        for (len in 0..20) {
            for (i in 0 until 256) {
                val orig = ByteArrayOutputStream(32)
                    .apply {
                        repeat(len) {
                            write(i)
                        }
                    }.toByteArray()
                val encoded = orig.encodeBase128()
                val decoded = encoded.decodeBase128()
                assertArrayEquals(
                    "len=$len,i=$i",
                    orig,
                    decoded
                )
            }
        }
    }
}
