# LinuxXR: GPU acceleration via Mesa Turnip (KGSL) + Zink, if built with `linuxxr gpu build`.
if [ -d /opt/linuxxr/mesa/lib ] && [ -r /dev/kgsl-3d0 ]; then
  export LINUXXR_GPU=on
  export LD_LIBRARY_PATH="/opt/linuxxr/mesa/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
  export VK_DRIVER_FILES=/opt/linuxxr/mesa/share/vulkan/icd.d/freedreno_icd.aarch64.json
  export __EGL_VENDOR_LIBRARY_FILENAMES=/opt/linuxxr/mesa/share/glvnd/egl_vendor.d/50_mesa.json
  export LIBGL_DRIVERS_PATH=/opt/linuxxr/mesa/lib/dri
  export MESA_LOADER_DRIVER_OVERRIDE=zink
  export GALLIUM_DRIVER=zink
  # Xvnc has no DRI3: present through Mesa's CPU-copy WSI path.
  export MESA_VK_WSI_DEBUG=sw
  export LIBGL_KOPPER_DRI2=true
  export TU_DEBUG=noconform
fi
