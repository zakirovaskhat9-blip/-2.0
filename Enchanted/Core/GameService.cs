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

    /// <summary>LT6 работает на Minecraft 1.21.11; обязательный JAR вшит в EXE.</summary>
    void InstallClientMod(string mc)
    {
        var dir = Dirs.Mods(mc);
        Directory.CreateDirectory(dir);
        var target = Path.Combine(dir, "lt6.jar");

        if (mc != "1.21.11")
        {
            // На 1.21.4 установленный LT6 1.21.11 не должен ломать игру.
            foreach (var old in Directory.GetFiles(dir, "lt6*.jar"))
                try { File.Delete(old); } catch (Exception e) { Log.W("LT6 cleanup: " + e.Message); }
            return;
        }

        using var source = Assembly.GetExecutingAssembly().GetManifestResourceStream("lt6.jar")
            ?? throw new InvalidOperationException(
                "LT6 не встроен в EXE лаунчера. Пересобери LT6MS Launcher через GitHub Actions; файл lt6.jar обязателен.");

        // Не оставляем нулевой/битый lt6.jar: скачанный мод должен содержать
        // Fabric manifest и клиентский entrypoint.
        var temp = target + ".installing";
        try
        {
            using (var output = File.Create(temp)) source.CopyTo(output);
            using (var archive = System.IO.Compression.ZipFile.OpenRead(temp))
            {
                if (archive.GetEntry("fabric.mod.json") == null ||
                    archive.GetEntry("dev/lt6/client/LT6Client.class") == null)
                    throw new InvalidDataException("Неверный lt6.jar: нет fabric.mod.json или LT6Client.class");
            }
            // Не переписываем существующий корректный JAR на каждом запуске.
            // Это предотвращает ошибки доступа, когда предыдущий Minecraft ещё запущен.
            bool sameJar = false;
            if (File.Exists(target))
            {
                using var candidate = File.OpenRead(temp);
                using var installed = File.OpenRead(target);
                sameJar = candidate.Length == installed.Length &&
                    System.Security.Cryptography.SHA256.HashData(candidate).AsSpan()
                        .SequenceEqual(System.Security.Cryptography.SHA256.HashData(installed));
            }
            // Убираем только копии LT6 под известным устаревшим именем.
            foreach (var old in Directory.GetFiles(dir, "enchanted-client*.jar"))
                File.Delete(old);
            foreach (var old in Directory.GetFiles(dir, "lt6*.jar"))
                if (!Path.GetFullPath(old).Equals(Path.GetFullPath(target), StringComparison.OrdinalIgnoreCase))
                    File.Delete(old);
            if (!sameJar) File.Move(temp, target, overwrite: true);
            Log.W($"LT6 проверен: {target}, {new FileInfo(target).Length} байт, {(sameJar ? "без замены" : "обновлён")}");
        }
        catch (Exception ex)
        {
            Log.W("LT6 install failed: " + ex);
            throw new IOException("Не удалось установить визуальный мод LT6: " + ex.GetBaseException().Message, ex);
        }
        finally
        {
            try { if (File.Exists(temp)) File.Delete(temp); } catch { }
        }
    }

    long lastTick = Environment.TickCount64;
    volatile bool started;
    void Touch()
    {
        Interlocked.Exchange(ref lastTick, Environment.TickCount64);
        started = true;
    }

    /// <summary>
    /// Не отменяем всю установку при краткой паузе сети или локальной проверке.
    /// Переключение зеркал выполняет MirrorHandler; установка повторяется лишь
    /// при действительно длительном зависании или сетевой ошибке.
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
                        if (quietMs < (started ? 60000 : 90000)) continue;
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
                ? $"Загрузка долго не отвечает — повторяю ({failures}/8)..."
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
                        Timeout = TimeSpan.FromSeconds(75)
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
            try { Skies.Seed(); }
            catch (Exception e) { Log.W("LT6 sky packs: " + e); }
        }
        if (mc == "1.21.11")
        {
            var nick = string.IsNullOrWhiteSpace(cfg.Nick) ? "Player" : cfg.Nick.Trim();
            SkinStore.WriteModConfig(mc, cfg, nick);
            _ = Task.Run(async () => { var err = await SkinStore.UploadAsync(cfg, nick); if (err != null) Log.W("скин при запуске: " + err); });
        }
        var mods = new ModManager(Http, cfg);
        int missing = await mods.EnsureAsync(mc, status, row, ct);
        if (mc == "1.21.11")
        {
            var modsDir = Dirs.Mods(mc);
            if (!File.Exists(Path.Combine(modsDir, "lt6.jar")))
                throw new FileNotFoundException("LT6 отсутствует после установки.", Path.Combine(modsDir, "lt6.jar"));
            // LT6 использует Fabric API; без него запуск не имеет смысла.
            if (!Directory.EnumerateFiles(modsDir, "*.jar")
                    .Any(f => Path.GetFileName(f).StartsWith("fabric-api-", StringComparison.OrdinalIgnoreCase)))
                throw new IOException("Не удалось установить Fabric API — он обязателен для LT6. Проверь сеть и launcher.log.");
        }

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
        // CmlLib может сгенерировать аргументы без --gameDir. В этом случае
        // моды из Dirs.Mods(mc) иначе будут установлены не туда, где их ищет Fabric.
        const string gameDirPattern = @"--gameDir\s+(""[^""]*""|\S+)";
        var gameDirOption = "--gameDir \"" + gameDir + "\"";
        proc.StartInfo.Arguments = Regex.IsMatch(proc.StartInfo.Arguments, gameDirPattern)
            ? Regex.Replace(proc.StartInfo.Arguments, gameDirPattern, gameDirOption)
            : proc.StartInfo.Arguments + " " + gameDirOption;
        proc.StartInfo.WorkingDirectory = gameDir;
        Log.W($"Minecraft version={versionName}, gameDir={gameDir}, mods={Dirs.Mods(mc)}, lt6={(File.Exists(Path.Combine(Dirs.Mods(mc), "lt6.jar")) ? "installed" : "not-installed")}");
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
