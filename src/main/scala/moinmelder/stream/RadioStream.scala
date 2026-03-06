package moinmelder.stream

import moinmelder.config.AppConfig
import zio.*
import zio.stream.*

import java.io.InputStream

trait RadioStream:
  def audioStream: ZStream[Any, Throwable, Byte]

object RadioStream:

  val layer: ZLayer[AppConfig, Nothing, RadioStream] =
    ZLayer.fromFunction { (config: AppConfig) =>
      RadioStreamLive(config)
    }

  def audioStream: ZStream[RadioStream, Throwable, Byte] =
    ZStream.serviceWithStream[RadioStream](_.audioStream)

private final class RadioStreamLive(config: AppConfig) extends RadioStream:

  private val chunkSize = 4096 // ~128ms audio at 16kHz/16bit/mono

  private val ffmpegCommand = List(
    "ffmpeg",
    "-i", config.streamUrl,
    "-ar", "16000",    // 16 kHz sample rate
    "-ac", "1",        // mono
    "-f", "s16le",     // signed 16-bit little-endian PCM
    "-acodec", "pcm_s16le",
    "-loglevel", "error",
    "pipe:1",
  )

  override def audioStream: ZStream[Any, Throwable, Byte] =
    ZStream.unwrapScoped {
      for
        process <- startFFmpeg
        _       <- ZIO.logInfo(s"FFmpeg process started, streaming from ${config.streamUrl}")
      yield readProcessOutput(process)
    }.retry(Schedule.exponential(5.seconds) && Schedule.recurs(10))

  private def startFFmpeg: ZIO[Scope, Throwable, Process] =
    ZIO.acquireRelease {
      ZIO.attemptBlocking {
        val pb = new ProcessBuilder(ffmpegCommand*)
          .redirectError(ProcessBuilder.Redirect.INHERIT)
        pb.start()
      }
    } { process =>
      ZIO.succeed {
        process.destroyForcibly()
        process.waitFor()
      }.ignore *> ZIO.logInfo("FFmpeg process terminated")
    }

  private def readProcessOutput(process: Process): ZStream[Any, Throwable, Byte] =
    ZStream
      .fromInputStream(process.getInputStream, chunkSize)
      .tapError(err => ZIO.logError(s"Error reading FFmpeg output: ${err.getMessage}"))
