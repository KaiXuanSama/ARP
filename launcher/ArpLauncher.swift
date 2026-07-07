import Cocoa

/// ARP (Agent Reverse Proxy) macOS 菜单栏启动器
///
/// 职责：
/// 1. 启动 java -jar agentreproxy.jar 后台进程
/// 2. 在菜单栏显示图标
/// 3. 点击菜单栏图标时显示菜单（打开管理页/重启/GitHub/退出）
/// 4. 退出时关闭后台进程
///
/// 编译方式（在 macOS 上）：
///   swiftc -o ARP -framework Cocoa ArpLauncher.swift
///
class AppDelegate: NSObject, NSApplicationDelegate {
    private var statusItem: NSStatusItem!
    private var javaProcess: Process?
    private let port = 8351
    private var isExiting = false

    func applicationDidFinishLaunching(_ notification: Notification) {
        // 创建菜单栏图标
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)

        if let button = statusItem.button {
            // 尝试加载自定义图标
            let appDir = Bundle.main.bundlePath.contains(".app")
                ? Bundle.main.resourcePath ?? FileManager.default.currentDirectoryPath
                : FileManager.default.currentDirectoryPath

            let iconPath = (appDir as NSString).appendingPathComponent("arp.png")
            if FileManager.default.fileExists(atPath: iconPath),
               let img = NSImage(contentsOfFile: iconPath) {
                img.isTemplate = true
                img.size = NSSize(width: 18, height: 18)
                button.image = img
            } else {
                button.title = "ARP"
            }
            button.toolTip = "ARP - Agent Reverse Proxy"
        }

        // 构建菜单
        let menu = NSMenu()
        menu.addItem(NSMenuItem(title: "打开管理页", action: #selector(openPage), keyEquivalent: "o"))
        menu.addItem(NSMenuItem.separator())
        menu.addItem(NSMenuItem(title: "重启服务", action: #selector(restartService), keyEquivalent: "r"))
        menu.addItem(NSMenuItem.separator())
        menu.addItem(NSMenuItem(title: "GitHub 仓库", action: #selector(openGitHub), keyEquivalent: ""))
        menu.addItem(NSMenuItem.separator())
        menu.addItem(NSMenuItem(title: "退出", action: #selector(quitApp), keyEquivalent: "q"))
        statusItem.menu = menu

        // 启动服务
        startJavaProcess()
        showNotification(title: "ARP", body: "服务正在启动...")

        // 后台等待服务就绪
        DispatchQueue.global(qos: .background).async { [weak self] in
            self?.waitAndOpenBrowser()
        }
    }

    // MARK: - Java Process Management

    private func startJavaProcess() {
        guard let jarPath = findJar() else {
            showAlert(message: "找不到 agentreproxy jar 文件！\n请确保 jar 文件与本程序在同一目录。")
            NSApplication.shared.terminate(nil)
            return
        }

        guard let javaExe = findJava() else {
            showAlert(message: "找不到 Java 运行环境！\n\n请安装 JDK 17 或更高版本，\n或将 jre/ 目录放在本程序同目录下。")
            NSApplication.shared.terminate(nil)
            return
        }

        let process = Process()
        process.executableURL = URL(fileURLWithPath: javaExe)
        process.arguments = ["-jar", jarPath]
        process.currentDirectoryURL = URL(fileURLWithPath: appDirectory())
        // 静默输出
        process.standardOutput = FileHandle.nullDevice
        process.standardError = FileHandle.nullDevice

        do {
            try process.launch()
            javaProcess = process
        } catch {
            showAlert(message: "启动服务失败：\n\(error.localizedDescription)")
            NSApplication.shared.terminate(nil)
        }
    }

    private func stopJavaProcess() {
        guard let process = javaProcess, process.isRunning else {
            javaProcess = nil
            return
        }
        // 发送 SIGTERM 优雅关闭
        process.terminate()
        // 等待最多 5 秒
        let deadline = Date().addingTimeInterval(5)
        while process.isRunning && Date() < deadline {
            Thread.sleep(forTimeInterval: 0.1)
        }
        // 如果还没退出，强制 kill
        if process.isRunning {
            process.interrupt()
            Thread.sleep(forTimeInterval: 1)
            if process.isRunning {
                kill(process.processIdentifier, SIGKILL)
            }
        }
        javaProcess = nil
    }

    // MARK: - File Discovery

    private func appDirectory() -> String {
        if Bundle.main.bundlePath.hasSuffix(".app") {
            return Bundle.main.resourcePath ?? FileManager.default.currentDirectoryPath
        }
        return FileManager.default.currentDirectoryPath
    }

    private func findJar() -> String? {
        let dir = appDirectory()
        let fm = FileManager.default

        // 精确名称
        let exact = (dir as NSString).appendingPathComponent("agentreproxy.jar")
        if fm.fileExists(atPath: exact) { return exact }

        // 模糊匹配
        if let files = try? fm.contentsOfDirectory(atPath: dir) {
            for file in files where file.hasPrefix("agentreproxy") && file.hasSuffix(".jar") {
                return (dir as NSString).appendingPathComponent(file)
            }
        }

        // app 子目录
        let appSub = (dir as NSString).appendingPathComponent("app")
        if let files = try? fm.contentsOfDirectory(atPath: appSub) {
            for file in files where file.hasPrefix("agentreproxy") && file.hasSuffix(".jar") {
                return (appSub as NSString).appendingPathComponent(file)
            }
        }

        return nil
    }

    private func findJava() -> String? {
        let dir = appDirectory()
        let fm = FileManager.default

        // 1. 同目录 jre/bin/java（捆绑 JRE）
        let bundled = (dir as NSString).appendingPathComponent("jre/bin/java")
        if fm.fileExists(atPath: bundled) { return bundled }

        // 2. 同目录 runtime/bin/java（jlink）
        let runtime = (dir as NSString).appendingPathComponent("runtime/bin/java")
        if fm.fileExists(atPath: runtime) { return runtime }

        // 3. JAVA_HOME
        if let javaHome = ProcessInfo.processInfo.environment["JAVA_HOME"] {
            let fromHome = (javaHome as NSString).appendingPathComponent("bin/java")
            if fm.fileExists(atPath: fromHome) { return fromHome }
        }

        // 4. PATH 中的 java
        let which = Process()
        which.executableURL = URL(fileURLWithPath: "/usr/bin/which")
        which.arguments = ["java"]
        let pipe = Pipe()
        which.standardOutput = pipe
        which.standardError = FileHandle.nullDevice
        do {
            try which.launch()
            which.waitUntilExit()
            if which.terminationStatus == 0 {
                let data = pipe.fileHandleForReading.readDataToEndOfFile()
                if let path = String(data: data, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines),
                   !path.isEmpty, fm.fileExists(atPath: path) {
                    return path
                }
            }
        } catch { }

        return nil
    }

    // MARK: - Service Readiness

    private func waitAndOpenBrowser() {
        for _ in 0..<120 {
            if isExiting { return }
            if isServiceReady() {
                Thread.sleep(forTimeInterval: 0.5)
                DispatchQueue.main.async { [weak self] in
                    self?.openBrowser("http://127.0.0.1:\(self?.port ?? 8351)")
                    self?.showNotification(title: "ARP", body: "服务已启动")
                }
                return
            }
            Thread.sleep(forTimeInterval: 0.5)
        }

        if !isExiting {
            DispatchQueue.main.async { [weak self] in
                self?.showNotification(title: "ARP", body: "服务启动超时，请检查 Java 环境或端口是否被占用。")
            }
        }
    }

    private func isServiceReady() -> Bool {
        guard let url = URL(string: "http://127.0.0.1:\(port)/api/accounts") else { return false }
        var request = URLRequest(url: url)
        request.timeoutInterval = 2
        request.httpMethod = "GET"

        let semaphore = DispatchSemaphore(value: 0)
        var ready = false
        URLSession.shared.dataTask(with: request) { _, response, _ in
            if let http = response as? HTTPURLResponse, http.statusCode == 200 {
                ready = true
            }
            semaphore.signal()
        }.resume()
        semaphore.wait()
        return ready
    }

    // MARK: - Menu Actions

    @objc private func openPage() {
        openBrowser("http://127.0.0.1:\(port)")
    }

    @objc private func restartService() {
        stopJavaProcess()
        Thread.sleep(forTimeInterval: 1)
        startJavaProcess()
        showNotification(title: "ARP", body: "服务正在重启...")
    }

    @objc private func openGitHub() {
        openBrowser("https://github.com/KaiXuanSama/ARP")
    }

    @objc private func quitApp() {
        isExiting = true
        stopJavaProcess()
        showNotification(title: "ARP", body: "服务已停止")
        // 给通知一点时间显示
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) {
            NSApplication.shared.terminate(nil)
        }
    }

    // MARK: - Helpers

    private func openBrowser(_ urlString: String) {
        if let url = URL(string: urlString) {
            NSWorkspace.shared.open(url)
        }
    }

    private func showNotification(title: String, body: String) {
        let notification = NSUserNotification()
        notification.title = title
        notification.informativeText = body
        NSUserNotificationCenter.default.deliver(notification)
    }

    private func showAlert(message: String) {
        let alert = NSAlert()
        alert.messageText = "ARP 启动失败"
        alert.informativeText = message
        alert.alertStyle = .critical
        alert.addButton(withTitle: "确定")
        alert.runModal()
    }
}

// MARK: - Entry Point

let app = NSApplication.shared
app.setActivationPolicy(.accessory)  // 不显示 Dock 图标，只显示菜单栏
let delegate = AppDelegate()
app.delegate = delegate
app.run()
