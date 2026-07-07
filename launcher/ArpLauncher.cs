using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Net;
using System.Reflection;
using System.Threading;
using System.Windows.Forms;

/// <summary>
/// ARP (Agent Reverse Proxy) 系统托盘启动器
/// 
/// 职责：
/// 1. 启动 java -jar agentreproxy.jar 后台进程
/// 2. 在系统托盘显示图标
/// 3. 点击托盘图标时打开管理页 http://127.0.0.1:8351
/// 4. 退出时关闭后台进程
/// 
/// 编译方式：
///   csc /target:winexe /win32icon:arp.ico /out:ARP.exe ArpLauncher.cs
/// </summary>
class ArpLauncher : ApplicationContext
{
    private NotifyIcon trayIcon;
    private Process javaProcess;
    private readonly string appDir;
    private readonly int port = 8351;
    private bool isExiting = false;

    [STAThread]
    static void Main(string[] args)
    {
        // 单实例检查
        bool createdNew;
        using (var mutex = new Mutex(true, "Global\\ArpLauncherMutex_8351", out createdNew))
        {
            if (!createdNew)
            {
                // 已有实例在运行，直接打开管理页
                OpenBrowser("http://127.0.0.1:8351");
                return;
            }
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new ArpLauncher());
        }
    }

    public ArpLauncher()
    {
        appDir = Path.GetDirectoryName(Assembly.GetExecutingAssembly().Location);

        // 创建托盘图标
        trayIcon = new NotifyIcon
        {
            Text = "ARP - Agent Reverse Proxy",
            Visible = true
        };

        // 尝试加载自定义图标，否则使用系统默认图标
        string iconPath = Path.Combine(appDir, "arp.ico");
        if (File.Exists(iconPath))
        {
            trayIcon.Icon = new Icon(iconPath);
        }
        else
        {
            trayIcon.Icon = SystemIcons.Application;
        }

        // 右键菜单
        var menu = new ContextMenuStrip();
        menu.Items.Add("打开管理页", null, OnOpenPage);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("重启服务", null, OnRestart);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("GitHub 仓库", null, OnOpenGitHub);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("退出", null, OnExit);
        trayIcon.ContextMenuStrip = menu;

        // 双击托盘图标 → 打开管理页
        trayIcon.DoubleClick += OnOpenPage;

        // 启动后端服务
        StartJavaProcess();

        // 启动提示
        trayIcon.ShowBalloonTip(2000, "ARP", "服务正在启动...", ToolTipIcon.Info);

        // 等服务就绪后自动打开浏览器
        var readyThread = new Thread(WaitAndOpenBrowser) { IsBackground = true };
        readyThread.Start();
    }

    private void StartJavaProcess()
    {
        string jarPath = FindJar();
        if (jarPath == null)
        {
            MessageBox.Show(
                "找不到 agentreproxy jar 文件！\n请确保 jar 文件与本程序在同一目录。",
                "ARP 启动失败",
                MessageBoxButtons.OK,
                MessageBoxIcon.Error);
            ExitThread();
            return;
        }

        // 查找 Java
        string javaExe = FindJava();
        if (javaExe == null)
        {
            MessageBox.Show(
                "找不到 Java 运行环境！\n\n请安装 JDK 17 或更高版本，\n或将 jre/ 目录放在本程序同目录下。",
                "ARP 启动失败",
                MessageBoxButtons.OK,
                MessageBoxIcon.Error);
            ExitThread();
            return;
        }

        var psi = new ProcessStartInfo
        {
            FileName = javaExe,
            Arguments = string.Format("-jar \"{0}\"", jarPath),
            WorkingDirectory = appDir,
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = false,
            RedirectStandardError = false
        };

        try
        {
            javaProcess = Process.Start(psi);
        }
        catch (Exception ex)
        {
            MessageBox.Show(
                "启动服务失败：\n" + ex.Message,
                "ARP 启动失败",
                MessageBoxButtons.OK,
                MessageBoxIcon.Error);
            ExitThread();
        }
    }

    private string FindJar()
    {
        // 1. 同目录下精确名称
        string exact = Path.Combine(appDir, "agentreproxy.jar");
        if (File.Exists(exact)) return exact;

        // 2. 同目录下模糊匹配 agentreproxy*.jar
        string[] jars = Directory.GetFiles(appDir, "agentreproxy*.jar");
        if (jars.Length > 0) return jars[0];

        // 3. app 子目录
        string appSubDir = Path.Combine(appDir, "app");
        if (Directory.Exists(appSubDir))
        {
            jars = Directory.GetFiles(appSubDir, "agentreproxy*.jar");
            if (jars.Length > 0) return jars[0];
        }

        return null;
    }

    private string FindJava()
    {
        // 1. 同目录下的 jre/bin/java.exe（捆绑 JRE 场景）
        string bundled = Path.Combine(appDir, "jre", "bin", "java.exe");
        if (File.Exists(bundled)) return bundled;

        // 2. 同目录下的 runtime/bin/java.exe（jlink 场景）
        string runtime = Path.Combine(appDir, "runtime", "bin", "java.exe");
        if (File.Exists(runtime)) return runtime;

        // 3. JAVA_HOME
        string javaHome = Environment.GetEnvironmentVariable("JAVA_HOME");
        if (!string.IsNullOrEmpty(javaHome))
        {
            string fromHome = Path.Combine(javaHome, "bin", "java.exe");
            if (File.Exists(fromHome)) return fromHome;
        }

        // 4. PATH 里的 java
        try
        {
            var psi = new ProcessStartInfo
            {
                FileName = "where",
                Arguments = "java",
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true
            };
            using (var p = Process.Start(psi))
            {
                string output = p.StandardOutput.ReadToEnd();
                p.WaitForExit();
                if (p.ExitCode == 0)
                {
                    string firstLine = output.Split(new[] { '\r', '\n' }, StringSplitOptions.RemoveEmptyEntries)[0];
                    if (File.Exists(firstLine.Trim())) return firstLine.Trim();
                }
            }
        }
        catch { }

        return null;
    }

    private void WaitAndOpenBrowser()
    {
        // 最多等 60 秒
        for (int i = 0; i < 120; i++)
        {
            if (isExiting) return;
            if (IsServiceReady())
            {
                Thread.Sleep(500); // 额外等半秒确保完全就绪
                OpenBrowser("http://127.0.0.1:" + port);
                trayIcon.ShowBalloonTip(2000, "ARP", "服务已启动", ToolTipIcon.Info);
                return;
            }
            Thread.Sleep(500);
        }

        // 超时提示
        if (!isExiting)
        {
            trayIcon.ShowBalloonTip(
                3000,
                "ARP",
                "服务启动超时，请检查 Java 环境或端口 " + port + " 是否被占用。",
                ToolTipIcon.Warning);
        }
    }

    private bool IsServiceReady()
    {
        try
        {
            var request = (HttpWebRequest)WebRequest.Create("http://127.0.0.1:" + port + "/api/accounts");
            request.Timeout = 2000;
            request.Method = "GET";
            using (var response = (HttpWebResponse)request.GetResponse())
            {
                // 任何 HTTP 响应都说明服务已启动（含 401/403 等需要认证的状态）
                return true;
            }
        }
        catch (WebException ex)
        {
            // WebException 带 Response 说明服务已响应（如 401），只是状态码非 2xx
            if (ex.Response != null) return true;
            // 无 Response = 连接失败，服务还没起来
            return false;
        }
        catch
        {
            return false;
        }
    }

    private void OnOpenPage(object sender, EventArgs e)
    {
        OpenBrowser("http://127.0.0.1:" + port);
    }

    private void OnRestart(object sender, EventArgs e)
    {
        StopJavaProcess();
        Thread.Sleep(1000);
        StartJavaProcess();
        trayIcon.ShowBalloonTip(2000, "ARP", "服务正在重启...", ToolTipIcon.Info);
    }

    private void OnOpenGitHub(object sender, EventArgs e)
    {
        OpenBrowser("https://github.com/KaiXuanSama/ARP");
    }

    private void OnExit(object sender, EventArgs e)
    {
        isExiting = true;
        StopJavaProcess();
        trayIcon.ShowBalloonTip(2000, "ARP", "服务已停止", ToolTipIcon.Info);
        Thread.Sleep(1000);
        trayIcon.Visible = false;
        trayIcon.Dispose();
        Application.Exit();
    }

    private void StopJavaProcess()
    {
        if (javaProcess != null)
        {
            try
            {
                if (!javaProcess.HasExited)
                {
                    int pid = javaProcess.Id;
                    // 用 taskkill /T 杀掉整个进程树
                    // Process.Kill() 只杀直接进程，不杀子进程；
                    // Java 可能 fork 了 worker 线程/进程，必须连带清理
                    var killPsi = new ProcessStartInfo
                    {
                        FileName = "taskkill",
                        Arguments = string.Format("/T /F /PID {0}", pid),
                        UseShellExecute = false,
                        CreateNoWindow = true
                    };
                    using (var killProc = Process.Start(killPsi))
                    {
                        if (killProc != null) killProc.WaitForExit(5000);
                    }
                    // 等待进程真正退出
                    javaProcess.WaitForExit(5000);
                }
            }
            catch { }
            finally
            {
                javaProcess = null;
            }
        }
    }

    private static void OpenBrowser(string url)
    {
        try
        {
            Process.Start(new ProcessStartInfo
            {
                FileName = url,
                UseShellExecute = true
            });
        }
        catch { }
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            isExiting = true;
            StopJavaProcess();
            if (trayIcon != null)
            {
                trayIcon.Visible = false;
                trayIcon.Dispose();
            }
        }
        base.Dispose(disposing);
    }
}
