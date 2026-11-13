using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Windows.Forms;

// The embedded bootstrap owns the build lock and managed files.
internal sealed class Installer : Form {
    readonly Label title = new Label(), description = new Label(), error = new Label();
    readonly TextBox key = new TextBox(), package = new TextBox(), sha = new TextBox();
    readonly Button primary = new Button(), secondary = new Button(), link = new Button(), copyPackage = new Button(), copySha = new Button();
    Process worker;
    TcpListener listener;
    StreamWriter pending;
    string script, token, apk, failureReason, phase = "准备安装", current = "";
    bool running, reuse;

    Installer() {
        Text = "ibudaoa 安装向导"; ClientSize = new Size(720, 470); MinimumSize = MaximumSize = Size;
        StartPosition = FormStartPosition.CenterScreen; Font = new Font("Microsoft YaHei UI", 10F);
        title.SetBounds(30, 24, 655, 40); title.Font = new Font(Font.FontFamily, 16F, FontStyle.Bold);
        description.SetBounds(30, 80, 650, 105);
        key.SetBounds(30, 208, 600, 30); key.MaxLength = 32;
        package.SetBounds(30, 212, 490, 30); package.ReadOnly = true; package.Text = "io.github.ibudaoa";
        sha.SetBounds(30, 258, 490, 30); sha.ReadOnly = true;
        copyPackage.SetBounds(535, 208, 110, 32); copyPackage.Text = "复制包名";
        copySha.SetBounds(535, 254, 110, 32); copySha.Text = "复制 SHA-1";
        link.SetBounds(30, 310, 180, 34);
        error.SetBounds(30, 350, 650, 32); error.ForeColor = Color.Firebrick;
        primary.SetBounds(550, 405, 130, 36); secondary.SetBounds(400, 405, 130, 36);
        Controls.AddRange(new Control[] { title, description, key, package, sha, copyPackage, copySha, link, error, primary, secondary });
        copyPackage.Click += delegate { CopyText(package.Text); }; copySha.Click += delegate { CopyText(sha.Text); };
        link.Click += delegate { OpenLink(); }; primary.Click += delegate { Primary(); }; secondary.Click += delegate { Secondary(); };
        Shown += delegate { StartWork(); }; FormClosing += OnClosing;
    }
    void Page(string name, string heading, string body) {
        current = name; title.Text = heading; description.Text = body; error.Text = "";
        foreach (Control c in new Control[] {key, package, sha, copyPackage, copySha, link, primary, secondary}) c.Visible = false;
    }
    void Progress(string message) { phase = message; Page("progress", message, "请保持窗口打开。首次使用可能需要几分钟。"); }
    static string Enc(string s) { return Convert.ToBase64String(Encoding.UTF8.GetBytes(s)); }
    static string Dec(string s) { return Encoding.UTF8.GetString(Convert.FromBase64String(s)); }
    static string NewToken() { byte[] bytes = new byte[32]; using (var rng = RandomNumberGenerator.Create()) rng.GetBytes(bytes); return BitConverter.ToString(bytes).Replace("-", ""); }
    void StartWork() {
        Progress("正在下载安装所需文件……"); running = true; apk = null; failureReason = null; token = NewToken();
        try {
            listener = new TcpListener(IPAddress.Loopback, 0); listener.Start(); int port = ((IPEndPoint)listener.LocalEndpoint).Port;
            Thread thread = new Thread(Listen); thread.IsBackground = true; thread.Start();
            string dir = Path.Combine(Path.GetTempPath(), "ibudaoa-wizard-" + Guid.NewGuid().ToString("N"));
            Directory.CreateDirectory(dir); script = Path.Combine(dir, "oneclick.ps1");
            using (Stream input = Assembly.GetExecutingAssembly().GetManifestResourceStream("oneclick.ps1"))
            using (FileStream output = new FileStream(script, FileMode.CreateNew)) input.CopyTo(output);
            var info = new ProcessStartInfo("powershell.exe", "-NoProfile -ExecutionPolicy Bypass -File \"" + script + "\" build");
            info.UseShellExecute = false; info.CreateNoWindow = true; info.RedirectStandardOutput = true; info.RedirectStandardError = true;
            info.StandardOutputEncoding = Encoding.UTF8; info.StandardErrorEncoding = Encoding.UTF8;
            info.EnvironmentVariables["IBUDAOA_GUI"] = "1"; info.EnvironmentVariables["IBUDAOA_WIZARD_PORT"] = port.ToString(); info.EnvironmentVariables["IBUDAOA_WIZARD_TOKEN"] = token;
            Process process = new Process(); worker = process; process.StartInfo = info; process.EnableRaisingEvents = true;
            process.OutputDataReceived += delegate(object sender, DataReceivedEventArgs e) { if (e.Data == "IBUDAOA_STATUS:ENVIRONMENT") UI(delegate { Progress("正在准备安装环境……"); }); else ClassifyFailure(e.Data); };
            process.ErrorDataReceived += delegate(object sender, DataReceivedEventArgs e) { ClassifyFailure(e.Data); };
            process.Exited += delegate { process.WaitForExit(); int code = process.ExitCode; UI(delegate { Finished(code); }); };
            process.Start(); process.BeginOutputReadLine(); process.BeginErrorReadLine();
        } catch (Exception) { running = false; StopListener(); Cleanup(); Failure("无法启动安装向导，请检查系统环境后重试。"); }
    }
    void ClassifyFailure(string line) {
        if (line == null) return;
        if (line.Contains("当前源码版本不支持图形向导")) failureReason = "当前下载的源码不支持此版本向导，请更新源码后重试。";
        else if (line.Contains("未同意 Android SDK 许可")) failureReason = "尚未同意 Android SDK 许可，无法继续安装。";
        else if (line.Contains("签名") && (line.Contains("缺失") || line.Contains("不匹配"))) failureReason = "保存的签名缺失或不匹配，请恢复原签名备份后重试。";
    }
    void UI(Action action) { if (IsDisposed || !IsHandleCreated) return; try { BeginInvoke(action); } catch (InvalidOperationException) { } }
    void Listen() {
        TcpListener server = listener; string secret = token;
        try { while (true) { TcpClient client = server.AcceptTcpClient(); ThreadPool.QueueUserWorkItem(delegate { HandleClient(client, secret); }); } }
        catch (SocketException) { } catch (ObjectDisposedException) { }
    }
    void HandleClient(TcpClient client, string secret) {
        try {
            client.ReceiveTimeout = 10000;
            NetworkStream stream = client.GetStream(); var reader = new StreamReader(stream, Encoding.UTF8, false, 1024, true);
            var writer = new StreamWriter(stream, new UTF8Encoding(false)) { AutoFlush = true };
            string line = reader.ReadLine(); if (line == null || line.Length > 8192) { client.Close(); return; }
            string[] parts = line.Split('|'); if (parts.Length != 3 || parts[0] != secret) { client.Close(); return; }
            string action = parts[1], value = Dec(parts[2]);
            UI(delegate {
                if (!running || secret != token) { client.Close(); return; }
                if (action == "STATUS") { Progress(value == "编译 APK" ? "正在生成安装包……" : "正在" + value + "……"); Reply(writer, ""); client.Close(); }
                else if (action == "DONE") { apk = value; Reply(writer, ""); client.Close(); }
                else if (action == "LICENSE" || action == "BIND" || action == "KEY") {
                    pending = writer;
                    if (action == "LICENSE") LicensePage(); else if (action == "BIND") BindPage(value); else KeyPage(value.Contains("复用"));
                } else client.Close();
            });
        } catch (Exception) { client.Close(); }
    }
    void Reply(StreamWriter writer, string value) { try { writer.WriteLine("OK|" + Enc(value)); writer.Dispose(); } catch (IOException) { } }
    void Respond(string value) { StreamWriter writer = pending; pending = null; if (writer != null) Reply(writer, value); }
    void LicensePage() {
        Page("license", "确认 Android SDK 许可", "安装需要 Android SDK。请阅读许可，同意后继续。");
        link.Visible = true; link.Text = "查看许可"; primary.Visible = true; primary.Text = "同意并继续";
        secondary.Visible = true; secondary.Text = "不同意";
    }
    void BindPage(string value) {
        Page("bind", "配置高德 Android Key", "1. 打开高德控制台并登录。\r\n2. 创建 Android 平台的 Key。\r\n3. 将下面的包名和 SHA-1 填入对应位置，完成创建。");
        package.Visible = sha.Visible = copyPackage.Visible = copySha.Visible = link.Visible = primary.Visible = true;
        sha.Text = value; link.Text = "打开高德控制台"; primary.Text = "继续";
    }
    void KeyPage(bool saved) {
        reuse = saved; Page("key", "输入高德 Android SDK Key", saved ? "可以使用已保存的 Key，或在下方输入新的 32 位 Key。" : "粘贴刚才创建的 32 位 Android SDK Key。");
        key.Text = ""; key.Visible = primary.Visible = true; primary.Text = saved ? "使用新 Key" : "继续";
        secondary.Visible = saved; secondary.Text = "使用已保存的 Key"; key.Focus();
    }
    void Primary() {
        if (current == "license") { Respond("yes"); Progress("正在准备安装环境……"); }
        else if (current == "bind") { Respond("continue"); Progress("正在准备 Key 输入……"); }
        else if (current == "key") {
            string value = key.Text.Trim(); if (!Regex.IsMatch(value, "\\A[0-9a-fA-F]{32}\\z")) { error.Text = "请输入 32 位十六进制字符的 Key。"; return; }
            key.Clear(); Respond(value); Progress("正在生成安装包……");
        } else if (current == "failure") StartWork();
        else if (current == "done") { try { Process.Start("explorer.exe", "/select,\"" + apk + "\""); } catch (Exception) { error.Text = "无法打开文件夹，请手动打开上方路径。"; } }
    }
    void Secondary() {
        if (current == "license") { Respond("no"); Progress("正在停止……"); }
        else if (current == "key" && reuse) { Respond(""); Progress("正在生成安装包……"); }
    }
    void OpenLink() { string url = current == "license" ? "https://developer.android.com/studio/terms" : "https://console.amap.com/dev/key/app"; try { Process.Start(url); } catch (Exception) { error.Text = "无法打开浏览器，请手动访问：" + url; } }
    void CopyText(string text) { try { Clipboard.SetText(text); } catch (Exception) { error.Text = "复制失败，请手动选择并复制。"; } }
    void Failure(string message) { Page("failure", "操作未完成", message); primary.Visible = true; primary.Text = "重试"; }
    void Finished(int code) {
        running = false; StopListener(); pending = null; Cleanup();
        if (code == 0 && !String.IsNullOrEmpty(apk)) { Page("done", "安装包已生成", "将 APK 传到手机安装：\r\n" + apk); primary.Visible = true; primary.Text = "打开文件夹"; }
        else Failure(failureReason ?? ("未能完成“" + phase + "”。请检查网络和磁盘空间后重试；已有签名和 Key 会保留。"));
    }
    void StopListener() { if (listener != null) { listener.Stop(); listener = null; } }
    void Cleanup() { try { if (script != null) Directory.Delete(Path.GetDirectoryName(script), true); } catch (IOException) { } catch (UnauthorizedAccessException) { } }
    void OnClosing(object sender, FormClosingEventArgs e) {
        if (!running) { StopListener(); Cleanup(); return; }
        if (MessageBox.Show("正在安装。确定停止并退出吗？", Text, MessageBoxButtons.YesNo) != DialogResult.Yes) { e.Cancel = true; return; }
        try {
            var stop = Process.Start(new ProcessStartInfo("taskkill.exe", "/PID " + worker.Id + " /T /F") { UseShellExecute = false, CreateNoWindow = true });
            stop.WaitForExit(10000); worker.WaitForExit(10000);
            if (!worker.HasExited) { MessageBox.Show("安装仍在停止，请稍后重试。"); e.Cancel = true; return; }
        } catch (Exception) { MessageBox.Show("无法停止安装，请重试。"); e.Cancel = true; return; }
        StopListener(); Cleanup();
    }
    [STAThread] static void Main() { Application.EnableVisualStyles(); Application.SetCompatibleTextRenderingDefault(false); Application.Run(new Installer()); }
}
