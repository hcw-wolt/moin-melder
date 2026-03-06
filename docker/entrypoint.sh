#!/bin/bash
set -e

echo "=== Moin Melder Container Starting ==="

# Clean up stale files from previous runs
rm -f /data/whatsapp-session/SingletonLock /data/whatsapp-session/SingletonCookie /data/whatsapp-session/SingletonSocket
rm -f /tmp/.X99-lock

# Kill any leftover processes from previous runs
pkill -f Xvfb || true
pkill -f x11vnc || true
pkill -f websockify || true
sleep 1

# Start virtual framebuffer
echo "Starting Xvfb..."
Xvfb :99 -screen 0 1280x720x24 &
sleep 3

# Start VNC server
echo "Starting x11vnc..."
x11vnc -display :99 -forever -nopw -shared -rfbport 5900 &
sleep 1

# Start noVNC web client (accessible at http://localhost:6080)
echo "Starting noVNC on port 6080..."
ln -sf /usr/share/novnc/vnc.html /usr/share/novnc/index.html
websockify --web=/usr/share/novnc/ 6080 localhost:5900 &
sleep 1

echo "=== noVNC available at http://localhost:6080 ==="
echo "=== Use this to scan WhatsApp QR code ==="

# Run the application
exec /app/bin/moin-melder "$@"
