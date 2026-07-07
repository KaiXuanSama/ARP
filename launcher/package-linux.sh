#!/bin/bash
#
# ARP (Agent Reverse Proxy) Linux 打包脚本
#
# 将 Spring Boot jar + 启动脚本打包成可分发的 tar.gz
# 可选：如果本机有 jlink，会生成精简 JRE 一起打包
#
# 用法：./launcher/package-linux.sh [--skip-build] [--skip-jre]
#

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
LAUNCHER_DIR="$SCRIPT_DIR"
DIST_DIR="$PROJECT_ROOT/dist"
OUTPUT_DIR="$DIST_DIR/ARP-linux"

SKIP_BUILD=false
SKIP_JRE=false

for arg in "$@"; do
    case $arg in
        --skip-build) SKIP_BUILD=true ;;
        --skip-jre)   SKIP_JRE=true ;;
    esac
done

echo "========================================"
echo "  ARP Linux Packaging"
echo "========================================"
echo ""

# -- Step 1: Build jar --
if [ "$SKIP_BUILD" = false ]; then
    echo "[1/4] Building Spring Boot jar..."
    cd "$PROJECT_ROOT"
    ./mvnw clean package -DskipTests
else
    echo "[1/4] Skipping jar build (--skip-build)"
fi

JAR_FILE=$(find "$PROJECT_ROOT/target" -name "agentreproxy*.jar" ! -name "*.original" | head -1)
if [ -z "$JAR_FILE" ]; then
    echo "ERROR: Cannot find built jar file"
    exit 1
fi
echo "  jar: $(basename "$JAR_FILE")"

# -- Step 2: Prepare output directory --
echo "[2/4] Preparing output directory..."
rm -rf "$OUTPUT_DIR"
mkdir -p "$OUTPUT_DIR"

cp "$JAR_FILE" "$OUTPUT_DIR/agentreproxy.jar"
cp "$LAUNCHER_DIR/arp-linux.sh" "$OUTPUT_DIR/arp.sh"
chmod +x "$OUTPUT_DIR/arp.sh"

# Config files
cp "$PROJECT_ROOT/src/main/resources/application.yml" "$OUTPUT_DIR/" 2>/dev/null || true
cp "$PROJECT_ROOT/src/main/resources/schema.sql" "$OUTPUT_DIR/" 2>/dev/null || true
cp "$PROJECT_ROOT/modelsconfig.json" "$OUTPUT_DIR/modelsConfig.json" 2>/dev/null || true
cp "$PROJECT_ROOT/icon.png" "$OUTPUT_DIR/arp.png" 2>/dev/null || true

# -- Step 3: Optional bundled JRE --
if [ "$SKIP_JRE" = false ]; then
    echo "[3/4] Generating minimal JRE via jlink..."
    JLINK_EXE=""

    if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/jlink" ]; then
        JLINK_EXE="$JAVA_HOME/bin/jlink"
    else
        JLINK_EXE=$(command -v jlink 2>/dev/null || true)
    fi

    if [ -n "$JLINK_EXE" ]; then
        JRE_DIR="$OUTPUT_DIR/jre"
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
    echo "[3/4] Skipping JRE bundle (--skip-jre)"
fi

# -- Step 4: Create tar.gz --
echo "[4/4] Creating tar.gz archive..."
TAR_FILE="$DIST_DIR/ARP-linux.tar.gz"
rm -f "$TAR_FILE"
cd "$DIST_DIR"
tar czf "$TAR_FILE" "ARP-linux"
TAR_SIZE=$(du -sm "$TAR_FILE" | cut -f1)

echo ""
echo "========================================"
echo "  Packaging complete!"
echo "========================================"
echo ""
echo "  Output dir: $OUTPUT_DIR"
echo "  Archive:    $TAR_FILE (${TAR_SIZE} MB)"
echo ""
echo "  How to use:"
echo "    1. tar xzf ARP-linux.tar.gz"
echo "    2. cd ARP-linux"
echo "    3. ./arp.sh"
echo "    4. Browser opens http://127.0.0.1:8351"
echo ""
