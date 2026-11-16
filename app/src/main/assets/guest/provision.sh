#!/bin/bash
# LinuxXR first-boot provisioning for Arch Linux ARM (runs inside PRoot as fake root).
set -euo pipefail
MARKER=/var/lib/linuxxr/provisioned-v1
[ -e "$MARKER" ] && { echo "[linuxxr] ya aprovisionado"; exit 0; }

echo "[linuxxr] configurando pacman"
# PRoot cannot really drop privileges to the alpm download user.
sed -i 's/^DownloadUser/#DownloadUser/' /etc/pacman.conf
if [ ! -s /etc/pacman.d/gnupg/trustdb.gpg ]; then
  pacman-key --init
  pacman-key --populate archlinuxarm
fi

echo "[linuxxr] actualizando sistema e instalando escritorio y herramientas de agentes"
pacman -Syu --noconfirm --needed \
  base-devel git curl openssh sudo which nano less \
  tigervnc xorg-xauth xorg-xrandr xorg-xdpyinfo dbus \
  xfce4 xfce4-terminal \
  xdotool scrot xclip \
  python python-pillow \
  nodejs npm \
  chromium \
  mesa-utils vulkan-tools \
  ttf-dejavu noto-fonts

mkdir -p /etc/linuxxr /var/lib/linuxxr /root/.config
if [ ! -s /etc/linuxxr/agent-token ]; then
  head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n' > /etc/linuxxr/agent-token
  chmod 600 /etc/linuxxr/agent-token
fi
# Chromium cannot use its namespace sandbox inside PRoot.
echo '--no-sandbox' > /etc/chromium-flags.conf 2>/dev/null || true
mkdir -p /root/.config && echo '--no-sandbox' > /root/.config/chromium-flags.conf

touch "$MARKER"
echo "[linuxxr] aprovisionamiento completo"
