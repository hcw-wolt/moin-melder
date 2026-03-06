#!/bin/bash
# Inspect WhatsApp Web DOM selectors
set -e

cd /app
java -cp "lib/*" com.microsoft.playwright.CLI open \
  --browser chromium \
  --save-storage /tmp/wa-state.json \
  "https://web.whatsapp.com" &

# Wait for page to load, then dump data-testid attributes
sleep 30

# Take screenshot
DISPLAY=:99 timeout 5 bash -c '
cd /app && java -cp "lib/*" com.microsoft.playwright.CLI screenshot \
  --browser chromium \
  --load-storage /tmp/wa-state.json \
  --wait-for-timeout 15000 \
  --full-page \
  "https://web.whatsapp.com" /tmp/wa-loaded.png
' || true

wait
