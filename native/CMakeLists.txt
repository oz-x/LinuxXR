# Builds PRoot (termux fork) + its loader for Android arm64.
# Outputs: proot (PIE, talloc linked statically) and loader (static, no libc).
cmake_minimum_required(VERSION 3.22)
project(linuxxr_native C ASM)

set(PROOT_SRC "" CACHE PATH "Path to proot/src")
set(TALLOC_SRC "" CACHE PATH "Path to talloc source dir")
if(NOT EXISTS "${PROOT_SRC}/cli/proot.c" OR NOT EXISTS "${TALLOC_SRC}/talloc.c")
  message(FATAL_ERROR "PROOT_SRC / TALLOC_SRC not set correctly")
endif()

# --- talloc (static) -------------------------------------------------------
add_library(talloc STATIC "${TALLOC_SRC}/talloc.c")
target_include_directories(talloc PUBLIC "${TALLOC_SRC}" PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/talloc")
target_compile_definitions(talloc PRIVATE
  TALLOC_BUILD_VERSION_MAJOR=2 TALLOC_BUILD_VERSION_MINOR=4 TALLOC_BUILD_VERSION_RELEASE=4)
target_compile_options(talloc PRIVATE -O2 -w)

# --- build.h (replaces the makefile's feature probes) ----------------------
file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/gen/build.h"
"#ifndef BUILD_H\n#define BUILD_H\n#undef VERSION\n#define VERSION \"5.1.107-linuxxr\"\n#define HAVE_PROCESS_VM\n#define HAVE_SECCOMP_FILTER\n#endif\n")

# --- loader -----------------------------------------------------------------
# LOADER_ADDRESS for ARM64 from src/arch.h.
add_executable(loader "${PROOT_SRC}/loader/loader.c" "${PROOT_SRC}/loader/assembly.S")
target_include_directories(loader PRIVATE "${PROOT_SRC}")
target_compile_definitions(loader PRIVATE _FILE_OFFSET_BITS=64 _GNU_SOURCE)
target_compile_options(loader PRIVATE -O2 -fPIC -ffreestanding -fno-stack-protector)
target_link_options(loader PRIVATE -static -nostdlib
  "-Wl,--build-id=none,-Ttext=0x5400000000,--rosegment,-z,noexecstack,--no-gc-sections")
set_target_properties(loader PROPERTIES POSITION_INDEPENDENT_CODE OFF)
# NDK adds -pie for executables; the loader must be a static non-PIE image.
target_link_options(loader PRIVATE -no-pie)

find_program(LLVM_NM llvm-nm HINTS "${ANDROID_TOOLCHAIN_ROOT}/bin" REQUIRED)
add_custom_command(
  OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/gen/loader-info.c"
  COMMAND "${CMAKE_COMMAND}" -DNM=${LLVM_NM} -DLOADER=$<TARGET_FILE:loader>
          -DOUTPUT=${CMAKE_CURRENT_BINARY_DIR}/gen/loader-info.c
          -P "${CMAKE_CURRENT_SOURCE_DIR}/loader-info.cmake"
  DEPENDS loader "${CMAKE_CURRENT_SOURCE_DIR}/loader-info.cmake")

# --- proot ------------------------------------------------------------------
set(PROOT_OBJS
  cli/cli cli/proot cli/note
  execve/enter execve/exit execve/shebang execve/elf execve/ldso execve/auxv execve/aoxp
  path/binding path/glue path/canon path/f2fs-bug path/path path/proc path/temp
  syscall/seccomp syscall/syscall syscall/chain syscall/enter syscall/exit syscall/sysnum
  syscall/socket syscall/heap syscall/rlimit syscall/pipe_shadow
  tracee/tracee tracee/mem tracee/reg tracee/event tracee/seccomp tracee/statx
  ptrace/ptrace ptrace/user ptrace/wait
  extension/extension extension/ashmem_memfd/ashmem_memfd extension/kompat/kompat
  extension/fake_id0/chown extension/fake_id0/chroot extension/fake_id0/getsockopt
  extension/fake_id0/sendmsg extension/fake_id0/socket extension/fake_id0/open
  extension/fake_id0/unlink extension/fake_id0/rename extension/fake_id0/chmod
  extension/fake_id0/utimensat extension/fake_id0/access extension/fake_id0/exec
  extension/fake_id0/link extension/fake_id0/symlink extension/fake_id0/mk
  extension/fake_id0/stat extension/fake_id0/helper_functions extension/fake_id0/fake_id0
  extension/hidden_files/hidden_files extension/mountinfo/mountinfo
  extension/port_switch/port_switch
  extension/sysvipc/sysvipc extension/sysvipc/sysvipc_msg
  extension/sysvipc/sysvipc_sem extension/sysvipc/sysvipc_shm
  extension/link2symlink/link2symlink extension/fix_symlink_size/fix_symlink_size)
list(TRANSFORM PROOT_OBJS PREPEND "${PROOT_SRC}/")
list(TRANSFORM PROOT_OBJS APPEND ".c")

add_executable(proot ${PROOT_OBJS} "${CMAKE_CURRENT_BINARY_DIR}/gen/loader-info.c")
target_include_directories(proot PRIVATE "${CMAKE_CURRENT_BINARY_DIR}/gen" "${PROOT_SRC}")
# The loader path is supplied at runtime through PROOT_LOADER (APK native lib dir).
target_compile_definitions(proot PRIVATE _FILE_OFFSET_BITS=64 _GNU_SOURCE
  "PROOT_UNBUNDLE_LOADER=\"/nonexistent\"")
# Some upstream files rely on headers pulled in transitively by glibc.
target_compile_options(proot PRIVATE -O2 -include string.h -Wno-unused-parameter -Wno-sign-compare)
target_link_libraries(proot PRIVATE talloc)
target_link_options(proot PRIVATE "-Wl,-z,noexecstack")
