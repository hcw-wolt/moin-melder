package moinmelder.trigger

import moinmelder.config.AppConfig
import zio.*
import zio.stream.*

import java.time.Instant

final case class TriggerEvent(
    timestamp: Instant,
    transcribedText: String,
    matchedWords: List[String],
)

trait TriggerDetector:
  def detect(texts: ZStream[Any, Throwable, String]): ZStream[Any, Throwable, TriggerEvent]

object TriggerDetector:

  val layer: ZLayer[AppConfig, Nothing, TriggerDetector] =
    ZLayer.derive[TriggerDetectorLive]

  def detect(texts: ZStream[Any, Throwable, String]): ZStream[TriggerDetector, Throwable, TriggerEvent] =
    ZStream.serviceWithStream[TriggerDetector](_.detect(texts))

private final class TriggerDetectorLive(config: AppConfig) extends TriggerDetector:

  private val debounceDuration = 5.minutes
  private val maxLevenshteinDistance = 2

  override def detect(texts: ZStream[Any, Throwable, String]): ZStream[Any, Throwable, TriggerEvent] =
    ZStream.unwrap {
      for lastTrigger <- Ref.make(Option.empty[Instant])
      yield texts
        .mapZIO { text =>
          checkTrigger(text, lastTrigger)
        }
        .collect { case Some(event) => event }
        .tap(event => ZIO.logWarning(s"TRIGGER DETECTED: ${event.matchedWords.mkString(", ")} in '${event.transcribedText}'"))
    }

  private def checkTrigger(text: String, lastTrigger: Ref[Option[Instant]]): UIO[Option[TriggerEvent]] =
    for
      now     <- Clock.instant
      last    <- lastTrigger.get
      debounced = last.exists(t => java.time.Duration.between(t, now).toMinutes < debounceDuration.toMinutes)
      result  <- if debounced then ZIO.succeed(None)
                 else
                   val normalized = normalize(text)
                   val matched    = findMatches(normalized)
                   if matched.size >= config.minTriggerMatches then
                     lastTrigger.set(Some(now)).as(Some(TriggerEvent(now, text, matched)))
                   else
                     ZIO.succeed(None)
    yield result

  private def findMatches(normalizedText: String): List[String] =
    val words = normalizedText.split("\\s+").toList
    config.triggerWords.filter { trigger =>
      words.exists(word => fuzzyMatch(word, trigger))
    }

  private def fuzzyMatch(word: String, trigger: String): Boolean =
    word == trigger ||
      word.contains(trigger) ||
      (word.length >= 4 && trigger.length >= 4 && levenshtein(word, trigger) <= maxAllowedDistance(trigger))

  private def normalize(text: String): String =
    text.toLowerCase
      .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue")
      .replace("ß", "ss")
      // Also keep original umlauts for direct matching
      .pipe(normalized =>
        s"${text.toLowerCase} $normalized"
      )

  // Scale allowed distance by trigger word length
  private def maxAllowedDistance(trigger: String): Int =
    if trigger.length <= 5 then 1
    else if trigger.length <= 8 then 2
    else 3

  private def levenshtein(s1: String, s2: String): Int =
    val dist = Array.tabulate(s1.length + 1, s2.length + 1) { (i, j) =>
      if i == 0 then j
      else if j == 0 then i
      else 0
    }
    for
      i <- 1 to s1.length
      j <- 1 to s2.length
    do
      val cost = if s1(i - 1) == s2(j - 1) then 0 else 1
      dist(i)(j) = Math.min(
        Math.min(dist(i - 1)(j) + 1, dist(i)(j - 1) + 1),
        dist(i - 1)(j - 1) + cost,
      )
    dist(s1.length)(s2.length)

  extension [A](a: A)
    private def pipe[B](f: A => B): B = f(a)
