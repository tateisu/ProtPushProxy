package util

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

class BinPackMap : HashMap<Any?, Any?>()

class BinPackList : ArrayList<Any?>()

private enum class ValueType(val id: Int) {
    VNull(0),
    VTrue(1),
    VFalse(2),
    VBytes(3),
    VString(4),
    VList(5),
    VMap(6),
    VDouble(7),
    VFloat(8),

    VInt8(10), // Byte
    VInt16(11),
    VInt32(12),
    VInt64(13),
    VUInt8(14),
    VUInt16(15), // Char

    VUInt32(16),//reserved

    VUInt64(17),//reserved

    ;

    companion object {
        val valuesCache = values()
        val idMap = valuesCache.associateBy { it.id }
    }
}

class BinPackWriter(
    private val out: OutputStream,
) {
    private fun writeVType(t: ValueType) {
        out.write(t.id)
    }

    private fun writeInt64(n: Long) {
        out.write(n/*_____________*/.toInt().and(255))
        out.write(n.shr(8).toInt().and(255))
        out.write(n.shr(16).toInt().and(255))
        out.write(n.shr(24).toInt().and(255))
        out.write(n.shr(32).toInt().and(255))
        out.write(n.shr(40).toInt().and(255))
        out.write(n.shr(48).toInt().and(255))
        out.write(n.shr(56).toInt().and(255))
    }

    private fun writeInt32(n: Int) {
        out.write(n/*____________*/.and(255))
        out.write(n.shr(8).and(255))
        out.write(n.shr(16).and(255))
        out.write(n.shr(24).and(255))
    }

    private fun writeInt16(n: Int) {
        out.write(n/*____________*/.and(255))
        out.write(n.shr(8).and(255))
    }

    private fun writeDouble(n: Double) =
        writeInt64(n.toRawBits())

    private fun writeFloat(n: Float) =
        writeInt32(n.toRawBits())

    private fun writeSize(n: Int) = writeInt32(n)

    fun writeValue(value: Any?) {
        when (value) {
            null -> writeVType(ValueType.VNull)
            true -> writeVType(ValueType.VTrue)
            false -> writeVType(ValueType.VFalse)
            is ByteArray -> {
                writeVType(ValueType.VBytes)
                writeSize(value.size)
                out.write(value)
            }

            is String -> {
                writeVType(ValueType.VString)
                val encoded = value.encodeUTF8()
                writeSize(encoded.size)
                out.write(encoded)
            }

            is Collection<*> -> {
                writeVType(ValueType.VList)
                writeSize(value.size)
                for (x in value) {
                    writeValue(x)
                }
            }

            is Array<*> -> {
                writeVType(ValueType.VList)
                writeSize(value.size)
                for (x in value) {
                    writeValue(x)
                }
            }

            is Map<*, *> -> {
                writeVType(ValueType.VMap)
                val entries = value.entries
                writeSize(entries.size)
                for (entry in value.entries) {
                    writeValue(entry.key)
                    writeValue(entry.value)
                }
            }

            is Double -> {
                writeVType(ValueType.VDouble)
                writeDouble(value)
            }

            is Float -> {
                writeVType(ValueType.VFloat)
                writeFloat(value)
            }

            is Long -> {
                writeVType(ValueType.VInt64)
                writeInt64(value)
            }

            is Int -> {
                writeVType(ValueType.VInt32)
                writeInt32(value)
            }

            is Short -> {
                writeVType(ValueType.VInt16)
                writeInt16(value.toInt())
            }

            is Char -> {
                writeVType(ValueType.VUInt16)
                writeInt16(value.code)
            }

            is Byte -> {
                writeVType(ValueType.VInt8)
                out.write(value.toInt())
            }

            else -> error("unsupported type ${value.javaClass.simpleName}")
        }
    }
}

fun Any?.encodeBinPack(): ByteArray =
    ByteArrayOutputStream().use {
        BinPackWriter(it).writeValue(this)
        it.flush()
        it.toByteArray()
    }

class BinPackReader(
    private val ins: InputStream,
) {

    private fun readUInt8(): Int {
        val n = ins.read().and(255)
        if (n == -1) error("unexpected end")
        return n
    }

    private fun readUInt16(): Int {
        val b0 = readUInt8().and(255)
        val b1 = readUInt8().and(255).shl(8)
        return b0.or(b1)
    }

    private fun readInt32(): Int {
        val b0 = readUInt8().and(255)
        val b1 = readUInt8().and(255).shl(8)
        val b2 = readUInt8().and(255).shl(16)
        val b3 = readUInt8().and(255).shl(24)
        return b0.or(b1).or(b2).or(b3)
    }

    private fun readInt64(): Long {
        val b0 = readUInt8().and(255).toLong()
        val b1 = readUInt8().and(255).toLong().shl(8)
        val b2 = readUInt8().and(255).toLong().shl(16)
        val b3 = readUInt8().and(255).toLong().shl(24)
        val b4 = readUInt8().and(255).toLong().shl(32)
        val b5 = readUInt8().and(255).toLong().shl(40)
        val b6 = readUInt8().and(255).toLong().shl(48)
        val b7 = readUInt8().and(255).toLong().shl(56)
        return b0.or(b1).or(b2).or(b3).or(b4).or(b5).or(b6).or(b7)
    }

    private fun readFloat() = Float.fromBits(readInt32())
    private fun readDouble() = Double.fromBits(readInt64())

    private fun readSize() = readInt32()
    fun readValue(): Any? {
        val id = readUInt8()
        return when (ValueType.idMap[id]) {
            null -> error("unknown type id=$id")
            ValueType.VNull -> null
            ValueType.VTrue -> true
            ValueType.VFalse -> false
            ValueType.VBytes -> ins.readNBytes(readSize())
            ValueType.VString -> ins.readNBytes(readSize()).decodeUTF8()
            ValueType.VList -> BinPackList().apply {
                repeat(readSize()) {
                    add(readValue())
                }
            }

            ValueType.VMap -> BinPackMap().apply {
                repeat(readSize()) {
                    val k = readValue()
                    val v = readValue()
                    put(k, v)
                }
            }

            ValueType.VDouble -> readDouble()
            ValueType.VFloat -> readFloat()
            ValueType.VInt64, ValueType.VUInt64 -> readInt64()
            ValueType.VInt32, ValueType.VUInt32 -> readInt32()
            ValueType.VInt16 -> readUInt16().toShort()
            ValueType.VUInt16 -> readUInt16().toChar()
            ValueType.VUInt8, ValueType.VInt8 -> readUInt8().toByte()
        }
    }
}

@Suppress("unused")
fun ByteArray.decodeBinPack(): Any? =
    ByteArrayInputStream(this).use {
        BinPackReader(it).readValue()
    }
