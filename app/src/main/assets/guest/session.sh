#!/bin/bash
# Starts LinuxXR displays (TigerVNC Xvnc on private Unix sockets) and the agent bridge.
# Usage: session.sh <num_displays> <width>x<height>
set -u
N="${1:-1}"
GEOM="${2:-1920x1080}"
RUN=/run/linuxxr
# Must match LinuxXR's VR transparency key (ImmersiveActivity.ROOT_KEY).
ROOT_KEY="#01FE02"
mkdir -p "$RUN" /tmp/.X11-unix
chmod 1777 /tmp /tmp/.X11-unix
export XDG_RUNTIME_DIR=/tmp/runtime-root
mkdir -p "$XDG_RUNTIME_DIR" && chmod 700 "$XDG_RUNTIME_DIR"
[ -r /etc/profile.d/linuxxr-gpu.sh ] && . /etc/profile.d/linuxxr-gpu.sh

# Agent CLIs (claude, omp) install to ~/.local/bin; terminals start non-login shells.
grep -qs 'HOME/.local/bin' /root/.bashrc || echo 'export PATH="$HOME/.local/bin:$PATH"' >> /root/.bashrc
export PATH="/root/.local/bin:$PATH"

# KDE Plasma settings for LinuxXR (written every start; Plasma keeps everything else).
mkdir -p /root/.config
kwc() { kwriteconfig6 --file "$1" --group "$2" --key "$3" "$4"; }
kwc kwinrc Compositing Enabled false            # software GL compositing would burn the CPU
kwc kwinrc MouseBindings CommandAllKey Alt       # grip in VR = Alt+drag moves windows
kwc ksmserverrc General loginMode emptySession
kwc kscreenlockerrc Daemon Autolock false
kwc kscreenlockerrc Daemon LockOnResume false
kwc kdeglobals KDE LookAndFeelPackage org.kde.breezedark.desktop
dbus-uuidgen --ensure 2>/dev/null || true   # dbus/KDE need a machine-id

# Plasma's desktop (wallpaper) is painted with the transparency key colour so that, in VR, only
# windows and panels float over passthrough. Plasma creates one desktop per monitor, and LinuxXR
# changes the monitor layout (360° ring in VR, panel size otherwise), so re-apply on changes.
KEY_RGB="1,254,2"
plasma_key_watcher() {
  local last=""
  for _ in $(seq 120); do
    dbus-send --session --dest=org.freedesktop.DBus --print-reply /org/freedesktop/DBus \
      org.freedesktop.DBus.NameHasOwner string:org.kde.plasmashell 2>/dev/null | grep -q true && break
    sleep 1
  done
  while true; do
    local now
    now=$(xrandr --listmonitors 2>/dev/null | md5sum)
    if [ "$now" != "$last" ]; then
      sleep 2   # let plasmashell create containments for new monitors
      dbus-send --session --dest=org.kde.plasmashell --type=method_call /PlasmaShell \
        org.kde.PlasmaShell.evaluateScript "string:desktops().forEach(function(d){d.wallpaperPlugin='org.kde.color';d.currentConfigGroup=['Wallpaper','org.kde.color','General'];d.writeConfig('Color','$KEY_RGB');d.reloadConfig();})" \
        && last="$now"
      # Breeze Dark once, after plasmashell has its desktops (earlier it is silently ignored).
      if [ -n "$last" ] && [ ! -e /var/lib/linuxxr/breezedark-applied-v2 ]; then
        plasma-apply-lookandfeel -a org.kde.breezedark.desktop >/dev/null 2>&1 && touch /var/lib/linuxxr/breezedark-applied-v2
      fi
    fi
    sleep 3
  done
}
pids=()
cleanup() { kill "${pids[@]}" 2>/dev/null; wait; }
trap cleanup EXIT TERM INT

for i in $(seq 1 "$N"); do
  rm -f "/tmp/.X$i-lock" "/tmp/.X11-unix/X$i" "$RUN/display-$i.sock"
  # -nolock: X lock files use link(2), which Android forbids for apps.
  Xvnc ":$i" -geometry "$GEOM" -depth 24 -dpi 120 \
    -rfbunixpath "$RUN/display-$i.sock" -rfbunixmode 0600 -rfbport -1 \
    -SecurityTypes None -AlwaysShared -AcceptSetDesktopSize -nolisten tcp \
    -nolock \
    > "$RUN/xvnc-$i.log" 2>&1 &
  pids+=($!)
  for _ in $(seq 50); do [ -S "/tmp/.X11-unix/X$i" ] && break; sleep 0.2; done
  # Session bus on a filesystem socket (dbus-launch's abstract socket is not reachable under PRoot).
  bus="unix:path=$RUN/bus-$i"
  rm -f "$RUN/bus-$i"
  dbus-daemon --session --nofork --nopidfile --address="$bus" > "$RUN/dbus-$i.log" 2>&1 &
  pids+=($!)
  for _ in $(seq 50); do [ -S "$RUN/bus-$i" ] && break; sleep 0.1; done
  (
    export DISPLAY=":$i" DBUS_SESSION_BUS_ADDRESS="$bus"
    xsetroot -solid "$ROOT_KEY"
    plasma_key_watcher &
    exec startplasma-x11
  ) > "$RUN/desktop-$i.log" 2>&1 &
  pids+=($!)
  echo "[linuxxr] display :$i listo ($RUN/display-$i.sock)"
done

python3 /usr/local/lib/linuxxr/agentd.py > "$RUN/agentd.log" 2>&1 &
pids+=($!)
echo "[linuxxr] agentd en 127.0.0.1:7777 (MCP: /mcp)"
echo "[linuxxr] SESSION_READY"
wait -n "${pids[@]}"
echo "[linuxxr] un proceso de la sesión terminó; deteniendo"
