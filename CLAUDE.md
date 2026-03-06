# MoinMelder - Projektrichtlinien

## Sprache & Syntax

- **Scala 3.3.x** mit Einrückungs-basierter Syntax (keine geschweiften Klammern)
- `if-then-else` ohne Klammern, `for-yield` Comprehensions
- `enum` statt `sealed trait` + `case class`
- `given`/`using` statt `implicit`
- `extension` Methods statt implicit classes
- `derives` Klausel für Typeclass-Derivation: `case class Foo(x: Int) derives JsonDecoder`
- Trailing Commas in Parameterlisten
- Intersection Types mit `&`: `ZIO[ServiceA & ServiceB, Throwable, Unit]`
- Varargs-Forwarding mit `*`: `List(args*)`

## ZIO Patterns

### Grundregeln

- **ZIO 2.1.x** — keine ZIO 1 Patterns verwenden
- Effekte sind immer `ZIO[R, E, A]`, nie `Future` oder `Try`
- Blockierende Operationen immer in `ZIO.attemptBlocking { ... }` wrappen
- Logging über `ZIO.logInfo/logWarning/logError`, nicht println oder externe Logger

### Service Pattern

Jeder Service folgt dem gleichen Aufbau:

```scala
// 1. Trait mit Methoden
trait MyService:
  def doSomething: ZIO[Any, Throwable, Unit]

// 2. Companion mit Layer und Accessor-Methoden
object MyService:
  val layer: ZLayer[Dependencies, Throwable, MyService] =
    ZLayer.derive[MyServiceLive]

  def doSomething: ZIO[MyService, Throwable, Unit] =
    ZIO.serviceWithZIO[MyService](_.doSomething)

// 3. Private Implementierung
private final class MyServiceLive(dep: Dep) extends MyService:
  override def doSomething: ZIO[Any, Throwable, Unit] = ...
```

- **`ZLayer.derive[Impl]`** bevorzugen — leitet den Layer automatisch aus dem Konstruktor ab
- Accessor-Methoden im Companion nutzen `ZIO.serviceWithZIO` (Effekte) oder `ZStream.serviceWithStream` (Streams)
- Implementierung ist immer `private final class`
- Layer-Komposition in `Main.run` via `.provide(...)`

### Resource Management

Wenn ein Service Ressourcen mit Lifecycle verwaltet (z.B. Vosk Model, Playwright Browser), kann `ZLayer.derive` **nicht** verwendet werden. Stattdessen:

```scala
val layer: ZLayer[Deps, Throwable, MyService] =
  ZLayer.scoped {
    for
      dep      <- ZIO.service[Dep]
      resource <- ZIO.acquireRelease(
                    ZIO.attemptBlocking(new Resource())
                  )(r => ZIO.succeed(r.close()))
    yield MyServiceLive(resource)
  }
```

- `ZLayer.derive` → einfache Constructor-Injection (RadioStream, TriggerDetector)
- `ZLayer.scoped` + `ZIO.acquireRelease` → Ressourcen mit Cleanup (VoskTranscriber, WhatsAppNotifier)
- Cleanup in `acquireRelease` darf nicht feilen: `ZIO.succeed(resource.close())`

### ZStream

- Audio-Verarbeitung läuft über `ZStream[Any, Throwable, Byte]`
- `rechunk(size)` für feste Chunk-Größen (z.B. 4096 für Vosk)
- `ZStream.unwrapScoped` wenn der Stream eine scoped Ressource braucht
- Stream-Timeout mit `.timeout(duration)`
- Ergebnisse sammeln: `.runCollect` (endlich) oder `.runDrain` (nur Seiteneffekte)
- Filtern mit `.collect { case Pattern => value }`

### Shared State

- `Ref.make[A]` für geteilten Zustand zwischen Fibern
- Atomare Updates mit `ref.update(...)`, Lesen mit `ref.get`
- Kein `var`, kein `AtomicReference` — immer `Ref`

### Error Handling

- Nicht-kritische Fehler: `.tapError(logFn).ignore`
- Kritische Fehler propagieren lassen (kein `.catchAll` ohne Grund)
- Fehler-Logging Funktionen geben `UIO[Unit]` zurück

### Concurrency

- Background Tasks mit `.fork` starten
- Endlosschleifen mit `.forever` (Typ ist `Nothing`)
- Zeitbasiertes Warten mit `ZIO.sleep(duration)`
- Retry mit `Schedule`: z.B. `Schedule.exponential(5.seconds) && Schedule.recurs(10)`

## Projektstruktur

```
src/main/scala/moinmelder/
├── Main.scala                    # Einstiegspunkt, ZIOAppDefault
├── config/AppConfig.scala        # Konfiguration (zio-config + HOCON)
├── stream/RadioStream.scala      # FFmpeg Audio-Stream
├── transcription/VoskTranscriber.scala  # Spracherkennung
├── trigger/TriggerDetector.scala  # Schlüsselwort-Erkennung
└── notification/WhatsAppNotifier.scala  # WhatsApp via Playwright
```

## Dependencies

| Library | Zweck |
|---|---|
| `zio` / `zio-streams` | Effect System & Streaming |
| `zio-json` | JSON Parsing (Vosk Ergebnisse) |
| `zio-logging` | Strukturiertes Logging |
| `zio-config` / `zio-config-typesafe` | HOCON Konfiguration |
| `vosk` | Offline-Spracherkennung |
| `playwright` | Browser-Automatisierung (WhatsApp) |

## Build & Run

```bash
# Lokal kompilieren
sbt compile

# Docker Build & Start
docker compose -f docker/docker-compose.yml up -d --build

# Logs
docker compose -f docker/docker-compose.yml logs -f
```
