package com.remote.kvm.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Environment
import android.util.Log
import android.view.Surface
import java.io.File

/**
 * H265Encoder — 硬件视频编码器
 *
 * 职责：
 * 1. 创建 MediaCodec 硬件编码器（优先 H.265，不支持则降级 H.264）
 * 2. 提供 Surface 给 VirtualDisplay（屏幕画面写入这里）
 * 3. 持续读取编码后的 H.265 数据
 * 4. Phase 1: 通过 MediaMuxer 写入 MP4 文件
 * 5. Phase 2: 替换为 UDP 网络推流
 *
 * 关键概念：
 * - Surface 模式：不用手动塞原始像素，系统直接通过 GPU 传帧，零拷贝
 * - MediaCodec：Android 硬件编码 API，调用芯片自带的编码器，速度快功耗低
 * - MediaMuxer：把编码后的裸流封装成 MP4 容器格式
 */
class H265Encoder(
    private val width: Int,
    private val height: Int,
    private val bitrate: Int,
    private val frameRate: Int,
    private val iFrameInterval: Int
) {
    companion object {
        const val TAG = "H265Encoder"
    }

    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var running = false
    private var muxer: android.media.MediaMuxer? = null
    private var muxerStarted = false
    private var trackIndex = -1
    private var useHEVC = true

    /** VirtualDisplay 输出到这个 Surface */
    val inputSurface: Surface
        get() = surface ?: throw IllegalStateException("先调用 prepare()")

    /**
     * 第一阶段：创建编码器 + 获取输入 Surface
     *
     * 顺序必须是：configure → createInputSurface → start
     * 不能乱，否则会 crash
     */
    fun prepare() {
        // 检测设备是否支持 H.265
        useHEVC = checkHEVCSupport()
        val mime = if (useHEVC) "video/hevc" else "video/avc"
        // H.265 压缩效率高 40%，不支持的话用更高码率补偿
        val actualBitrate = if (useHEVC) bitrate else (bitrate * 1.5).toInt()

        Log.i(TAG, "编码器: $mime | ${width}x${height} | ${frameRate}fps | ${actualBitrate / 1000} kbps")

        // 配置编码参数
        val format = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, actualBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval)
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface  // Surface 模式
            )
        }

        // 创建编码器
        codec = MediaCodec.createEncoderByType(mime).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }

        // configure 之后、start 之前，创建输入 Surface
        surface = codec!!.createInputSurface()

        // 初始化 MP4 文件输出
        initMuxer()
    }

    /**
     * 启动编码 + 启动输出线程
     */
    fun start() {
        running = true
        codec?.start()
        // 单独线程持续读取编码输出
        Thread({ drainEncoder() }, "encoder-drain").start()
    }

    /**
     * 停止编码器，释放资源
     */
    fun stop() {
        running = false
        // 等输出线程退出
        Thread.sleep(150)
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        try { muxer?.stop() } catch (_: Exception) {}
        try { muxer?.release() } catch (_: Exception) {}
        codec = null
        muxer = null
    }

    /**
     * 持续从编码器读取 H.265 数据，写入 MP4
     *
     * 这是编码器的输出循环，类似生产者-消费者模型：
     * - 编码器是生产者，持续产出 H.265 NALU 数据
     * - 这个循环是消费者，取出数据写入文件
     * - dequeueOutputBuffer 的超时设为 10ms（不阻塞太久）
     */
    private fun drainEncoder() {
        val bufferInfo = MediaCodec.BufferInfo()

        while (running) {
            val index = codec?.dequeueOutputBuffer(bufferInfo, 10_000) ?: break

            when {
                // 编码器输出格式变了（首次会发一次，包含 SPS/PPS）
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val newFormat = codec?.outputFormat
                    Log.i(TAG, "输出格式: $newFormat")
                    trackIndex = muxer?.addTrack(newFormat!!) ?: -1
                    if (trackIndex >= 0) {
                        muxer?.start()
                        muxerStarted = true
                        Log.i(TAG, "Muxer 启动，开始写入 MP4")
                    }
                }

                // 有编码数据输出
                index >= 0 -> {
                    val outputBuffer = codec?.getOutputBuffer(index)

                    // 跳过编解码配置数据（SPS/PPS），Muxer 不需要
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }

                    // 写入 MP4
                    if (bufferInfo.size > 0 && muxerStarted && outputBuffer != null) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        muxer?.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                    }

                    codec?.releaseOutputBuffer(index, false)

                    // 流结束（正常不会到这里，除非 signalEndOfSurface）
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        Log.i(TAG, "编码流结束")
                        break
                    }
                }
            }
        }
    }

    /**
     * 初始化 MP4 输出文件
     * 路径：Movies/RemoteKVM/capture_时间戳.mp4
     */
    private fun initMuxer() {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "RemoteKVM"
        )
        dir.mkdirs()

        val file = File(dir, "capture_${System.currentTimeMillis()}.mp4")

        try {
            muxer = android.media.MediaMuxer(
                file.absolutePath,
                android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )
            Log.i(TAG, "输出文件: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "创建 MP4 文件失败", e)
        }
    }

    /**
     * 检测设备是否支持 H.265 (HEVC) 硬件编码
     *
     * 大多数 2020 年后的手机都支持
     * 如果不支持，自动降级到 H.264（兼容性最好）
     */
    private fun checkHEVCSupport(): Boolean {
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        for (info in codecList.codecInfos) {
            if (!info.isEncoder) continue
            for (type in info.supportedTypes) {
                if (type.equals("video/hevc", ignoreCase = true)) {
                    Log.i(TAG, "找到 HEVC 编码器: ${info.name}")
                    return true
                }
            }
        }
        Log.w(TAG, "⚠ 设备不支持 HEVC，将使用 H.264")
        return false
    }
}
