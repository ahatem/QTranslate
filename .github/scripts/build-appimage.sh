#!/usr/bin/env bash
#
# Builds the Linux AppImage from the portable release ZIP.
#
# The AppImage is the Linux counterpart of the Windows app image built by package-windows.ps1: a
# single executable file the user can run without installing anything, carrying its own trimmed
# Java runtime so no system JDK is required.
#
# One property of the format decides the whole layout: the payload is a squashfs image mounted
# read-only for the lifetime of the process. The distribution can therefore never hold user data,
# so the portable marker the portable ZIP carries is stripped here for the same reason
# package-windows.ps1 refuses to let the installer payload inherit it. QTranslate resolves that
# correctly on its own -- with no marker beside the launcher it stores its data in
# $XDG_CONFIG_HOME/QTranslate and reads plugins, languages, themes and icons from the read-only
# distribution -- but only if the marker is absent, which is what this script guarantees.
#
# Usage:
#   .github/scripts/build-appimage.sh \
#       --version 1.5.0 \
#       --portable-archive build/release/QTranslate-1.5.0.zip \
#       --output-directory appimage-candidate
#
# Optional:
#   --arch x86_64          Architecture tag for the runtime and the output name.
#   --appimagetool PATH    Path to an appimagetool binary or AppImage. Defaults to $APPIMAGETOOL,
#                          then a `appimagetool` on PATH, then a download of the continuous build.
set -euo pipefail

readonly APP_NAME="QTranslate"
# Reverse-DNS component id, which is what AppStream expects and what a software centre keys on. It
# names the desktop entry, the icon and the metainfo file, so all three stay matched to each other.
readonly APP_ID="io.github.ahatem.QTranslate"
readonly DISTRIBUTION_SUBDIR="usr/share/qtranslate"
readonly RUNTIME_SUBDIR="usr/lib/jvm/qtranslate-runtime"

# Reflectively loaded providers and plugin code require modules beyond static analysis. This is the
# same set package-windows.ps1 hands jlink, so both distributions run on the same runtime surface.
readonly JLINK_MODULES="java.base,java.desktop,java.instrument,java.logging,java.management,\
java.naming,java.net.http,java.prefs,java.security.jgss,java.xml.crypto,jdk.charsets,\
jdk.unsupported,jdk.crypto.ec,jdk.localedata,jdk.zipfs"

# Sizes shipped in ui-swing/src/main/resources/icons/app. The hicolor set is what makes the icon
# correct in a taskbar, an application menu and a software centre alike.
readonly ICON_SIZES=(16 24 32 48 64 128 256 512)

# What readelf reports as the ELF machine for each supported --arch value. The bundled runtime is
# checked against this, because an AppImage named -linux-aarch64 that carries an x86-64 java is a
# packaging accident that only surfaces on the user's machine.
declare -A ELF_MACHINE_FOR_ARCH=(
    [x86_64]="Advanced Micro Devices X86-64"
    [aarch64]="AArch64"
)

usage() {
    cat <<'USAGE'
Usage:
  build-appimage.sh --version <ver> --portable-archive <zip> --output-directory <dir>

Options:
  --arch x86_64          Architecture tag for the runtime and the output name (x86_64, aarch64).
  --appimagetool PATH    Path to an appimagetool binary or AppImage. Defaults to $APPIMAGETOOL,
                         then an `appimagetool` on PATH, then a download of the continuous build.
  --runtime-jdk HOME     Build the bundled runtime from this JDK's jmods instead of the JDK on PATH.
                         For cross-architecture builds; CI builds each architecture natively.
USAGE
    exit 2
}

version=""
portable_archive=""
output_directory=""
arch="x86_64"
appimagetool_path="${APPIMAGETOOL:-}"
runtime_jdk=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --version) version="${2:-}"; shift 2 ;;
        --portable-archive) portable_archive="${2:-}"; shift 2 ;;
        --output-directory) output_directory="${2:-}"; shift 2 ;;
        --arch) arch="${2:-}"; shift 2 ;;
        --appimagetool) appimagetool_path="${2:-}"; shift 2 ;;
        --runtime-jdk) runtime_jdk="${2:-}"; shift 2 ;;
        -h|--help) usage ;;
        *) echo "Unknown argument: $1" >&2; usage ;;
    esac
done

for required in version portable_archive output_directory; do
    if [[ -z "${!required}" ]]; then
        echo "Missing required argument --${required//_/-}" >&2
        usage
    fi
done

if [[ ! -v "ELF_MACHINE_FOR_ARCH[$arch]" ]]; then
    echo "Unsupported --arch '$arch'; expected one of: ${!ELF_MACHINE_FOR_ARCH[*]}" >&2
    exit 2
fi

if ! command -v jlink >/dev/null 2>&1; then
    echo "jlink not found on PATH; a JDK 21 or newer is needed to build the bundled runtime" >&2
    exit 1
fi

# readelf prints a machine name per ELF binary. It is how the script proves the runtime it bundled
# matches --arch, and how it notices a cross-build.
elf_machine() {
    readelf -h "$1" 2>/dev/null | sed -n 's/^ *Machine:[[:space:]]*//p'
}

# The leading number of a JDK version string: 21.0.12.1 -> 21.
feature_of() {
    sed -n 's/^\([0-9]\{1,\}\).*/\1/p' <<<"$1"
}

host_machine="$(elf_machine "$(command -v jlink)")"

if [[ -n "$runtime_jdk" ]]; then
    runtime_jdk="$(readlink -f "$runtime_jdk")"
    if [[ ! -x "$runtime_jdk/bin/java" || ! -d "$runtime_jdk/jmods" ]]; then
        echo "--runtime-jdk must point at a JDK with a jmods directory: $runtime_jdk" >&2
        exit 1
    fi
    source_machine="$(elf_machine "$runtime_jdk/bin/java")"
else
    source_machine="$host_machine"
fi

# jlink itself runs on the host but links the target's jmods, so a foreign JDK yields a foreign
# runtime image. That is what makes an aarch64 AppImage buildable on an x86 host for examination;
# CI still builds each architecture on its own runner.
cross_build="no"
if [[ "$source_machine" != "$host_machine" ]]; then
    cross_build="yes"
fi

if [[ ! -f "$portable_archive" ]]; then
    echo "Portable archive not found: $portable_archive" >&2
    exit 1
fi

readonly archive="$(readlink -f "$portable_archive")"
mkdir -p "$output_directory"
readonly output="$(readlink -f "$output_directory")"
readonly repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

readonly staging="$output/staging"
readonly appdir="$output/${APP_NAME}.AppDir"
readonly appimage="$output/${APP_NAME}-${version}-linux-${arch}.AppImage"

rm -rf "$staging" "$appdir"
mkdir -p "$staging" "$appdir/usr/share"

# ---------------------------------------------------------------------------------------------
# 1. Unpack the portable candidate -- the exact tree every other release asset is built from.
# ---------------------------------------------------------------------------------------------

unzip -q "$archive" -d "$staging"
readonly distribution="$staging/$APP_NAME"

if [[ ! -f "$distribution/$APP_NAME.jar" ]]; then
    echo "Portable candidate is missing $APP_NAME.jar" >&2
    exit 1
fi
if [[ ! -f "$distribution/portable-plugin-ids.txt" ]]; then
    echo "Portable candidate is missing portable-plugin-ids.txt" >&2
    exit 1
fi
for name in plugins languages themes icons LICENSES THIRD_PARTY_LICENSES; do
    if [[ ! -d "$distribution/$name" ]]; then
        echo "Portable candidate is missing $name" >&2
        exit 1
    fi
done

# The marker is what declares a distribution portable, and a portable distribution keeps its data
# beside the launcher. An AppImage cannot: its payload is mounted read-only, so honouring the marker
# would mean failing startup on an unwritable data directory. Dropping it is what makes the
# AppImage an installed distribution in the application's own terms.
if [[ -f "$distribution/portable.flag" ]]; then
    rm -f "$distribution/portable.flag"
    echo "Stripped portable.flag: an AppImage payload is read-only, so data belongs in the user location"
fi

cp -a "$distribution" "$appdir/$DISTRIBUTION_SUBDIR"

# ---------------------------------------------------------------------------------------------
# 2. A trimmed runtime, so the AppImage needs no system Java -- the same reason the Windows
#    package ships one. Built with the JDK running this script, which the workflow pins to 21.
# ---------------------------------------------------------------------------------------------

readonly runtime="$appdir/$RUNTIME_SUBDIR"

jlink_args=(--add-modules "$JLINK_MODULES" --no-header-files --no-man-pages)
jlink_command=(jlink)
if [[ -n "$runtime_jdk" ]]; then
    if "$runtime_jdk/bin/jlink" --version >/dev/null 2>&1; then
        # Native to this host, so its own jlink runs and already defaults to its own jmods.
        jlink_command=("$runtime_jdk/bin/jlink")
    else
        # jlink refuses jmods from another feature release -- it checks the build signature inside
        # java.base -- so a cross-build needs a host jlink of the same release as the target JDK,
        # differing only in architecture.
        target_feature="$(feature_of "$(sed -n 's/^JAVA_VERSION="\([^"]*\)".*/\1/p' "$runtime_jdk/release" | head -1)")"
        host_feature="$(feature_of "$(jlink --version 2>/dev/null | head -1)")"
        if [[ "$target_feature" != "$host_feature" ]]; then
            echo "Cross-building a $arch runtime from JDK $target_feature needs a host jlink of the same feature release; the one on PATH is $host_feature" >&2
            exit 1
        fi
        jlink_args+=(--module-path "$runtime_jdk/jmods")
    fi
fi
if [[ "$cross_build" == "yes" ]]; then
    # --strip-debug shells out to objcopy, which cannot parse a foreign-architecture .so. jlink
    # reports that as a warning and still emits the image, so the option is simply left off: an
    # unstripped cross-built runtime is a little larger, and CI never takes this path.
    echo "Cross-building the $arch runtime: --strip-debug is skipped, objcopy cannot read foreign-architecture libraries"
else
    jlink_args+=(--strip-debug)
fi

build_runtime_image() {
    local compression="$1"
    "${jlink_command[@]}" "${jlink_args[@]}" --compress="$compression" --output "$runtime" 2>&1
}

# The spelling of the deflate mode has moved twice: JDK 21 accepts `zip`, later JDKs renamed it to
# `zip-6`, and `2` is the old spelling both still take with a warning. Try them in that order and
# keep the last error if none of them build.
runtime_built="no"
for compression in zip zip-6 2; do
    if attempt_output="$(build_runtime_image "$compression")"; then
        runtime_built="yes"
        # Surface jlink's own output rather than swallowing it: on the deprecated spelling it is a
        # warning worth seeing, and a silent success is the only thing hiding it.
        if [[ -n "$attempt_output" ]]; then
            printf '%s\n' "$attempt_output"
        fi
        break
    fi
    rm -rf "$runtime"
done
if [[ "$runtime_built" != "yes" ]]; then
    printf '%s\n' "$attempt_output" >&2
    echo "jlink could not build a runtime image at $runtime" >&2
    exit 1
fi

if [[ ! -x "$runtime/bin/java" ]]; then
    echo "jlink produced no runtime at $runtime" >&2
    exit 1
fi

# The AppImage name promises an architecture; the runtime inside has to keep that promise.
runtime_machine="$(elf_machine "$runtime/bin/java")"
if [[ "$runtime_machine" != *"${ELF_MACHINE_FOR_ARCH[$arch]}"* ]]; then
    echo "The bundled runtime is '$runtime_machine' but --arch is $arch (expected '${ELF_MACHINE_FOR_ARCH[$arch]}')" >&2
    exit 1
fi
echo "Bundled runtime architecture: $runtime_machine"
if [[ "$cross_build" == "no" ]]; then
    echo "Bundled runtime: $("$runtime/bin/java" -version 2>&1 | head -1)"
fi

# ---------------------------------------------------------------------------------------------
# 3. Launcher, desktop entry, icon and AppStream metadata.
# ---------------------------------------------------------------------------------------------

cat > "$appdir/AppRun" <<'APP_RUN'
#!/usr/bin/env bash
# Entry point the AppImage runtime executes with APPDIR set to the mounted payload.
set -euo pipefail

app_dir="${APPDIR:-$(cd -- "$(dirname -- "$(readlink -f -- "$0")")" && pwd)}"
distribution="$app_dir/usr/share/qtranslate"
runtime="$app_dir/usr/lib/jvm/qtranslate-runtime"

if [[ -x "$runtime/bin/java" ]]; then
    java_binary="$runtime/bin/java"
else
    java_binary="$(command -v java || true)"
    if [[ -z "$java_binary" ]]; then
        echo "QTranslate: the bundled Java runtime is missing and no system Java was found." >&2
        exit 1
    fi
fi

# No -DappData: the payload is read-only, so the application resolves its data directory itself and
# lands in $XDG_CONFIG_HOME/QTranslate. -Dawt.appName fixes the window class, which the desktop
# entry's StartupWMClass relies on to group the window with its own launcher icon.
exec "$java_binary" -Dfile.encoding=UTF-8 -Dawt.appName=QTranslate -jar "$distribution/QTranslate.jar" "$@"
APP_RUN
chmod +x "$appdir/AppRun"

cat > "$appdir/$APP_ID.desktop" <<DESKTOP
[Desktop Entry]
Type=Application
Name=$APP_NAME
GenericName=Translation tool
Comment=Select text in any application, press Ctrl+Q, and understand it without leaving what you are doing
Exec=AppRun
Icon=$APP_ID
Terminal=false
Categories=Utility;Translation;
Keywords=translate;translation;ocr;dictionary;spell;tts;
StartupWMClass=$APP_NAME
X-AppImage-Version=$version
DESKTOP

# The root copy is the one the AppImage spec requires and the runtime executes. This second copy is
# where appstreamcli resolves the component's launchable, and where a desktop environment looks when
# the AppImage is registered in a menu.
mkdir -p "$appdir/usr/share/applications"
cp "$appdir/$APP_ID.desktop" "$appdir/usr/share/applications/$APP_ID.desktop"

# The AppStream metadata is what a software centre reads. appimagetool validates it against the
# desktop entry, so shipping it is also what keeps the build from needing --no-appstream.
mkdir -p "$appdir/usr/share/metainfo"
cat > "$appdir/usr/share/metainfo/$APP_ID.appdata.xml" <<APPDATA
<?xml version="1.0" encoding="UTF-8"?>
<!-- Generated by the packaging script rather than committed, so the release entry always
     describes the build the AppImage actually contains: its date is the build date. -->
<component type="desktop-application">
  <id>$APP_ID</id>
  <metadata_license>CC0-1.0</metadata_license>
  <project_license>MPL-2.0</project_license>
  <name>$APP_NAME</name>
  <summary>Select text anywhere and translate, OCR, speak or spell check it</summary>
  <description>
    <p>Select text in any application and press Ctrl+Q for a translation without leaving what you
    are doing. Translate documents, compare providers side by side, look words up in a dictionary,
    read text out of an image, and extend the app with plugins.</p>
  </description>
  <launchable type="desktop-id">$APP_ID.desktop</launchable>
  <url type="homepage">https://github.com/ahatem/QTranslate</url>
  <url type="bugtracker">https://github.com/ahatem/QTranslate/issues</url>
  <developer id="io.github.ahatem">
    <name>Ahmed Hatem and the QTranslate contributors</name>
  </developer>
  <content_rating type="oars-1.1"/>
  <releases>
    <release version="$version" date="$(date -u +%Y-%m-%d)"/>
  </releases>
</component>
APPDATA

icon_source_dir="$repo_root/ui-swing/src/main/resources/icons/app"
for size in "${ICON_SIZES[@]}"; do
    icon_file="$icon_source_dir/icon-$size.png"
    if [[ ! -f "$icon_file" ]]; then
        echo "Expected icon not found: $icon_file" >&2
        exit 1
    fi
    mkdir -p "$appdir/usr/share/icons/hicolor/${size}x${size}/apps"
    cp "$icon_file" "$appdir/usr/share/icons/hicolor/${size}x${size}/apps/$APP_ID.png"
done
# The root-level icon is the one the AppImage runtime and older menus pick up when they resolve
# Icon= without a hicolor lookup.
cp "$icon_source_dir/icon-256.png" "$appdir/$APP_ID.png"

# ---------------------------------------------------------------------------------------------
# 4. Pack. appimagetool is itself an AppImage, so on a runner without FUSE it has to be unpacked
#    and run from the extracted copy -- hence --appimage-extract-and-run for that case only.
# ---------------------------------------------------------------------------------------------

resolve_appimagetool() {
    if [[ -n "$appimagetool_path" ]]; then
        printf '%s\n' "$appimagetool_path"
        return
    fi
    if command -v appimagetool >/dev/null 2>&1; then
        command -v appimagetool
        return
    fi
    local downloaded="$output/appimagetool-$arch.AppImage"
    # Progress goes to stderr: this function's stdout is the tool path the caller captures, and a
    # stray line there becomes part of the command name.
    echo "Downloading appimagetool ($arch continuous build)" >&2
    curl -fsSL --retry 3 -o "$downloaded" \
        "https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-$arch.AppImage"
    chmod +x "$downloaded"
    printf '%s\n' "$downloaded"
}

tool="$(resolve_appimagetool)"
tool_args=()
if [[ "$tool" == *.AppImage ]]; then
    tool_args+=(--appimage-extract-and-run)
fi

echo "Building $appimage"
ARCH="$arch" VERSION="$version" "$tool" "${tool_args[@]}" -v "$appdir" "$appimage"

if [[ ! -f "$appimage" ]]; then
    echo "appimagetool did not produce $appimage" >&2
    exit 1
fi
chmod +x "$appimage"

echo "AppImage: $appimage ($(du -h "$appimage" | cut -f1))"
