package com.remote.kvm.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import java.io.ByteArrayOutputStream

class H265Encoder(
    private val width: Int,
    private val height: Int,
    private val bitrate: Int,
    private val frameRate: Int,
    private val iFrameInterval: Int
) {
    companion object {
        const val TAG = "H265Encoder"
        const val MIME_TYPE = "video/hevc"
    }

    private var codec: MediaCodec? = null
    private val spsData = ByteArrayOutputStream()
    private val ppsData = ByteArrayOutputStream()
    private var spsReady = false
    private var frameCount = 0

    var onSpsAvailable: ((ByteArray) -> Unit)? = null
    var onPpsAvailable: ((ByteArray) -> Unit)? = null
    var onFrameAvailable: ((Long, ByteArray, Boolean) -> Unit)? = null

    fun prepare() {
        val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
            setInteger(MediaFormat.KEY_BITRATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval)
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
        }

        codec = MediaCodec.createEncoderByType(MIME_TYPE).also {
            it.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }

        Log.i(TAG, "编码器已准备: ${width}x${height} @ ${bitrate / 1000000}Mbps")
    }

    fun start() {
        codec?.start()

        // 单独线程读取编码输出
        Thread {
            val bufferInfo = MediaCodec.BufferInfo()
            while (codec != null) {
                try {
                    val index = codec!!.dequeueOutputBuffer(bufferInfo, 10000)
                    if (index >= 0) {
                        val outputBuffer = codec!!.getOutputBuffer(index)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            val data = ByteArray(bufferInfo.size)
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            outputBuffer.get(data)
                            processEncodedData(data, bufferInfo)
                        }
                        codec!!.releaseOutputBuffer(index, false)
                    }
                } catch (e: Exception) {
                    break
                }
            }
        }.start()

        Log.i(TAG, "编码器已启动")
    }

    private fun processEncodedData(data: ByteArray, bufferInfo: MediaCodec.BufferInfo) {
        val isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
        val presentationTimeUs = bufferInfo.presentationTimeUs

        if (isKeyFrame) {
            // 关键帧包含 SPS/PPS，解析出来发给解码端
            parseKeyFrame(data)
        }

        onFrameAvailable?.invoke(presentationTimeUs, data, isKeyFrame)
    }

    private fun parseKeyFrame(data: ByteArray) {
        // H265 NALU 解析：找 SPS (0x40) 和 PPS (0x42)
        var i = 0
        while (i < data.size - 4) {
            // 查找起始码 00 00 00 01 或 00 00 01
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                val startCodeLen = if (data[i + 2] == 1.toByte()) 3 else 4
                val nalHeader = data[i + startCodeLen].toInt() and 0x7E
                val nalType = nalHeader shr 1

                var nextNalu = data.size
                for (j in i + startCodeLen + 1 until data.size - 3) {
                    if (data[j] == 0.toByte() && data[j + 1] == 0.toByte() &&
                        (data[j + 2] == 1.toByte() || (j + 3 < data.size && data[j + 2] == 0.toByte() && data[j + 3] == 1.toByte()))
                    ) {
                        nextNalu = j
                        break
                    }
                }

                val naluData = data.copyOfRange(i + startCodeLen, nextNalu)

                when (nalType) {
                    33 -> { // SPS
                        spsData.reset()
                        spsData.write(naluData)
                        spsReady = true
                        onSpsAvailable?.invoke(naluData)
                        Log.i(TAG, "SPS 发送: ${naluData.size} bytes")
                    }
                    34 -> { // PPS
                        ppsData.reset()
                        ppsData.write(naluData)
                        onPpsAvailable?.invoke(naluData)
                        Log.i(TAG, "PPS 发送: ${naluData.size} bytes")
                    }
                }

                i = nextNalu
            } else {
                i++
            }
        }
    }

    fun stop() {
        try {
            codec?.stop()
            codec?.release()
        } catch (_: Exception) {}
        codec = null
        Log.i(TAG, "编码器已停止")
    }

    fun getInputSurface(): android.view.Surface? {
        return codec?.createInputSurface()
    }
}
