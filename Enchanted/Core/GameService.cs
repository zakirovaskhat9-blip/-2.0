using System.Diagnostics;
using System.Reflection;
using System.Text.RegularExpressions;
using CmlLib.Core;
using CmlLib.Core.Auth;
using CmlLib.Core.ModLoaders.FabricMC;
using CmlLib.Core.ProcessBuilder;

namespace Enchanted;

public sealed class GameService
{
    public readonly MirrorHandler Mirror = new();
    public readonly HttpClient Http;
    readonly Config cfg;

    public GameService(Config cfg)
    {
        this.cfg = cfg;
        Http = new HttpClient(Mirror) { Timeout = Timeout.InfiniteTimeSpan };
        Http.DefaultRequestHeaders.UserAgent.ParseAdd(Const.UserAgent);
    }

    /// <summary>Мод-меню Enchanted (левый Shift) кладём только на 1.21.11, на остальных версиях убираем.</summary>
    void InstallClientMod(string mc)
    {
        var dir = Dirs.Mods(mc);
        Directory.CreateDirectory(dir);
        foreach (var f in Directory.GetFiles(dir, "enchanted-client*.jar")) { try { File.Delete(f); } catch { } }
        if (mc != "1.21.11") return;
        using var s = Assembly.GetExecutingAssembly().GetManifestResourceStream("enchanted-client.jar");
        if (s == null) { Log.W("клиентский мод не встроен в exe"); return; }
        using var o = File.Create(Path.Combine(dir, "enchanted-client.jar"));
        s.CopyTo(o);
    }

    long lastTick = Environment.TickCount64;
    volatile bool started;
    void Touch() { Interlocked.Exchange(ref lastTick, Environment.TickCount64); started = true; }

    /// <summary>
    /// Выполняет загрузку; если 5 секунд нет ни байта прогресса - прерывает и запускает заново
    /// (уже скачанные файлы CmlLib пропускает). До первого прогресса даём 20 с на подготовку.
    /// </summary>
    async Task Guard(Func<CancellationToken, Task> work, Action<string> status, CancellationToken ct)
    {
        int stalls = 0, errors = 0;
        while (true)
        {
            ct.ThrowIfCancellationRequested();
            using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            started = false;
            Touch();
            started = false;
            bool stalled = false;
            _ = Task.Run(async () =>
            {
                while (!cts.IsCancellationRequested)
                {
                    await Task.Delay(500).ConfigureAwait(false);
                    long limit = started ? 5000 : 20000;
                    if (Environment.TickCount64 - Interlocked.Read(ref lastTick) > limit)
                    {
                        stalled = true;
                        try { cts.Cancel(); } catch { }
                        return;
                    }
                }
            });
            try
            {
                await work(cts.Token).WaitAsync(cts.Token);
                return;
            }
            catch (Exception e) when (!ct.IsCancellationRequested)
            {
                if (stalled)
                {
                    if (++stalls > 15) throw new IOException("сервер постоянно молчит, попробуй другой источник загрузки");
                    Log.W($"загрузка встала, перезапуск #{stalls}");
                    status($"Сервер молчит 5 с - перезапускаю загрузку (попытка {stalls})...");
                }
                else
                {
                    if (++errors > 3) throw;
                    Log.W("ошибка загрузки, повтор: " + e.Message);
                    status("Ошибка загрузки, повторяю...");
                    await Task.Delay(1000, ct);
                }
            }
            finally { try { cts.Cancel(); } catch { } }
        }
    }

    public async Task LaunchAsync(Action<string> status, Action<double> progress, Action<ModRow> row, CancellationToken ct)
    {
        var mc = cfg.Version;
        Mirror.PreferMirror = cfg.Mirrors;
        Directory.CreateDirectory(Dirs.Root);
        var gameDir = Dirs.Game(mc);
        Directory.CreateDirectory(gameDir);

        var path = new MinecraftPath(Dirs.Root);
        var launcher = new MinecraftLauncher(MinecraftLauncherParameters.CreateDefault(path, Http));
        launcher.ByteProgressChanged += (_, _) => Touch();
        launcher.FileProgressChanged += (_, e) =>
        {
            Touch();
            if (e.TotalTasks > 0) progress((double)e.ProgressedTasks / e.TotalTasks);
            status($"Файлы игры: {e.ProgressedTasks}/{e.TotalTasks}");
        };

        // Fabric
        status("Fabric для " + mc + "...");
        string versionName;
        var marker = Path.Combine(Dirs.Root, "installed-" + mc + ".txt");
        var fabMarker = Path.Combine(Dirs.Root, "fabric-" + mc + ".txt");
        if (File.Exists(fabMarker) && !cfg.VerifyFiles) versionName = File.ReadAllText(fabMarker).Trim();
        else
        {
            string vn = "";
            await Guard(async t => { vn = await new FabricInstaller(Http).Install(mc, path); }, status, ct);
            versionName = vn;
            File.WriteAllText(fabMarker, versionName);
        }

        // моды
        InstallClientMod(mc);
        var mods = new ModManager(Http, cfg);
        int missing = await mods.EnsureAsync(mc, status, row, ct);

        var opt = new MLaunchOption
        {
            Session = MSession.CreateOfflineSession(string.IsNullOrWhiteSpace(cfg.Nick) ? "Player" : cfg.Nick.Trim()),
            MaximumRamMb = cfg.RamMb,
            MinimumRamMb = Math.Min(1024, cfg.RamMb),
        };

        Process proc;
        if (File.Exists(marker) && !cfg.VerifyFiles)
        {
            status("Быстрый запуск...");
            proc = await launcher.BuildProcessAsync(versionName, opt, ct);
        }
        else
        {
            status("Проверяю и докачиваю файлы игры...");
            Process? built = null;
            await Guard(async t => { built = await launcher.InstallAndBuildProcessAsync(versionName, opt, t); }, status, ct);
            proc = built!;
            File.WriteAllText(marker, versionName);
        }

        // у каждой версии своя папка игрока (библиотеки общие)
        proc.StartInfo.Arguments = Regex.Replace(proc.StartInfo.Arguments, @"--gameDir\s+(""[^""]*""|\S+)", "--gameDir \"" + gameDir + "\"");
        proc.StartInfo.WorkingDirectory = gameDir;
        proc.StartInfo.RedirectStandardOutput = true;
        proc.StartInfo.RedirectStandardError = true;
        var gameLog = Path.Combine(Dirs.Root, "game-output.log");
        File.WriteAllText(gameLog, "");
        void Pipe(object _, DataReceivedEventArgs e) { if (e.Data != null) try { File.AppendAllText(gameLog, e.Data + Environment.NewLine); } catch { } }
        proc.OutputDataReceived += Pipe;
        proc.ErrorDataReceived += Pipe;
        proc.Start();
        proc.BeginOutputReadLine();
        proc.BeginErrorReadLine();
        progress(1);
        status(missing > 0 ? $"Игра запущена (модов не найдено: {missing}, см. launcher.log)" : "Игра запущена");
    }
}
