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
# bwrap cannot work under PRoot; point every caller at LinuxXR's pass-through shim and keep
# pacman from restoring the real binary on upgrades.
ln -sf /usr/local/bin/bwrap /usr/bin/bwrap
grep -q '^NoExtract.*usr/bin/bwrap' /etc/pacman.conf || sed -i '/^\[options\]/a NoExtract = usr/bin/bwrap' /etc/pacman.conf
[ -r /etc/profile.d/linuxxr-gpu.sh ] && . /etc/profile.d/linuxxr-gpu.sh

# Agent CLIs (claude, omp) install to ~/.local/bin; terminals start non-login shells.
grep -qs 'HOME/.local/bin' /root/.bashrc || echo 'export PATH="$HOME/.local/bin:$PATH"' >> /root/.bashrc
export PATH="/root/.local/bin:$PATH"
# Breeze Dark for KDE apps. Without Plasma nothing expands the colour scheme name, so the
# scheme itself goes into kdeglobals (rewritten each start; cheap and self-healing).
if [ -r /usr/share/color-schemes/BreezeDark.colors ]; then
  { cat /usr/share/color-schemes/BreezeDark.colors
    printf '\n[General]\nColorScheme=BreezeDark\n[Icons]\nTheme=breeze-dark\n[KDE]\nLookAndFeelPackage=org.kde.breezedark.desktop\n'
  } > /root/.config/kdeglobals
fi
# Qt/KDE apps take the KDE platform theme (plasma-integration) and Breeze style everywhere:
# panel launchers, terminals and agent-started apps alike.
export QT_QPA_PLATFORMTHEME=kde QT_STYLE_OVERRIDE=Breeze XDG_CURRENT_DESKTOP=KDE KDE_SESSION_VERSION=6
printf 'QT_QPA_PLATFORMTHEME=kde\nQT_STYLE_OVERRIDE=Breeze\nXDG_CURRENT_DESKTOP=KDE\nKDE_SESSION_VERSION=6\n' > /etc/environment
dbus-uuidgen --ensure 2>/dev/null || true   # xfconf/dbus need a machine-id
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
  # No desktop (xfdesktop) on purpose: the bare root window is painted with the transparency
  # key colour, so in VR only the windows float over passthrough. Panel = taskbar/launcher.
  (
    export DISPLAY=":$i" DBUS_SESSION_BUS_ADDRESS="$bus"
    xsetroot -solid "$ROOT_KEY"
    # Breeze Dark for GTK/xfwm4 widgets, KDE (Qt) apps follow kdeglobals.
    xfconf-query -c xsettings -p /Net/ThemeName -n -t string -s Breeze-Dark 2>/dev/null
    xfconf-query -c xsettings -p /Net/IconThemeName -n -t string -s breeze-dark 2>/dev/null
    xfconf-query -c xfwm4 -p /general/theme -n -t string -s Breeze 2>/dev/null
    # LinuxXR changes the monitor layout (VR ring / panel size): don't pop up the display dialog.
    xfconf-query -c displays -p /Notify -n -t int -s 0 2>/dev/null
    xfconf-query -c displays -p /AutoEnableProfiles -n -t bool -s false 2>/dev/null
    xfsettingsd --replace &
    # No compositor: xfwm4's compositor paints the root black, hiding the transparency key.
    xfwm4 --replace --compositor=off &
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
