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
#       --appimage appimage-candidate/QTranslate-1.5.0-linux-x86_64.AppImage [--arch x86_64]
#
# --arch defaults to x86_64 and must match the architecture tag in the file name. The bundled
# runtime's ELF machine is checked against it, so an AppImage cannot be named for one CPU and carry
# another. When the host cannot execute that runtime -- an aarch64 image checked on an x86 host --
# the layout and architecture assertions still run and only the readiness probe is skipped.
set -euo pipefail

readonly APP_NAME="QTranslate"
readonly APP_ID="io.github.ahatem.QTranslate"
readonly DISTRIBUTION_SUBDIR="usr/share/qtranslate"
readonly RUNTIME_SUBDIR="usr/lib/jvm/qtranslate-runtime"
# The AppImage carries its own runtime plus the whole portable bundle; anything past this is a
# packaging accident rather than product weight.
readonly MAX_APPIMAGE_BYTES=$((220 * 1024 * 1024))

# readelf's machine name per supported architecture tag.
declare -A ELF_MACHINE_FOR_ARCH=(
    [x86_64]="Advanced Micro Devices X86-64"
    [aarch64]="AArch64"
)

version=""
appimage=""
work_dir=""
arch="x86_64"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --version) version="${2:-}"; shift 2 ;;
        --appimage) appimage="${2:-}"; shift 2 ;;
        --work-dir) work_dir="${2:-}"; shift 2 ;;
        --arch) arch="${2:-}"; shift 2 ;;
        *) echo "Unknown argument: $1" >&2; exit 2 ;;
    esac
done

if [[ ! -v "ELF_MACHINE_FOR_ARCH[$arch]" ]]; then
    echo "Unsupported --arch '$arch'; expected one of: ${!ELF_MACHINE_FOR_ARCH[*]}" >&2
    exit 2
fi

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
# 2. Unpack it. The AppImage runtime unpacks its own payload and needs no FUSE, which a container
#    runner will not have. That path is unavailable for a foreign-architecture image, whose runtime
#    this host cannot execute, so unsquashfs reads the payload directly.
# ---------------------------------------------------------------------------------------------

# The runtime writes squashfs-root into the current directory, hence the subshell.
if ! (cd "$extract_dir" && "$image" --appimage-extract >/dev/null 2>&1) || [[ ! -d "$extract_dir/squashfs-root" ]]; then
    rm -rf "$extract_dir/squashfs-root"
    command -v unsquashfs >/dev/null 2>&1 ||
        fail "could not unpack the AppImage: its runtime is not executable here and unsquashfs is not installed"

    # The payload is appended after the runtime, so its squashfs superblock sits at a byte offset
    # rather than at the start of the file. The runtime binary can carry the same four-byte magic
    # itself, so every candidate is tried, last first, until one yields a real AppDir.
    unpacked="no"
    while read -r offset; do
        if unsquashfs -quiet -no-xattrs -offset "$offset" -d "$extract_dir/squashfs-root" "$image" >/dev/null 2>&1 \
            && [[ -f "$extract_dir/squashfs-root/AppRun" ]]; then
            echo "Unpacked the payload at byte offset $offset with unsquashfs"
            unpacked="yes"
            break
        fi
        rm -rf "$extract_dir/squashfs-root"
    done < <(grep -abo hsqs "$image" | cut -d: -f1 | tac)
    [[ "$unpacked" == "yes" ]] || fail "unsquashfs found no readable squashfs payload in $image"
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

# The desktop entry is what a menu lists and what a window manager groups a window under, so it is
# checked as packaging surface rather than assumed.
desktop_file="$root/$APP_ID.desktop"
[[ -f "$desktop_file" ]] || fail "$APP_ID.desktop is missing"
grep -q '^Type=Application$' "$desktop_file" || fail "desktop entry is not a Type=Application entry"
grep -q "^Name=$APP_NAME\$" "$desktop_file" || fail "desktop entry does not name the product"
grep -q '^Exec=AppRun' "$desktop_file" || fail "desktop entry does not exec AppRun"
grep -q "^Icon=$APP_ID\$" "$desktop_file" || fail "desktop entry does not reference the packaged icon"
grep -q "^StartupWMClass=$APP_NAME\$" "$desktop_file" || fail "desktop entry lacks StartupWMClass, so a taskbar would not group the window with its launcher"
grep -q "X-AppImage-Version=$version" "$desktop_file" || fail "desktop entry version does not match $version"

# The copy under usr/share/applications is what appstreamcli resolves the component's launchable
# against, and what a desktop environment reads once the AppImage is registered in a menu.
applications_copy="$root/usr/share/applications/$APP_ID.desktop"
[[ -f "$applications_copy" ]] || fail "the desktop entry is not installed under usr/share/applications"
cmp -s "$desktop_file" "$applications_copy" || fail "the two desktop entries differ"

if command -v desktop-file-validate >/dev/null 2>&1; then
    desktop-file-validate "$desktop_file" || fail "the desktop entry does not validate"
    echo "Desktop entry validates"
fi

# Every shipped size has to be present. A menu that asks for 24x24 renders a placeholder if only
# 256x256 was installed, and that miss is invisible on a high-DPI machine.
for size in 16 24 32 48 64 128 256 512; do
    icon="$root/usr/share/icons/hicolor/${size}x${size}/apps/$APP_ID.png"
    [[ -f "$icon" ]] || fail "hicolor icon missing at ${size}x${size}"
    # A truncated or empty file passes -f and renders as nothing.
    if [[ ! -s "$icon" ]] || [[ "$(head -c 8 "$icon" | od -An -tx1 | tr -d ' \n')" != "89504e470d0a1a0a" ]]; then
        fail "hicolor icon at ${size}x${size} is not a readable PNG"
    fi
done
[[ -f "$root/$APP_ID.png" ]] || fail "root icon is missing"
[[ -e "$root/.DirIcon" ]] || fail ".DirIcon is missing, so a file manager shows no thumbnail for the file"
[[ -f "$root/usr/share/metainfo/$APP_ID.appdata.xml" ]] || fail "AppStream metadata is missing"

if command -v appstreamcli >/dev/null 2>&1; then
    appstreamcli validate "$root/usr/share/metainfo/$APP_ID.appdata.xml" >/dev/null 2>&1 \
        || fail "AppStream metadata does not validate"
    echo "AppStream metadata validates"
fi

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

elf_machine() {
    readelf -h "$1" 2>/dev/null | sed -n 's/^ *Machine:[[:space:]]*//p'
}

runtime_machine="$(elf_machine "$runtime/bin/java")"
if [[ -z "$runtime_machine" ]]; then
    fail "could not read the ELF machine of the bundled runtime"
fi
if [[ "$runtime_machine" != *"${ELF_MACHINE_FOR_ARCH[$arch]}"* ]]; then
    fail "the bundled runtime is '$runtime_machine' but this AppImage is named for $arch (expected '${ELF_MACHINE_FOR_ARCH[$arch]}')"
fi
echo "Bundled runtime architecture: $runtime_machine"

# ---------------------------------------------------------------------------------------------
# 5. Readiness probe, run from the packaged JAR on the packaged runtime.
# ---------------------------------------------------------------------------------------------

# The probe executes the bundled runtime, so it only runs where the host can. A cross-architecture
# image still gets every layout and architecture assertion above.
if [[ "$runtime_machine" != "$(elf_machine /proc/self/exe)" ]]; then
    echo "SKIP: the readiness probe would have to execute the bundled $arch runtime on a '$runtime_machine' host"
    echo "PASS: AppImage layout, desktop entry, icons and runtime architecture verified (probe skipped)"
    exit 0
fi

timeout 180s "$runtime/bin/java" -Djava.awt.headless=true \
    -cp "$distribution/$APP_NAME.jar" \
    com.github.ahatem.qtranslate.app.ReleaseArtifactProbeKt \
    "$distribution" || fail "release readiness probe failed against the packaged distribution"

echo "PASS: AppImage layout, bundled runtime, and packaged plugins verified"
