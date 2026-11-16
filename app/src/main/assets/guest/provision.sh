#!/bin/bash
# LinuxXR first-boot provisioning for Debian 13 "trixie" (runs inside PRoot as fake root).
# Desktop: i3 tiling window manager on X11 (no compositor needed, light on PRoot's CPU).
set -euo pipefail
MARKER=/var/lib/linuxxr/provisioned-debian-i3-v1
[ -e "$MARKER" ] && { echo "[linuxxr] ya aprovisionado"; exit 0; }

export DEBIAN_FRONTEND=noninteractive
# apt cannot drop privileges to _apt under PRoot's fake root.
echo 'APT::Sandbox::User "root";' > /etc/apt/apt.conf.d/01linuxxr-sandbox
printf 'APT::Install-Recommends "false";\n' > /etc/apt/apt.conf.d/02linuxxr-norecommends

echo "[linuxxr] actualizando Debian"
apt-get update
apt-get -y full-upgrade

# Earlier LinuxXR versions installed KDE Plasma: remove it.
if dpkg -s plasma-workspace >/dev/null 2>&1; then
  echo "[linuxxr] quitando KDE Plasma"
  apt-get -y purge 'plasma-*' 'kwin-*' kde-plasma-desktop konsole dolphin kate ark gwenview okular \
    kde-spectacle systemsettings plasma-integration 'kde-*' sddm 2>/dev/null || true
  apt-get -y autoremove --purge
  rm -rf /root/.config/plasma* /root/.config/kwin* /root/.config/kde* /root/.local/share/plasma* /var/lib/linuxxr/breezedark-applied*
fi

echo "[linuxxr] instalando i3 (tiling), escritorio remoto y herramientas de agentes"
apt-get -y install \
  i3-wm i3status rofi dunst xfce4-terminal thunar adwaita-icon-theme papirus-icon-theme \
  gnome-themes-extra \
  tigervnc-standalone-server tigervnc-tools dbus-x11 x11-xserver-utils x11-utils \
  xdotool scrot xclip \
  python3 python3-pil \
  nodejs npm chromium \
  mesa-utils vulkan-tools \
  build-essential git curl ca-certificates openssh-client sudo nano less locales \
  fonts-noto-core fonts-dejavu-core

mkdir -p /etc/linuxxr /var/lib/linuxxr /root/.config
if [ ! -s /etc/linuxxr/agent-token ]; then
  head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n' > /etc/linuxxr/agent-token
  chmod 600 /etc/linuxxr/agent-token
fi
# Chromium cannot use its namespace sandbox inside PRoot.
mkdir -p /etc/chromium.d
echo 'export CHROMIUM_FLAGS="$CHROMIUM_FLAGS --no-sandbox"' > /etc/chromium.d/linuxxr

echo "[linuxxr] instalando Claude Code y oh my pi (omp)"
/usr/local/bin/linuxxr agents install claude omp || echo "[linuxxr] aviso: no se pudieron instalar los agentes; reintenta con 'linuxxr agents install claude omp'"

touch "$MARKER"
echo "[linuxxr] aprovisionamiento completo"
