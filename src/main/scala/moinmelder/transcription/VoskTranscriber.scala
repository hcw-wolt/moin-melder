package moinmelder.transcription

import moinmelder.config.AppConfig
import org.vosk.{LogLevel, Model, Recognizer}
import zio.*
import zio.json.*
import zio.stream.*

enum TranscriptResult:
  case Final(value: String)
  case Partial(value: String)

  def text: String = this match
    case Final(t)   => t
    case Partial(t) => t

trait VoskTranscriber:
  def transcribeTagged(audio: ZStream[Any, Throwable, Byte]): ZStream[Any, Throwable, TranscriptResult]

  def transcribe(audio: ZStream[Any, Throwable, Byte]): ZStream[Any, Throwable, String] =
    transcribeTagged(audio).map(_.text)

object VoskTranscriber:

  val layer: ZLayer[AppConfig, Throwable, VoskTranscriber] =
    ZLayer.scoped {
      for
        config     <- ZIO.service[AppConfig]
        model      <- acquireModel(config.vosk.modelPath)
        _          <- ZIO.logInfo(s"Vosk model loaded from ${config.vosk.modelPath}")
      yield VoskTranscriberLive(model)
    }

  private def acquireModel(modelPath: String): ZIO[Scope, Throwable, Model] =
    ZIO.acquireRelease {
      ZIO.attemptBlocking {
        org.vosk.LibVosk.setLogLevel(LogLevel.WARNINGS)
        new Model(modelPath)
      }
    } { model =>
      ZIO.succeed(model.close()) *> ZIO.logInfo("Vosk model closed")
    }

  def transcribe(audio: ZStream[Any, Throwable, Byte]): ZStream[VoskTranscriber, Throwable, String] =
    ZStream.serviceWithStream[VoskTranscriber](_.transcribe(audio))

  def transcribeTagged(audio: ZStream[Any, Throwable, Byte]): ZStream[VoskTranscriber, Throwable, TranscriptResult] =
    ZStream.serviceWithStream[VoskTranscriber](_.transcribeTagged(audio))

private final case class VoskResult(text: String)
private object VoskResult:
  given JsonDecoder[VoskResult] = DeriveJsonDecoder.gen[VoskResult]

private final case class VoskPartialResult(partial: String)
private object VoskPartialResult:
  given JsonDecoder[VoskPartialResult] = DeriveJsonDecoder.gen[VoskPartialResult]

private final class VoskTranscriberLive(model: Model) extends VoskTranscriber:

  private val sampleRate = 16000.0f
  private val chunkSize  = 4096

  override def transcribeTagged(audio: ZStream[Any, Throwable, Byte]): ZStream[Any, Throwable, TranscriptResult] =
    ZStream.unwrapScoped {
      for recognizer <- acquireRecognizer
      yield processAudio(recognizer, audio)
    }

  private def acquireRecognizer: ZIO[Scope, Throwable, Recognizer] =
    ZIO.acquireRelease {
      ZIO.attemptBlocking(new Recognizer(model, sampleRate))
    } { recognizer =>
      ZIO.succeed(recognizer.close())
    }

  private def processAudio(
      recognizer: Recognizer,
      audio: ZStream[Any, Throwable, Byte],
  ): ZStream[Any, Throwable, TranscriptResult] =
    audio
      .rechunk(chunkSize)
      .chunks
      .mapZIO { chunk =>
        ZIO.attemptBlocking {
          val bytes    = chunk.toArray
          val isFinal  = recognizer.acceptWaveForm(bytes, bytes.length)
          if isFinal then
            val json = recognizer.getResult()
            json.fromJson[VoskResult].toOption.map(r => TranscriptResult.Final(r.text)).filter(_.text.nonEmpty)
          else
            val json = recognizer.getPartialResult()
            json.fromJson[VoskPartialResult].toOption.map(r => TranscriptResult.Partial(r.partial)).filter(_.text.nonEmpty)
        }
      }
      .collect { case Some(result) => result }
