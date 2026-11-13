using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Reflection;
using System.Text;
using System.Windows.Forms;

// The embedded bootstrap owns the existing build lock and all managed files.
internal sealed class Installer : Form {
    readonly Label status = new Label();
    readonly TextBox log = new TextBox();
    readonly Button retry = new Button();
    Process worker;
    string script;
    bool running;

    Installer() {
        Text = "ibudaoa 安装向导"; Width = 740; Height = 510; StartPosition = FormStartPosition.CenterScreen;
        Font = new Font("Microsoft YaHei UI", 9F);
        status.SetBounds(20, 18, 680, 48); status.Text = "正在准备，请保持窗口打开。需要操作时会出现提示。";
        log.SetBounds(20, 72, 680, 330); log.Multiline = true; log.ReadOnly = true; log.ScrollBars = ScrollBars.Vertical;
        log.Anchor = AnchorStyles.Top | AnchorStyles.Bottom | AnchorStyles.Left | AnchorStyles.Right;
        retry.Text = "重试"; retry.SetBounds(20, 412, 100, 30); retry.Anchor = AnchorStyles.Bottom | AnchorStyles.Left;
        retry.Enabled = false; retry.Click += delegate { StartWork(); };
        Controls.AddRange(new Control[] { status, log, retry });
        Shown += delegate { StartWork(); };
        FormClosing += Closing;
    }
    void Append(string text) {
        if (IsDisposed || !IsHandleCreated) return;
        try { BeginInvoke((Action)delegate {
            if (log.TextLength > 120000) log.Text = log.Text.Substring(log.TextLength - 60000);
            log.AppendText(text + Environment.NewLine);
        }); } catch (InvalidOperationException) { }
    }
    void StartWork() {
        retry.Enabled = false; running = true; status.Text = "正在准备和构建；需要操作时会出现提示。";
        try {
            string dir = Path.Combine(Path.GetTempPath(), "ibudaoa-wizard-" + Guid.NewGuid().ToString("N"));
            Directory.CreateDirectory(dir); script = Path.Combine(dir, "oneclick.ps1");
            using (Stream input = Assembly.GetExecutingAssembly().GetManifestResourceStream("oneclick.ps1"))
            using (FileStream output = new FileStream(script, FileMode.CreateNew)) input.CopyTo(output);
            var info = new ProcessStartInfo("powershell.exe", "-NoProfile -ExecutionPolicy Bypass -File \"" + script + "\" build");
            info.UseShellExecute = false; info.CreateNoWindow = true; info.RedirectStandardOutput = true; info.RedirectStandardError = true;
            info.StandardOutputEncoding = Encoding.UTF8; info.StandardErrorEncoding = Encoding.UTF8;
            info.EnvironmentVariables["IBUDAOA_GUI"] = "1";
            worker = new Process(); worker.StartInfo = info; worker.EnableRaisingEvents = true;
            worker.OutputDataReceived += delegate(object sender, DataReceivedEventArgs e) { if (e.Data != null) Append(e.Data); };
            worker.ErrorDataReceived += delegate(object sender, DataReceivedEventArgs e) { if (e.Data != null) Append(e.Data); };
            worker.Exited += delegate {
                int code = worker.ExitCode;
                try { BeginInvoke((Action)delegate { running = false; status.Text = code == 0 ? "构建完成。" : "操作未完成，请查看下方信息并重试。"; retry.Enabled = code != 0; Cleanup(); }); } catch (InvalidOperationException) { }
            };
            worker.Start(); worker.BeginOutputReadLine(); worker.BeginErrorReadLine();
        } catch (Exception e) { running = false; retry.Enabled = true; status.Text = "启动失败，请查看下方信息。"; Append(e.Message); Cleanup(); }
    }
    void Cleanup() { try { if (script != null) Directory.Delete(Path.GetDirectoryName(script), true); } catch (IOException) { } catch (UnauthorizedAccessException) { } }
    void Closing(object sender, FormClosingEventArgs e) {
        if (!running) { Cleanup(); return; }
        if (MessageBox.Show("正在构建。确定停止并退出吗？", Text, MessageBoxButtons.YesNo) != DialogResult.Yes) { e.Cancel = true; return; }
        try {
            var stop = Process.Start(new ProcessStartInfo("taskkill.exe", "/PID " + worker.Id + " /T /F") { UseShellExecute = false, CreateNoWindow = true });
            stop.WaitForExit(10000); worker.WaitForExit(10000);
        } catch (Exception ex) { MessageBox.Show("无法停止构建：" + ex.Message); e.Cancel = true; return; }
        if (!worker.HasExited) { MessageBox.Show("构建仍在停止，请稍后重试。"); e.Cancel = true; return; }
        Cleanup();
    }
    [STAThread] static void Main() { Application.EnableVisualStyles(); Application.SetCompatibleTextRenderingDefault(false); Application.Run(new Installer()); }
}
