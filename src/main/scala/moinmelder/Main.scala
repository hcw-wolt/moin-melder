package moinmelder

import moinmelder.config.{AppConfig, ScheduleConfig}
import moinmelder.notification.WhatsAppNotifier
import moinmelder.stream.RadioStream
import moinmelder.transcription.{TranscriptResult, VoskTranscriber}
import moinmelder.trigger.{TriggerDetector, TriggerEvent}
import zio.*
import zio.stream.*

import java.time.{LocalDate, LocalDateTime, LocalTime, ZoneId}
import java.time.format.DateTimeFormatter

object Main extends ZIOAppDefault:

  private val zone = ZoneId.of("Europe/Berlin")

  override def run: ZIO[Any, Any, Any] =
    val program =
      for
        config <- ZIO.service[AppConfig]
        _      <- ZIO.logInfo("=== Moin Melder - R.SH Vereins-Alarm ===")
        _      <- ZIO.logInfo(s"Trigger-Wörter: ${config.triggerWords.mkString(", ")}")
        _      <- ZIO.logInfo(s"Ziehung: ${config.schedule.targetHour}:%02d".format(config.schedule.targetMinute) +
                    s" Uhr (± ${config.schedule.windowMinutes} Min)")
        _      <- ZIO.logInfo(s"WhatsApp Gruppe: ${config.whatsapp.groupName}")
        _      <- healthCheckLoop.fork
        _      <- dailyLoop(config)
      yield ()

    program.provide(
      AppConfig.layer,
      RadioStream.layer,
      VoskTranscriber.layer,
      TriggerDetector.layer,
      WhatsAppNotifier.layer,
    )

  private def dailyLoop(
      config: AppConfig
  ): ZIO[RadioStream & VoskTranscriber & TriggerDetector & WhatsAppNotifier, Throwable, Nothing] =
    (waitForMonitoringWindow(config.schedule) *>
      runMonitoringSession(config).catchAll { err =>
        ZIO.logError(s"Monitoring-Session fehlgeschlagen: ${err.getMessage}") *>
          ZIO.logError("Warte 2 Minuten vor erneutem Versuch...") *>
          ZIO.sleep(2.minutes)
      }
    ).forever

  private def waitForMonitoringWindow(
      schedule: ScheduleConfig
  ): ZIO[Any, Nothing, Unit] =
    for
      now       <- Clock.currentDateTime.map(_.atZoneSameInstant(zone).toLocalDateTime)
      startTime  = nextMonitoringStart(now, schedule)
      delay      = java.time.Duration.between(now, startTime)
      _         <- ZIO.logInfo(s"Nächstes Monitoring-Fenster: $startTime (in ${formatDuration(delay)})")
      _         <- ZIO.sleep(Duration.fromJava(delay))
    yield ()

  private def nextMonitoringStart(
      now: LocalDateTime,
      schedule: ScheduleConfig,
  ): LocalDateTime =
    val today = now.toLocalDate
    val targetTime = LocalTime.of(schedule.targetHour, schedule.targetMinute)
    val startTime = LocalDateTime.of(today, targetTime).minusMinutes(schedule.windowMinutes.toLong)
    val endTime = LocalDateTime.of(today, targetTime).plusMinutes(schedule.windowMinutes.toLong)
    if now.isBefore(startTime) then startTime
    else if now.isBefore(endTime) then now // Wir sind im Fenster → sofort starten
    else
      // Schon vorbei für heute → morgen
      LocalDateTime.of(today.plusDays(1), targetTime).minusMinutes(schedule.windowMinutes.toLong)

  private def runMonitoringSession(
      config: AppConfig
  ): ZIO[RadioStream & VoskTranscriber & TriggerDetector & WhatsAppNotifier, Throwable, Unit] =
    val windowDuration = (config.schedule.windowMinutes * 2).minutes
    for
      notifier    <- ZIO.service[WhatsAppNotifier]
      radio       <- ZIO.service[RadioStream]
      transcriber <- ZIO.service[VoskTranscriber]
      detector    <- ZIO.service[TriggerDetector]

      // Begrüßung
      _ <- ZIO.logInfo("Monitoring-Fenster gestartet!")
      _ <- notifier.sendGroupMessage(Messages.startMessage(config.schedule)).tapError(logSendError)

      // Debug: collect 60s of final transcriptions and log them
      transcriptBuffer <- Ref.make(List.empty[String])
      debugLogged      <- Ref.make(false)
      taggedStream = transcriber.transcribeTagged(radio.audioStream)
        .tap {
          case TranscriptResult.Final(text) =>
            ZIO.when(text.trim.length > 3) {
              for
                logged <- debugLogged.get
                _      <- ZIO.when(!logged) {
                             transcriptBuffer.update(buf => (buf :+ text).takeRight(50))
                           }
              yield ()
            }
          case _ => ZIO.unit
        }
      transcriptionStream = taggedStream.map(_.text)

      // Start a background fiber to send transcript to WhatsApp after 60s
      _ <- (ZIO.sleep(60.seconds) *> transcriptBuffer.get.flatMap { buf =>
             val transcript = buf.takeRight(30).mkString("\n")
             val msg = s"🔍 *Debug Transkript (60s, ${buf.size} Sätze):*\n\n$transcript"
             ZIO.logInfo(s"=== DEBUG TRANSKRIPT ===\n$transcript") *>
               notifier.sendGroupMessage(msg).tapError(logSendError).ignore *>
               debugLogged.set(true)
           }).fork

      // Pipeline mit Zeitfenster
      triggerRef <- Ref.make(Option.empty[TriggerEvent])
      _ <- detector
             .detect(transcriptionStream)
             .mapZIO { event =>
               triggerRef.set(Some(event)) *>
                 notifier.sendAlert(event).tapError(logSendError).ignore
             }
             .timeout(windowDuration)
             .runDrain

      // Ergebnis auswerten
      trigger <- triggerRef.get
      _ <- trigger match
             case Some(event) =>
               ZIO.logInfo(s"Trigger gefunden: ${event.matchedWords.mkString(", ")}") *>
                 notifier.sendGroupMessage(Messages.successMessage).tapError(logSendError).ignore
             case None =>
               ZIO.logInfo("Kein Trigger im Zeitfenster erkannt.") *>
                 notifier.sendGroupMessage(Messages.noTriggerMessage).tapError(logSendError).ignore

      // Verabschiedung
      _ <- notifier.sendGroupMessage(Messages.goodbyeMessage).tapError(logSendError).ignore
      _ <- ZIO.logInfo("Monitoring-Session beendet. Bis morgen!")
    yield ()

  private val healthCheckTimes = List(LocalTime.of(12, 0), LocalTime.of(18, 0))

  private def healthCheckLoop: ZIO[RadioStream & VoskTranscriber & WhatsAppNotifier, Throwable, Nothing] =
    (waitForNextHealthCheck *>
      runHealthCheck.catchAll { err =>
        ZIO.logError(s"Health-Check fehlgeschlagen: ${err.getMessage}")
      }
    ).forever

  private def waitForNextHealthCheck: ZIO[Any, Nothing, Unit] =
    for
      now      <- Clock.currentDateTime.map(_.atZoneSameInstant(zone).toLocalDateTime)
      nextTime  = healthCheckTimes
                    .map(t => LocalDateTime.of(now.toLocalDate, t))
                    .filter(_.isAfter(now))
                    .minOption
                    .getOrElse(LocalDateTime.of(now.toLocalDate.plusDays(1), healthCheckTimes.head))
      delay     = java.time.Duration.between(now, nextTime)
      _        <- ZIO.logInfo(s"Nächster Health-Check: $nextTime (in ${formatDuration(delay)})")
      _        <- ZIO.sleep(Duration.fromJava(delay))
    yield ()

  private def runHealthCheck: ZIO[RadioStream & VoskTranscriber & WhatsAppNotifier, Throwable, Unit] =
    for
      notifier    <- ZIO.service[WhatsAppNotifier]
      radio       <- ZIO.service[RadioStream]
      transcriber <- ZIO.service[VoskTranscriber]
      _           <- ZIO.logInfo("Health-Check: 30s Radio-Transkription...")
      finals      <- transcriber.transcribeTagged(radio.audioStream)
                       .collect { case TranscriptResult.Final(text) if text.trim.length > 3 => text }
                       .take(30)
                       .timeout(30.seconds)
                       .runCollect
      transcript   = finals.takeRight(15).mkString("\n")
      now         <- Clock.currentDateTime.map(_.atZoneSameInstant(zone).toLocalTime)
      msg          = s"🩺 *Health-Check ($now)*\n\n${finals.size} Sätze erkannt:\n$transcript"
      _           <- notifier.sendGroupMessage(msg).tapError(logSendError).ignore
      _           <- ZIO.logInfo(s"Health-Check gesendet (${finals.size} Sätze)")
    yield ()

  private def logSendError(err: Throwable): UIO[Unit] =
    ZIO.logError(s"WhatsApp-Nachricht fehlgeschlagen: ${err.getMessage}")

  private def formatDuration(d: java.time.Duration): String =
    val hours   = d.toHours
    val minutes = d.toMinutesPart
    val seconds = d.toSecondsPart
    s"${hours}h ${minutes}m ${seconds}s"

private object Messages:

  private val timeFormat = DateTimeFormatter.ofPattern("H:mm")

  def startMessage(schedule: ScheduleConfig): String =
    val target = LocalTime.of(schedule.targetHour, schedule.targetMinute)
    val start  = target.minusMinutes(schedule.windowMinutes.toLong).format(timeFormat)
    val end    = target.plusMinutes(schedule.windowMinutes.toLong).format(timeFormat)
    s"""☀️ *Moin Moin!* ☀️
       |
       |🎙️ Der MoinMelder ist wach und hört jetzt R.SH!
       |
       |⏰ Überwachung von $start bis $end Uhr
       |🔍 Ich lausche nach dem *Schulverein der TSS Heiligenhafen*...
       |
       |🤞 Drückt die Daumen!""".stripMargin

  val successMessage: String =
    s"""✅ *Monitoring abgeschlossen - TREFFER!* ✅
       |
       |Die Alarm-Nachricht wurde bereits gesendet.
       |Jetzt alle ab in die R.SH App und VOTEN! 🗳️""".stripMargin

  val noTriggerMessage: String =
    s"""😔 *Monitoring abgeschlossen - Leider nicht gezogen* 😔
       |
       |Der Schulverein der TSS Heiligenhafen wurde heute nicht genannt.
       |Aber morgen ist ein neuer Tag! 💪""".stripMargin

  val goodbyeMessage: String =
    s"""👋 *Bis morgen!* 👋
       |
       |Der MoinMelder legt sich wieder schlafen. 😴
       |Morgen früh bin ich wieder da und lausche für euch!
       |
       |Moin! 🌅""".stripMargin
