package util

import com.aayushatharva.brotli4j.Brotli4jLoader
import com.aayushatharva.brotli4j.decoder.Decoder
import com.aayushatharva.brotli4j.decoder.DecoderJNI
import com.aayushatharva.brotli4j.encoder.Encoder

object BrotliUtils {
    // Load the native library
    private val timeInitialized by lazy{
        Brotli4jLoader.ensureAvailability()
        System.currentTimeMillis()
    }
    fun requireInitialized() = timeInitialized

    // Compress data and get output in byte array
    fun ByteArray.compressBrotli():ByteArray {
        requireInitialized()
        return Encoder.compress(this)
    }

    fun ByteArray.decompressBrotli():ByteArray {
        val directDecompress = Decoder.decompress(this)
        when (val status = directDecompress.resultStatus) {
            DecoderJNI.Status.DONE -> return directDecompress.decompressedData
            else -> error("decompressBrotli failed. status=$status")
        }
    }
}