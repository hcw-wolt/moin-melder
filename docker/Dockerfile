# ---- Build Stage ----
FROM eclipse-temurin:21-jdk AS builder

# Install sbt
RUN apt-get update && apt-get install -y curl gnupg && \
    echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" | tee /etc/apt/sources.list.d/sbt.list && \
    curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" | apt-key add && \
    apt-get update && apt-get install -y sbt && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /build
COPY build.sbt .
COPY project/ project/
# Pre-fetch dependencies
RUN sbt update

COPY src/ src/
RUN sbt stage

# ---- Runtime Stage ----
FROM eclipse-temurin:21-jre

# Install FFmpeg, VNC/noVNC, Vosk deps, and base utilities
RUN apt-get update && apt-get install -y --no-install-recommends \
    ffmpeg \
    fonts-noto \
    fonts-noto-cjk \
    # Vosk native lib dependency
    libatomic1 \
    # VNC + noVNC for QR code scanning
    x11vnc \
    xvfb \
    novnc \
    websockify \
    # Utilities
    wget \
    unzip \
    && rm -rf /var/lib/apt/lists/*

# Copy application from builder (needed for Playwright CLI)
COPY --from=builder /build/target/universal/stage /app

# Install Playwright Chromium + ALL system dependencies
ENV PLAYWRIGHT_BROWSERS_TO_INSTALL=chromium
RUN cd /app && java -cp "lib/*" com.microsoft.playwright.CLI install --with-deps chromium

# Download Vosk native library for the current architecture
RUN ARCH=$(dpkg --print-architecture) && \
    if [ "$ARCH" = "arm64" ]; then VOSK_ARCH="aarch64"; else VOSK_ARCH="x86_64"; fi && \
    wget -q "https://github.com/alphacep/vosk-api/releases/download/v0.3.45/vosk-linux-${VOSK_ARCH}-0.3.45.zip" \
         -O /tmp/vosk-native.zip && \
    unzip /tmp/vosk-native.zip -d /tmp/vosk-native && \
    cp /tmp/vosk-native/*/libvosk.so /usr/lib/ && \
    ldconfig && \
    rm -rf /tmp/vosk-native*

# Download Vosk German model (large, 1.9GB - much better for broadcast transcription)
RUN mkdir -p /data/vosk-model && \
    wget -q https://alphacephei.com/vosk/models/vosk-model-de-0.21.zip -O /tmp/vosk-model.zip && \
    unzip /tmp/vosk-model.zip -d /tmp/ && \
    mv /tmp/vosk-model-de-0.21/* /data/vosk-model/ && \
    rm -rf /tmp/vosk-model*

# Create directories
RUN mkdir -p /data/whatsapp-session

ENV DISPLAY=:99
# Prevent Playwright from downloading additional browsers at runtime
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1

WORKDIR /app

# Startup script
COPY docker/entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

EXPOSE 6080

ENTRYPOINT ["/entrypoint.sh"]
