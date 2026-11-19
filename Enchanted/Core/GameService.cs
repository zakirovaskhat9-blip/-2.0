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
        Mirror.OnRequestAttempt = Touch;
    }

    /// <summary>LT6 визуальный мод (левый Shift) кладём только на 1.21.11, на остальных версиях убираем.</summary>
    void InstallClientMod(string mc)
    {
        var dir = Dirs.Mods(mc);
        Directory.CreateDirectory(dir);
        foreach (var f in Directory.GetFiles(dir, "enchanted-client*.jar")) { try { File.Delete(f); } catch { } }
        // Clean previous LT6 editions as well so only one visual jar is installed.
        foreach (var f in Directory.GetFiles(dir, "lt6*.jar")) { try { File.Delete(f); } catch { } }
        if (mc != "1.21.11") return;
        using var s = Assembly.GetExecutingAssembly().GetManifestResourceStream("lt6.jar");
        if (s == null) { Log.W("клиентский мод не встроен в exe"); return; }
        using var o = File.Create(Path.Combine(dir, "lt6.jar"));
        s.CopyTo(o);
    }

    long lastTick = Environment.TickCount64;
    volatile bool started;
    void Touch()
    {
        Interlocked.Exchange(ref lastTick, Environment.TickCount64);
        started = true;
    }

    /// <summary>
    /// Пять секунд тишины после начала сетевой работы — отмена и быстрый повтор.
    /// До первого запроса допускаем 8 секунд на локальную подготовку файлов,
    /// чтобы не обрывать проверку файлов без сетевого трафика.
    /// </summary>
    async Task Guard(Func<CancellationToken, Task> work, Action<string> status, CancellationToken ct)
    {
        int failures = 0;
        while (true)
        {
            ct.ThrowIfCancellationRequested();
            using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            started = false;
            Interlocked.Exchange(ref lastTick, Environment.TickCount64);
            int stalled = 0;

            var watcher = Task.Run(async () =>
            {
                try
                {
                    while (!cts.IsCancellationRequested)
                    {
                        await Task.Delay(200, cts.Token).ConfigureAwait(false);
                        long quietMs = Environment.TickCount64 - Interlocked.Read(ref lastTick);
                        if (quietMs < (started ? 5000 : 8000)) continue;
                        Interlocked.Exchange(ref stalled, 1);
                        cts.Cancel();
                        return;
                    }
                }
                catch (OperationCanceledException) { }
            });

            Exception? problem = null;
            try
            {
                await work(cts.Token).WaitAsync(cts.Token);
                return;
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested) { throw; }
            catch (Exception e) when (!ct.IsCancellationRequested) { problem = e; }
            finally
            {
                cts.Cancel();
                await watcher.ConfigureAwait(false);
            }

            if (++failures > 8)
                throw new IOException("Загрузка прервалась несколько раз. Проверь сеть и launcher.log.", problem);

            bool wasStalled = Interlocked.CompareExchange(ref stalled, 0, 0) == 1;
            Log.W($"загрузка: повтор {failures}/8, таймаут={wasStalled}: {problem?.GetBaseException().Message}");
            status(wasStalled
                ? $"Нет ответа 5 с — переключаю источник и повторяю ({failures}/8)..."
                : $"Ошибка загрузки — быстро повторяю ({failures}/8)...");
            await Task.Delay(wasStalled ? 250 : 500, ct);
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
            // FabricInstaller.Install не принимает CancellationToken. Не запускаем
            // его повторно до завершения предыдущей попытки (иначе файлы конфликтуют).
            string vn = "";
            for (int attempt = 1; attempt <= 3; attempt++)
            {
                ct.ThrowIfCancellationRequested();
                try
                {
                    using var fabricHttp = new HttpClient(new MirrorHandler { PreferMirror = cfg.Mirrors })
                    {
                        Timeout = TimeSpan.FromSeconds(12)
                    };
                    fabricHttp.DefaultRequestHeaders.UserAgent.ParseAdd(Const.UserAgent);
                    vn = await new FabricInstaller(fabricHttp).Install(mc, path);
                    break;
                }
                catch (Exception e) when (attempt < 3 && !ct.IsCancellationRequested)
                {
                    Log.W($"Fabric: повтор {attempt}/3: {e.GetBaseException().Message}");
                    status($"Fabric: переключаю источник, повтор {attempt}/3...");
                    await Task.Delay(250, ct);
                }
            }
            ct.ThrowIfCancellationRequested();
            versionName = vn;
            File.WriteAllText(fabMarker, versionName);
        }

        // моды
        InstallClientMod(mc);
        if (mc == "1.21.11")
        {
            var nick = string.IsNullOrWhiteSpace(cfg.Nick) ? "Player" : cfg.Nick.Trim();
            SkinStore.WriteModConfig(mc, cfg, nick);
            _ = Task.Run(async () => { var err = await SkinStore.UploadAsync(cfg, nick); if (err != null) Log.W("скин при запуске: " + err); });
        }
        var mods = new ModManager(Http, cfg);
        int missing = await mods.EnsureAsync(mc, status, row, ct);

        var opt = new MLaunchOption
        {
            Session = MSession.CreateOfflineSession(string.IsNullOrWhiteSpace(cfg.Nick) ? "Player" : cfg.Nick.Trim()),
            MaximumRamMb = cfg.RamMb,
            MinimumRamMb = Math.Min(1024, cfg.RamMb),
        };

        Process? proc = null;
        if (File.Exists(marker) && !cfg.VerifyFiles)
        {
            status("Быстрый запуск...");
            try { proc = await launcher.BuildProcessAsync(versionName, opt, ct); }
            catch (OperationCanceledException) when (ct.IsCancellationRequested) { throw; }
            catch (Exception e)
            {
                // Повреждённый или неполный кэш не должен ломать все следующие запуски.
                Log.W("быстрый запуск не удался, восстанавливаю файлы: " + e);
                try { File.Delete(marker); } catch { }
                status("Восстанавливаю недостающие файлы игры...");
            }
        }
        if (proc == null)
        {
            status("Проверяю и докачиваю файлы игры...");
            Process? built = null;
            await Guard(async t => { built = await launcher.InstallAndBuildProcessAsync(versionName, opt, t); }, status, ct);
            proc = built ?? throw new IOException("Не удалось подготовить процесс Minecraft.");
            File.WriteAllText(marker, versionName);
        }

        // у каждой версии своя папка игрока (библиотеки общие)
        proc.StartInfo.Arguments = Regex.Replace(proc.StartInfo.Arguments, @"--gameDir\s+(""[^""]*""|\S+)", "--gameDir \"" + gameDir + "\"");
        proc.StartInfo.WorkingDirectory = gameDir;
        proc.StartInfo.RedirectStandardOutput = true;
        proc.StartInfo.RedirectStandardError = true;
        var gameLog = Path.Combine(Dirs.Root, "game-output.log");
        File.WriteAllText(gameLog, "");
        var gameLogLock = new object();
        void Pipe(object _, DataReceivedEventArgs e)
        {
            if (e.Data == null) return;
            try { lock (gameLogLock) File.AppendAllText(gameLog, e.Data + Environment.NewLine); }
            catch { }
        }
        proc.OutputDataReceived += Pipe;
        proc.ErrorDataReceived += Pipe;
        proc.EnableRaisingEvents = true;
        proc.Exited += (_, _) =>
        {
            try
            {
                int exitCode = proc.ExitCode;
                Log.W($"Minecraft завершился с кодом {exitCode}, лог: {gameLog}");
                if (exitCode != 0) status($"Minecraft закрылся с ошибкой {exitCode}. Проверь game-output.log.");
            }
            catch (Exception e) { Log.W("ожидание завершения игры: " + e.Message); }
        };
        if (!proc.Start()) throw new IOException("Java-процесс Minecraft не запустился.");
        proc.BeginOutputReadLine();
        proc.BeginErrorReadLine();
        // Ранние падения больше не скрываются сообщением «Игра запущена».
        await Task.Delay(750, ct);
        if (proc.HasExited)
            throw new IOException($"Minecraft завершился сразу (код {proc.ExitCode}). Проверь {gameLog}.");
        progress(1);
        status(missing > 0 ? $"Игра запущена (модов не найдено: {missing}, см. launcher.log)" : "Игра запущена");
    }
}
