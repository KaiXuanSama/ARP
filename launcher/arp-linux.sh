#!/bin/bash
#
# ARP (Agent Reverse Proxy) Linux 启动器
#
# 功能：
# 1. 启动 java -jar agentreproxy.jar 后台进程
# 2. 等待服务就绪后自动打开浏览器
# 3. Ctrl+C 时优雅关闭服务
#

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PORT=8351
JAVA_PID=""

cleanup() {
    echo ""
    echo "[ARP] 正在停止服务..."
    if [ -n "$JAVA_PID" ] && kill -0 "$JAVA_PID" 2>/dev/null; then
        kill "$JAVA_PID" 2>/dev/null
        # 等待最多 5 秒
        for i in $(seq 1 50); do
            kill -0 "$JAVA_PID" 2>/dev/null || break
            sleep 0.1
        done
        # 强制 kill
        kill -0 "$JAVA_PID" 2>/dev/null && kill -9 "$JAVA_PID" 2>/dev/null
    fi
    echo "[ARP] 服务已停止"
    exit 0
}

trap cleanup SIGINT SIGTERM

# -- Find Java --
find_java() {
    # 1. 同目录 jre/bin/java
    if [ -x "$SCRIPT_DIR/jre/bin/java" ]; then
        echo "$SCRIPT_DIR/jre/bin/java"
        return
    fi
    # 2. JAVA_HOME
    if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
        echo "$JAVA_HOME/bin/java"
        return
    fi
    # 3. PATH
    if command -v java &>/dev/null; then
        command -v java
        return
    fi
    echo ""
}

# -- Find jar --
find_jar() {
    # 精确名称
    if [ -f "$SCRIPT_DIR/agentreproxy.jar" ]; then
        echo "$SCRIPT_DIR/agentreproxy.jar"
        return
    fi
    # 模糊匹配
    local jar
    jar=$(find "$SCRIPT_DIR" -maxdepth 1 -name "agentreproxy*.jar" | head -1)
    if [ -n "$jar" ]; then
        echo "$jar"
        return
    fi
    echo ""
}

JAVA_EXE=$(find_java)
if [ -z "$JAVA_EXE" ]; then
    echo "[ARP] 错误：找不到 Java 运行环境！"
    echo "      请安装 JDK 17+ 或将 jre/ 目录放在本程序同目录下。"
    exit 1
fi

JAR_PATH=$(find_jar)
if [ -z "$JAR_PATH" ]; then
    echo "[ARP] 错误：找不到 agentreproxy jar 文件！"
    exit 1
fi

echo "[ARP] 服务正在启动..."
echo "      Java: $JAVA_EXE"
echo "      Jar:  $(basename "$JAR_PATH")"
echo ""

# 启动后端
cd "$SCRIPT_DIR"
"$JAVA_EXE" -jar "$JAR_PATH" &
JAVA_PID=$!

# 等待就绪
echo -n "[ARP] 等待服务就绪"
for i in $(seq 1 120); do
    if ! kill -0 "$JAVA_PID" 2>/dev/null; then
        echo ""
        echo "[ARP] 错误：服务进程意外退出"
        exit 1
    fi
    if curl -s -o /dev/null -w "%{http_code}" "http://127.0.0.1:$PORT/api/accounts" 2>/dev/null | grep -q "200"; then
        echo ""
        echo "[ARP] 服务已启动 → http://127.0.0.1:$PORT"
        # 尝试打开浏览器
        if command -v xdg-open &>/dev/null; then
            xdg-open "http://127.0.0.1:$PORT" 2>/dev/null &
        fi
        break
    fi
    echo -n "."
    sleep 0.5
done

echo ""
echo "[ARP] 按 Ctrl+C 停止服务"
echo ""

# 前台等待 Java 进程
wait "$JAVA_PID" 2>/dev/null
