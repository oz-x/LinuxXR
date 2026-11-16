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
grep -qs '.opencode/bin' /root/.bashrc || echo 'export PATH="$HOME/.opencode/bin:$PATH"' >> /root/.bashrc
export PATH="/root/.opencode/bin:/root/.local/bin:$PATH"

# Dark GTK theme and icons (XFCE, terminal, Thunar).
mkdir -p /root/.config/gtk-3.0
printf '[Settings]\ngtk-theme-name=Adwaita-dark\ngtk-icon-theme-name=Papirus-Dark\ngtk-application-prefer-dark-theme=1\n' \
  > /root/.config/gtk-3.0/settings.ini
dbus-uuidgen --ensure 2>/dev/null || true   # dbus needs a machine-id

# The root window is painted with the VR transparency key, but Xvnc resets it to black when
# the screen is resized (VR ring / panel size), so repaint it after every layout change.
root_key_watcher() {
  local last=""
  while true; do
    local now
    now=$(xrandr --listmonitors 2>/dev/null | md5sum)
    if [ "$now" != "$last" ]; then
      xsetroot -solid "$ROOT_KEY" && last="$now"
    fi
    sleep 2
  done
}

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
    root_key_watcher &
    xfconf-query -c xsettings -p /Net/ThemeName -n -t string -s Adwaita-dark 2>/dev/null
    xfconf-query -c xsettings -p /Net/IconThemeName -n -t string -s Papirus-Dark 2>/dev/null
    xfconf-query -c xfwm4 -p /general/theme -n -t string -s Default 2>/dev/null
    # LinuxXR changes the monitor layout (VR ring / panel size): no display dialog.
    xfconf-query -c displays -p /Notify -n -t int -s 0 2>/dev/null
    xfconf-query -c displays -p /AutoEnableProfiles -n -t bool -s false 2>/dev/null
    xfsettingsd --replace &
    # No compositor: it would paint the root black and hide the transparency key.
    xfwm4 --replace --compositor=off &
    # No xfdesktop on purpose: in VR only windows and the panel float over passthrough.
    exec xfce4-panel
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
