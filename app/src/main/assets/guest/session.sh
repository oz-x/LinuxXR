#!/bin/bash
# Starts LinuxXR displays (TigerVNC Xvnc on private Unix sockets) and the agent bridge.
# Usage: session.sh <num_displays> <width>x<height>
set -u
N="${1:-1}"
GEOM="${2:-1920x1080}"
RUN=/run/linuxxr
mkdir -p "$RUN" /tmp/.X11-unix
chmod 1777 /tmp /tmp/.X11-unix
export XDG_RUNTIME_DIR=/tmp/runtime-root
mkdir -p "$XDG_RUNTIME_DIR" && chmod 700 "$XDG_RUNTIME_DIR"
[ -r /etc/profile.d/linuxxr-gpu.sh ] && . /etc/profile.d/linuxxr-gpu.sh

pids=()
cleanup() { kill "${pids[@]}" 2>/dev/null; wait; }
trap cleanup EXIT TERM INT

for i in $(seq 1 "$N"); do
  rm -f "/tmp/.X$i-lock" "/tmp/.X11-unix/X$i" "$RUN/display-$i.sock"
  Xvnc ":$i" -geometry "$GEOM" -depth 24 -dpi 120 \
    -rfbunixpath "$RUN/display-$i.sock" -rfbunixmode 0600 -rfbport -1 \
    -SecurityTypes None -AlwaysShared -AcceptSetDesktopSize -nolisten tcp \
    > "$RUN/xvnc-$i.log" 2>&1 &
  pids+=($!)
  for _ in $(seq 50); do [ -S "/tmp/.X11-unix/X$i" ] && break; sleep 0.2; done
  DISPLAY=":$i" dbus-launch --exit-with-session startxfce4 > "$RUN/desktop-$i.log" 2>&1 &
  pids+=($!)
  echo "[linuxxr] display :$i listo ($RUN/display-$i.sock)"
done

python3 /usr/local/lib/linuxxr/agentd.py > "$RUN/agentd.log" 2>&1 &
pids+=($!)
echo "[linuxxr] agentd en 127.0.0.1:7777 (MCP: /mcp)"
echo "[linuxxr] SESSION_READY"
wait -n "${pids[@]}"
echo "[linuxxr] un proceso de la sesión terminó; deteniendo"
