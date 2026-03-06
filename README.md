# MoinMelder - R.SH Vereins-Alarm

Der MoinMelder ist ein Bot, der automatisch das Radioprogramm von **R.SH** abhört und eine WhatsApp-Gruppe benachrichtigt, wenn der **Schulverein der TSS Heiligenhafen** bei der Verlosung gezogen wird.

## So funktioniert's

Jeden Morgen um kurz vor 7 Uhr wacht der Bot auf und hört ca. 10 Minuten lang den R.SH Livestream ab. Dabei wird das Gesprochene automatisch per Spracherkennung in Text umgewandelt und nach Schlüsselwörtern wie *Heiligenhafen*, *TSS* und *Schulverein* durchsucht.

Sobald mindestens 2 dieser Wörter in einem Satz erkannt werden, schickt der Bot sofort einen Alarm in die WhatsApp-Gruppe — dann heißt es: Ab in die R.SH App und **VOTEN**!

Falls der Verein an dem Tag nicht gezogen wurde, meldet er das ebenfalls und verabschiedet sich bis zum nächsten Morgen.

Zusätzlich schickt der Bot 2x am Tag (12:00 und 18:00 Uhr) einen kurzen Health-Check, damit man sieht, dass er noch läuft.

## Tech-Stack

- **Scala 3** mit **ZIO 2** (Functional Effect System)
- **Vosk** — Offline-Spracherkennung (deutsches Modell, 1.9 GB)
- **FFmpeg** — Konvertierung des MP3-Radiostreams in 16kHz Mono PCM
- **Playwright** — Browser-Automatisierung für WhatsApp Web
- **Docker** — Xvfb + x11vnc + noVNC für headless Browser mit VNC-Zugang

## Voraussetzungen

- Docker & Docker Compose
- Beim ersten Start: WhatsApp QR-Code scannen über noVNC (http://localhost:6080)

## Starten

```bash
docker compose -f docker/docker-compose.yml up -d --build
```

Nach dem Start http://localhost:6080 im Browser öffnen, den WhatsApp QR-Code scannen — fertig.

## Konfiguration

Die Einstellungen befinden sich in `src/main/resources/application.conf`:

| Parameter | Beschreibung | Default |
|---|---|---|
| `stream-url` | R.SH Livestream URL | `https://streams.rsh.de/rsh-live/mp3-192/streams.rsh.de/` |
| `trigger-words` | Schlüsselwörter für die Erkennung | `heiligenhafen, tss, schulverein` |
| `min-trigger-matches` | Mindestanzahl Treffer pro Satz | `2` |
| `schedule.target-hour/minute` | Zeitpunkt der Verlosung | `7:00` |
| `schedule.window-minutes` | ± Minuten um die Zielzeit | `5` (= 6:55 - 7:05) |
| `whatsapp.group-name` | Name der WhatsApp-Gruppe | `Operation Wildpark` |

## Architektur

```
R.SH Livestream (MP3)
        │
        ▼
    FFmpeg (→ 16kHz PCM)
        │
        ▼
  Vosk Spracherkennung
        │
        ▼
   TriggerDetector (Fuzzy-Matching)
        │
        ▼
  WhatsApp-Benachrichtigung
```

## Lizenz

MIT
