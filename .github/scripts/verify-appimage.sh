#!/usr/bin/env bash
#
# Verifies a built AppImage the way verify-windows-package.ps1 verifies the Windows app image:
# unpack it, assert the packaged layout is the one the launcher and the desktop entry expect, and
# run the release readiness probe against the copy inside the AppImage using the runtime the
# AppImage bundles.
#
# The probe is the part that carries weight. It loads every bundled plugin from the packaged JARs,
# and it asserts the data-layout contract: with no portable.flag in the payload the distribution
# must resolve as installed rather than portable, because the payload is mounted read-only at run
# time. A marker that survived the packaging step would therefore fail here rather than on a user's
# desktop at first launch.
#
# Usage:
#   .github/scripts/verify-appimage.sh --version 1.5.0 \
#       --appimage appimage-candidate/QTranslate-1.5.0-linux-x86_64.AppImage
set -euo pipefail

readonly APP_NAME="QTranslate"
readonly APP_ID="io.github.ahatem.QTranslate"
readonly DISTRIBUTION_SUBDIR="usr/share/qtranslate"
readonly RUNTIME_SUBDIR="usr/lib/jvm/qtranslate-runtime"
# The AppImage carries its own runtime plus the whole portable bundle; anything past this is a
# packaging accident rather than product weight.
readonly MAX_APPIMAGE_BYTES=$((220 * 1024 * 1024))

version=""
appimage=""
work_dir=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --version) version="${2:-}"; shift 2 ;;
        --appimage) appimage="${2:-}"; shift 2 ;;
        --work-dir) work_dir="${2:-}"; shift 2 ;;
        *) echo "Unknown argument: $1" >&2; exit 2 ;;
    esac
done

for required in version appimage; do
    if [[ -z "${!required}" ]]; then
        echo "Missing required argument --${required}" >&2
        exit 2
    fi
done

if [[ ! -f "$appimage" ]]; then
    echo "AppImage not found: $appimage" >&2
    exit 1
fi

readonly image="$(readlink -f "$appimage")"
readonly extract_dir="${work_dir:-$(dirname "$image")/appimage-inspect}"
rm -rf "$extract_dir"
mkdir -p "$extract_dir"

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

# ---------------------------------------------------------------------------------------------
# 1. The file itself: executable, and a squashfs payload behind the runtime ELF header.
# ---------------------------------------------------------------------------------------------

if [[ ! -x "$image" ]]; then
    fail "the AppImage is not executable: $image"
fi
if ! head -c 4 "$image" | grep -q $'\x7fELF'; then
    fail "the AppImage does not start with the runtime ELF header"
fi
if ! grep -aq hsqs "$image"; then
    fail "the AppImage contains no squashfs payload (hsqs magic)"
fi

size_bytes="$(stat -c %s "$image")"
if (( size_bytes > MAX_APPIMAGE_BYTES )); then
    fail "AppImage is $size_bytes bytes, over the $MAX_APPIMAGE_BYTES byte budget"
fi
echo "AppImage: $size_bytes bytes"

# ---------------------------------------------------------------------------------------------
# ---------------------------------------------------------------------------------------------
# 2. Unpack it with the AppImage runtime, which needs no FUSE -- a container runner will not have
#    any, and that is exactly the environment this job runs in.
# ---------------------------------------------------------------------------------------------

# The runtime unpacks the payload itself and writes squashfs-root into the current directory,
# hence the subshell.
if ! (cd "$extract_dir" && "$image" --appimage-extract >/dev/null 2>&1) || [[ ! -d "$extract_dir/squashfs-root" ]]; then
    fail "could not unpack the AppImage: --appimage-extract produced no squashfs-root in $extract_dir"
fi
readonly root="$extract_dir/squashfs-root"
readonly distribution="$root/$DISTRIBUTION_SUBDIR"

# ---------------------------------------------------------------------------------------------
# 3. Layout the launcher and the desktop entry depend on.
# ---------------------------------------------------------------------------------------------

[[ -f "$root/AppRun" ]] || fail "AppRun is missing"
[[ -x "$root/AppRun" ]] || fail "AppRun is not executable"
grep -q "$DISTRIBUTION_SUBDIR" "$root/AppRun" || fail "AppRun does not point at the packaged distribution"
grep -q "$APP_NAME.jar" "$root/AppRun" || fail "AppRun does not launch the packaged JAR"
grep -q "$RUNTIME_SUBDIR" "$root/AppRun" || fail "AppRun does not prefer the bundled runtime"

desktop_file="$root/$APP_ID.desktop"
[[ -f "$desktop_file" ]] || fail "$APP_ID.desktop is missing"
grep -q '^Exec=AppRun' "$desktop_file" || fail "desktop entry does not exec AppRun"
grep -q "^Icon=$APP_ID$" "$desktop_file" || fail "desktop entry does not reference the packaged icon"
grep -q "X-AppImage-Version=$version" "$desktop_file" || fail "desktop entry version does not match $version"

[[ -f "$root/$APP_ID.png" ]] || fail "root icon is missing"
[[ -f "$root/usr/share/icons/hicolor/256x256/apps/$APP_ID.png" ]] || fail "hicolor icon is missing"
[[ -f "$root/usr/share/metainfo/$APP_ID.appdata.xml" ]] || fail "AppStream metadata is missing"

for name in "$APP_NAME.jar" plugins languages themes icons LICENSES THIRD_PARTY_LICENSES; do
    [[ -e "$distribution/$name" ]] || fail "packaged distribution is missing $name"
done
[[ -f "$distribution/portable-plugin-ids.txt" ]] || fail "packaged distribution is missing portable-plugin-ids.txt"

# The assertion the whole layout rests on. The portable ZIP the AppImage is built from carries the
# marker; if packaging handed it through, the app would try to keep its data inside a read-only
# mount and refuse to start.
if [[ -e "$distribution/portable.flag" ]]; then
    fail "the AppImage payload must not contain portable.flag; its payload is read-only"
fi
echo "Packaged distribution carries no portable.flag (installed semantics)"

# ---------------------------------------------------------------------------------------------
# 4. The bundled runtime has to be the one that runs, not the runner's JDK.
# ---------------------------------------------------------------------------------------------

runtime="$root/$RUNTIME_SUBDIR"
[[ -x "$runtime/bin/java" ]] || fail "bundled runtime is missing at $RUNTIME_SUBDIR"
echo "Bundled runtime: $("$runtime/bin/java" -version 2>&1 | head -1)"

# ---------------------------------------------------------------------------------------------
# 5. Readiness probe, run from the packaged JAR on the packaged runtime.
# ---------------------------------------------------------------------------------------------

timeout 180s "$runtime/bin/java" -Djava.awt.headless=true \
    -cp "$distribution/$APP_NAME.jar" \
    com.github.ahatem.qtranslate.app.ReleaseArtifactProbeKt \
    "$distribution" || fail "release readiness probe failed against the packaged distribution"

echo "PASS: AppImage layout, bundled runtime, and packaged plugins verified"
