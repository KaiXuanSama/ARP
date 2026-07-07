#!/bin/bash
#
# ARP (Agent Reverse Proxy) macOS 打包脚本
#
# 将 Spring Boot jar + Swift 菜单栏启动器 + 图标打包成 macOS .app 包
# 可选：如果本机有 jlink，会生成精简 JRE 一起打包，实现"免装 Java"
#
# 用法（在项目根目录执行）：
#   ./launcher/package-mac.sh [--skip-build] [--skip-jre]
#

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
LAUNCHER_DIR="$SCRIPT_DIR"
DIST_DIR="$PROJECT_ROOT/dist"
APP_NAME="ARP"
APP_DIR="$DIST_DIR/$APP_NAME.app"

SKIP_BUILD=false
SKIP_JRE=false

for arg in "$@"; do
    case $arg in
        --skip-build) SKIP_BUILD=true ;;
        --skip-jre)   SKIP_JRE=true ;;
    esac
done

echo "========================================"
echo "  ARP macOS Packaging"
echo "========================================"
echo ""

# -- Step 1: Build jar --
if [ "$SKIP_BUILD" = false ]; then
    echo "[1/6] Building Spring Boot jar..."
    cd "$PROJECT_ROOT"
    ./mvnw clean package -DskipTests
else
    echo "[1/6] Skipping jar build (--skip-build)"
fi

JAR_FILE=$(find "$PROJECT_ROOT/target" -name "agentreproxy*.jar" ! -name "*.original" | head -1)
if [ -z "$JAR_FILE" ]; then
    echo "ERROR: Cannot find built jar file"
    exit 1
fi
echo "  jar: $(basename "$JAR_FILE")"

# -- Step 2: Compile Swift menu bar app --
echo "[2/6] Compiling Swift menu bar app..."
cd "$LAUNCHER_DIR"
swiftc -O -o ARP -framework Cocoa ArpLauncher.swift
echo "  Compiled: ARP"

# -- Step 3: Prepare .app bundle --
echo "[3/6] Creating .app bundle..."
rm -rf "$APP_DIR"
mkdir -p "$APP_DIR/Contents/MacOS"
mkdir -p "$APP_DIR/Contents/Resources"

# Copy executable
cp "$LAUNCHER_DIR/ARP" "$APP_DIR/Contents/MacOS/ARP"
chmod +x "$APP_DIR/Contents/MacOS/ARP"

# Copy jar
cp "$JAR_FILE" "$APP_DIR/Contents/Resources/agentreproxy.jar"

# Copy config files
cp "$PROJECT_ROOT/src/main/resources/application.yml" "$APP_DIR/Contents/Resources/" 2>/dev/null || true
cp "$PROJECT_ROOT/src/main/resources/schema.sql" "$APP_DIR/Contents/Resources/" 2>/dev/null || true
cp "$PROJECT_ROOT/modelsconfig.json" "$APP_DIR/Contents/Resources/modelsConfig.json" 2>/dev/null || true

# Copy icon (png for menu bar)
if [ -f "$PROJECT_ROOT/icon.png" ]; then
    cp "$PROJECT_ROOT/icon.png" "$APP_DIR/Contents/Resources/arp.png"
fi

# -- Step 4: Generate .icns icon --
echo "[4/6] Generating .icns icon..."
if [ -f "$PROJECT_ROOT/icon.png" ] && command -v sips &>/dev/null && command -v iconutil &>/dev/null; then
    ICONSET_DIR="$DIST_DIR/arp.iconset"
    mkdir -p "$ICONSET_DIR"

    # Generate all required sizes for .icns
    for size in 16 32 64 128 256 512; do
        sips -z $size $size "$PROJECT_ROOT/icon.png" --out "$ICONSET_DIR/icon_${size}x${size}.png" >/dev/null 2>&1
    done
    # @2x variants
    for size in 16 32 64 128 256; do
        double=$((size * 2))
        cp "$ICONSET_DIR/icon_${double}x${double}.png" "$ICONSET_DIR/icon_${size}x${size}@2x.png" 2>/dev/null || \
        sips -z $double $double "$PROJECT_ROOT/icon.png" --out "$ICONSET_DIR/icon_${size}x${size}@2x.png" >/dev/null 2>&1
    done
    # 512@2x = 1024 (scale down from source)
    sips -z 1024 1024 "$PROJECT_ROOT/icon.png" --out "$ICONSET_DIR/icon_512x512@2x.png" >/dev/null 2>&1

    iconutil -c icns "$ICONSET_DIR" -o "$APP_DIR/Contents/Resources/arp.icns"
    rm -rf "$ICONSET_DIR"
    ICNS_NAME="arp"
    echo "  .icns created"
else
    echo "  Skipping .icns (sips/iconutil not available or icon.png missing)"
    ICNS_NAME=""
fi

# -- Step 5: Create Info.plist --
echo "[5/6] Creating Info.plist..."
cat > "$APP_DIR/Contents/Info.plist" << PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleExecutable</key>
    <string>ARP</string>
    <key>CFBundleIdentifier</key>
    <string>com.kaixuan.arp</string>
    <key>CFBundleName</key>
    <string>ARP</string>
    <key>CFBundleDisplayName</key>
    <string>Agent Reverse Proxy</string>
    <key>CFBundleVersion</key>
    <string>1.0.0</string>
    <key>CFBundleShortVersionString</key>
    <string>1.0.0</string>
    <key>CFBundlePackageType</key>
    <string>APPL</string>
    <key>CFBundleInfoDictionaryVersion</key>
    <string>6.0</string>
    <key>LSMinimumSystemVersion</key>
    <string>10.15</string>
    <key>LSUIElement</key>
    <true/>
    <key>NSHighResolutionCapable</key>
    <true/>
$([ -n "$ICNS_NAME" ] && echo "    <key>CFBundleIconFile</key>
    <string>$ICNS_NAME</string>")
</dict>
</plist>
PLIST
# LSUIElement=true -> 不在 Dock 显示，只在菜单栏

# -- Step 6: Optional bundled JRE --
if [ "$SKIP_JRE" = false ]; then
    echo "[6/6] Generating minimal JRE via jlink..."
    JLINK_EXE=""

    if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/jlink" ]; then
        JLINK_EXE="$JAVA_HOME/bin/jlink"
    else
        JLINK_EXE=$(command -v jlink 2>/dev/null || true)
    fi

    if [ -n "$JLINK_EXE" ]; then
        JRE_DIR="$APP_DIR/Contents/Resources/jre"
        MODULES="java.base,java.compiler,java.desktop,java.instrument,java.logging,java.management,java.naming,java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.security.sasl,java.sql,java.sql.rowset,java.transaction.xa,java.xml,java.xml.crypto,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.jdwp.agent,jdk.management,jdk.naming.dns,jdk.net,jdk.unsupported,jdk.zipfs"

        "$JLINK_EXE" --add-modules "$MODULES" \
            --strip-debug --no-man-pages --no-header-files \
            --compress zip-6 --output "$JRE_DIR"

        if [ $? -eq 0 ]; then
            JRE_SIZE=$(du -sm "$JRE_DIR" | cut -f1)
            echo "  JRE bundled: ${JRE_SIZE} MB"
        else
            echo "  jlink failed, skipping JRE bundle"
        fi
    else
        echo "  jlink not found, skipping JRE bundle"
    fi
else
    echo "[6/6] Skipping JRE bundle (--skip-jre)"
fi

# -- Create .dmg (optional, zip as fallback) --
echo ""
echo "Creating distributable archive..."
ZIP_FILE="$DIST_DIR/ARP-macos.zip"
rm -f "$ZIP_FILE"
cd "$DIST_DIR"
# 清除隔离属性，防止 Gatekeeper 报"已损坏"
xattr -cr "$APP_NAME.app"
chmod +x "$APP_NAME.app/Contents/MacOS/ARP"
# ditto 保留 macOS 元数据和权限
ditto -c -k --keepParent "$APP_NAME.app" ARP-macos.zip
ZIP_SIZE=$(du -sm "$ZIP_FILE" | cut -f1)

echo ""
echo "========================================"
echo "  Packaging complete!"
echo "========================================"
echo ""
echo "  App bundle: $APP_DIR"
echo "  Zip file:   $ZIP_FILE (${ZIP_SIZE} MB)"
echo ""
echo "  How to use:"
echo "    1. Unzip ARP-macos.zip"
echo "    2. Double-click ARP.app (first time: right-click -> Open)"
echo "    3. ARP icon appears in menu bar"
echo "    4. Browser opens http://127.0.0.1:8351"
echo ""
