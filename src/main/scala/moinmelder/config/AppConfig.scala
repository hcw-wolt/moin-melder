package moinmelder.config

import zio.*
import zio.config.*
import zio.config.typesafe.*

final case class ScheduleConfig(
    targetHour: Int,
    targetMinute: Int,
    windowMinutes: Int,
)

final case class WhatsAppConfig(
    groupName: String,
    sessionDir: String,
    headless: Boolean,
)

final case class VoskConfig(
    modelPath: String
)

final case class AppConfig(
    streamUrl: String,
    triggerWords: List[String],
    minTriggerMatches: Int,
    schedule: ScheduleConfig,
    whatsapp: WhatsAppConfig,
    vosk: VoskConfig,
)

object AppConfig:

  private val configDescriptor: Config[AppConfig] =
    val whatsappConfig =
      (Config.string("group-name") ++
        Config.string("session-dir") ++
        Config.boolean("headless")).nested("whatsapp").map { case (groupName, sessionDir, headless) =>
        WhatsAppConfig(groupName, sessionDir, headless)
      }

    val scheduleConfig =
      (Config.int("target-hour") ++
        Config.int("target-minute") ++
        Config.int("window-minutes")).nested("schedule").map { case (hour, minute, window) =>
        ScheduleConfig(hour, minute, window)
      }

    val voskConfig =
      Config.string("model-path").nested("vosk").map(VoskConfig(_))

    (Config.string("stream-url") ++
      Config.listOf(Config.string).nested("trigger-words") ++
      Config.int("min-trigger-matches") ++
      scheduleConfig ++
      whatsappConfig ++
      voskConfig).nested("moin-melder").map { case (streamUrl, triggerWords, minMatches, schedule, whatsapp, vosk) =>
      AppConfig(streamUrl, triggerWords, minMatches, schedule, whatsapp, vosk)
    }

  val layer: ZLayer[Any, Config.Error, AppConfig] =
    ZLayer {
      ZIO.config(configDescriptor).withConfigProvider(ConfigProvider.fromResourcePath())
    }
