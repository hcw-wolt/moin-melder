package moinmelder.notification

import com.microsoft.playwright.*
import com.microsoft.playwright.options.AriaRole
import moinmelder.config.AppConfig
import moinmelder.trigger.TriggerEvent
import zio.*

import java.nio.file.Paths
import java.time.format.DateTimeFormatter
import java.time.ZoneId

trait WhatsAppNotifier:
  def sendAlert(event: TriggerEvent): Task[Unit]
  def sendGroupMessage(message: String): Task[Unit]

object WhatsAppNotifier:

  val layer: ZLayer[AppConfig, Throwable, WhatsAppNotifier] =
    ZLayer.scoped {
      for
        config  <- ZIO.service[AppConfig]
        notifier <- make(config)
      yield notifier
    }

  private def make(config: AppConfig): ZIO[Scope, Throwable, WhatsAppNotifier] =
    for
      pw      <- acquirePlaywright
      context <- acquireBrowserContext(pw, config)
      page    <- ZIO.attemptBlocking(context.pages().get(0))
      _       <- navigateToWhatsApp(page)
      lock    <- Semaphore.make(1)
    yield WhatsAppNotifierLive(page, config.whatsapp.groupName, lock)

  private def acquirePlaywright: ZIO[Scope, Throwable, Playwright] =
    ZIO.acquireRelease(
      ZIO.attemptBlocking(Playwright.create())
    )(pw => ZIO.succeed(pw.close()).ignore *> ZIO.logInfo("Playwright closed"))

  private def acquireBrowserContext(pw: Playwright, config: AppConfig): ZIO[Scope, Throwable, BrowserContext] =
    ZIO.acquireRelease(
      ZIO.attemptBlocking {
        val launchOptions = new BrowserType.LaunchPersistentContextOptions()
          .setHeadless(config.whatsapp.headless)
          .setUserAgent("Mozilla/5.0 (X11; Linux aarch64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36")
          .setArgs(java.util.List.of(
            "--disable-blink-features=AutomationControlled",
          ))
        pw.chromium().launchPersistentContext(
          Paths.get(config.whatsapp.sessionDir),
          launchOptions,
        )
      }
    )(ctx => ZIO.succeed(ctx.close()).ignore *> ZIO.logInfo("Browser context closed"))

  private def navigateToWhatsApp(page: Page): ZIO[Any, Throwable, Unit] =
    ZIO.attemptBlocking {
      page.navigate("https://web.whatsapp.com/")
      // Wait for chat list to load (indicates successful login)
      page.waitForSelector(
        "#pane-side, [aria-label='Chatliste']",
        new Page.WaitForSelectorOptions().setTimeout(120_000),
      )
    } *> ZIO.logInfo("WhatsApp Web loaded and logged in")

  def sendAlert(event: TriggerEvent): ZIO[WhatsAppNotifier, Throwable, Unit] =
    ZIO.serviceWithZIO[WhatsAppNotifier](_.sendAlert(event))

  def sendGroupMessage(message: String): ZIO[WhatsAppNotifier, Throwable, Unit] =
    ZIO.serviceWithZIO[WhatsAppNotifier](_.sendGroupMessage(message))

private final class WhatsAppNotifierLive(page: Page, groupName: String, lock: Semaphore) extends WhatsAppNotifier:

  private val formatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.of("Europe/Berlin"))

  override def sendAlert(event: TriggerEvent): Task[Unit] =
    sendGroupMessage(buildMessage(event))

  override def sendGroupMessage(message: String): Task[Unit] =
    lock.withPermit {
      val send =
        for
          _ <- ZIO.logInfo(s"Sending WhatsApp message to group '$groupName'")
          _ <- openGroup
          _ <- sendMessage(message)
          _ <- ZIO.logInfo("WhatsApp message sent successfully")
        yield ()

      send.catchAll { err =>
        ZIO.logWarning(s"WhatsApp send fehlgeschlagen, lade Seite neu: ${err.getMessage}") *>
          reloadPage *>
          send
      }
    }

  private def reloadPage: Task[Unit] =
    ZIO.attemptBlocking {
      page.navigate("https://web.whatsapp.com/")
      page.waitForSelector(
        "#pane-side, [aria-label='Chatliste']",
        new Page.WaitForSelectorOptions().setTimeout(60_000),
      )
    } *> ZIO.logInfo("WhatsApp Web neu geladen")

  private def openGroup: Task[Unit] =
    ZIO.attemptBlocking {
      // Click on search input
      val searchBox = page.locator("[aria-label='Sucheingabefeld']")
      searchBox.click()
      searchBox.fill(groupName)

      // Wait for and click on the group
      val groupEntry = page.locator(s"span[title='$groupName']").first()
      groupEntry.waitFor(new Locator.WaitForOptions().setTimeout(10_000))
      groupEntry.click()

      // Wait for compose box to appear (aria-label includes group name)
      page.locator(s"div[aria-label*='$groupName'][role='textbox'], div[aria-label='Schreib eine Nachricht'][role='textbox']")
        .waitFor(new Locator.WaitForOptions().setTimeout(10_000))
    }

  private def sendMessage(message: String): Task[Unit] =
    ZIO.attemptBlocking {
      val composeBox = page.locator(s"div[aria-label*='$groupName'][role='textbox'], div[aria-label='Schreib eine Nachricht'][role='textbox']")
      // WhatsApp Web uses Shift+Enter for newlines within a message
      val lines = message.split("\n")
      lines.zipWithIndex.foreach { (line, idx) =>
        composeBox.pressSequentially(line, new Locator.PressSequentiallyOptions().setDelay(10))
        if idx < lines.length - 1 then
          composeBox.press("Shift+Enter")
      }
      composeBox.press("Enter")
    }

  private def buildMessage(event: TriggerEvent): String =
    val time = formatter.format(event.timestamp)
    s"""🚨🚨🚨 *ALARM! ALARM! ALARM!* 🚨🚨🚨
       |
       |🎉 Der *Schulverein der TSS Heiligenhafen* wurde gerade bei R.SH gezogen! 🎉
       |
       |⚡ *JETZT SOFORT VOTEN!* ⚡
       |Öffnet die R.SH App und stimmt ab!
       |
       |🏆 Wildpark Eekholt wir kommen! 🦌
       |
       |🕐 Erkannt um $time Uhr
       |📝 Transkription: "${event.transcribedText}"""".stripMargin
