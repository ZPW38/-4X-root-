#!/usr/bin/env bash
# Build the arm64 exploit payload without an NDK.
#
# The Android NDK is a 1 GB download that is not needed to produce a static
# aarch64 binary: zig's bundled clang cross-compiles the same sources for
# `aarch64-linux-musl`, which is what the payload is linked against on the
# device anyway (it must not depend on the target's libc).
#
# Usage:
#   ./native/build-zig.sh                 # -> payload/arm64-v8a/libghostlock.so
#   ZIG=/path/to/zig ./native/build-zig.sh
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(dirname "$HERE")"

ZIG="${ZIG:-}"
if [[ -z "$ZIG" ]]; then
  # The zig installed through pip ships as a `python-zig` console script.
  if command -v python-zig >/dev/null 2>&1; then
    ZIG="$(command -v python-zig)"
  elif command -v zig >/dev/null 2>&1; then
    ZIG="$(command -v zig)"
  else
    echo "error: zig not found; set ZIG=/path/to/zig or 'pip install ziglang'" >&2
    exit 1
  fi
fi

OUT_DIR="$ROOT/payload/arm64-v8a"
OUT="$OUT_DIR/libghostlock.so"
RAW="$OUT_DIR/.libghostlock.unstripped"
mkdir -p "$OUT_DIR"

# `zig objcopy --strip-all` is unimplemented in the pip build of zig, and there is
# no NDK (hence no llvm-strip), so native/strip-elf.py drops the debug sections.
PYTHON="${PYTHON:-}"
if [[ -z "$PYTHON" ]]; then
  for candidate in python3 python; do
    if command -v "$candidate" >/dev/null 2>&1; then
      PYTHON="$candidate"
      break
    fi
  done
fi

CXX_SRCS=(
  core/main.cpp
  core/attack/ops.cpp
  core/profile/entry.cpp
  core/memory/address_space.cpp
  core/memory/heap_context.cpp
  core/race/pi_race.cpp
  core/route/route_controller.cpp
  core/route/route_middleware.cpp
  core/race/threads.cpp
  core/route/tcp_zerocopy_route.cpp
  core/route/select_stack_route.cpp
  core/route/multicast_waiter_route.cpp
  core/memory/payload_builder.cpp
  core/session/runtime_config.cpp
  core/profile/binary.cpp
  core/support/util.cpp
  core/session/exploit_session.cpp
  core/session/root_child_frontend.cpp
  core/session/backend/cve_2026_43499_backend.cpp
  core/session/handoff_probe.cpp
  core/session/victim_process.cpp
  core/session/victim_context.cpp
  core/support/native_resource.cpp
  core/support/run_state.cpp
)

FLAGS=(
  -std=c++23
  -target aarch64-linux-musl
  -O2
  -static
  -pthread
  -fno-rtti
  # The shim must come first: it stands in for the one bionic header the payload
  # includes (see native/shim/sys/system_properties.h for why that is safe here).
  -I"$HERE/shim"
  -I"$HERE/core"
  # The route implementations sit behind `#if defined(__ANDROID__)`. zig does not
  # define it for a musl target, but the payload only ever runs on Android, so the
  # guard has to be satisfied explicitly or those translation units compile empty
  # and the link fails on do_pselect_fake_lock_route / reserve_standard_io.
  -D__ANDROID__
  -DTARGET_CONFIG_H='"kernel/target.h"'
)

SOURCES=()
for src in "${CXX_SRCS[@]}"; do
  SOURCES+=("$HERE/$src")
done

echo "==> zig: $ZIG"
echo "==> compiling ${#SOURCES[@]} translation units"
"$ZIG" c++ "${FLAGS[@]}" "${SOURCES[@]}" -o "$RAW"

if [[ -n "$PYTHON" ]]; then
  echo "==> stripping (debug info and symbol table only; the loaded image is untouched)"
  "$PYTHON" "$HERE/strip-elf.py" "$RAW" "$OUT"
  rm -f "$RAW"
else
  echo "==> warning: no python found, shipping the unstripped binary (5.7 MB)" >&2
  mv "$RAW" "$OUT"
fi

echo "==> built $OUT ($(stat -c %s "$OUT" 2>/dev/null || stat -f %z "$OUT") bytes)"
echo "==> next: ./gradlew :app:assembleDebug"
