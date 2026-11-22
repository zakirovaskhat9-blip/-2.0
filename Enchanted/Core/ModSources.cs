using System.Collections.ObjectModel;
using System.Security.Cryptography;
using System.Text.Json;
using System.Windows.Media;

namespace Enchanted;

public sealed record ModFile(string Name, string Url, string? Sha512, string? Sha1, string ProjectId, string Source, List<string> Deps);

public abstract class ModSource
{
    public abstract string Label { get; }
    /// <summary>Найти лучшую Fabric-версию мода (slug или id проекта) под версию игры.</summary>
    public abstract Task<ModFile?> FindAsync(string idOrSlug, string mc, CancellationToken ct);
}

/// <summary>Only official Modrinth API and CDN hosts.</summary>
public sealed class ModrinthSource : ModSource
{
    readonly HttpClient http;
    readonly string api, label;
    public ModrinthSource(HttpClient http, string label, string api)
    { this.http = http; this.label = label; this.api = api.TrimEnd('/'); }
    public override string Label => label;

    public override async Task<ModFile?> FindAsync(string id, string mc, CancellationToken ct)
    {
        var url = $"{api}/v2/project/{Uri.EscapeDataString(id)}/version?loaders=%5B%22fabric%22%5D&game_versions=%5B%22{mc}%22%5D";
        using var res = await http.GetAsync(url, ct);
        if (!res.IsSuccessStatusCode) return null;
        using var doc = JsonDocument.Parse(await res.Content.ReadAsStringAsync(ct));
        var best = doc.RootElement.EnumerateArray()
            .OrderByDescending(v => v.GetProperty("version_type").GetString() == "release")
            .ThenByDescending(v => v.GetProperty("date_published").GetString())
            .FirstOrDefault();
        if (best.ValueKind != JsonValueKind.Object) return null;

        var files = best.GetProperty("files").EnumerateArray().ToList();
        if (files.Count == 0) return null;
        var f = files.FirstOrDefault(x => x.TryGetProperty("primary", out var p) && p.GetBoolean());
        if (f.ValueKind != JsonValueKind.Object) f = files[0];
        var fileUrl = f.GetProperty("url").GetString()!;
        if (!Uri.TryCreate(fileUrl, UriKind.Absolute, out var safe)
            || safe.Scheme != Uri.UriSchemeHttps || safe.Host != "cdn.modrinth.com")
            throw new InvalidDataException("Modrinth returned a file outside its official CDN.");

        var hashes = f.GetProperty("hashes");
        var deps = new List<string>();
        foreach (var d in best.GetProperty("dependencies").EnumerateArray())
        {
            if (d.GetProperty("dependency_type").GetString() == "required"
                && d.TryGetProperty("project_id", out var pid) && pid.ValueKind == JsonValueKind.String)
                deps.Add(pid.GetString()!);
        }
        return new ModFile(f.GetProperty("filename").GetString()!, fileUrl,
            hashes.TryGetProperty("sha512", out var h5) ? h5.GetString() : null,
            hashes.TryGetProperty("sha1", out var h1) ? h1.GetString() : null,
            best.GetProperty("project_id").GetString()!, label, deps);
    }
}

public sealed class ModManager
{
    readonly HttpClient http;
    readonly Config cfg;
    public ModManager(HttpClient http, Config cfg) { this.http = http; this.cfg = cfg; }

    static Brush Fz(byte r, byte g, byte b)
    {
        var br = new SolidColorBrush(Color.FromRgb(r, g, b));
        br.Freeze(); // заморожена - можно использовать из любого потока
        return br;
    }
    static readonly Brush Green = Fz(0xF2, 0xF2, 0xF2);
    static readonly Brush Blue = Fz(0x9A, 0x9A, 0x9A);
    static readonly Brush Red = Fz(0x5A, 0x5A, 0x5A);

    async Task<bool> Ping(string url, int sec)
    {
        try
        {
            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(sec));
            using var r = await http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, cts.Token);
            return r.IsSuccessStatusCode;
        }
        catch { return false; }
    }

    /// <summary>Докачивает недостающие моды. Возвращает число не найденных.</summary>
    public async Task<int> EnsureAsync(string mc, Action<string> status, Action<ModRow> row, CancellationToken ct)
    {
        var dir = Dirs.Mods(mc);
        Directory.CreateDirectory(dir);
        var manPath = Path.Combine(dir, ".enchanted-mods.json");
        var man = new Dictionary<string, string>();
        try { if (File.Exists(manPath)) man = JsonSerializer.Deserialize<Dictionary<string, string>>(File.ReadAllText(manPath)) ?? new(); } catch { }
        void SaveMan() { try { File.WriteAllText(manPath, JsonSerializer.Serialize(man)); } catch { } }

        // Clean up mods that this launcher previously installed but are no longer selected.
        // Never remove jars that are not recorded in the launcher manifest.
        var selected = new HashSet<string>(Const.Mods(mc), StringComparer.OrdinalIgnoreCase);
        foreach (var (key, filename) in man.ToArray())
        {
            if (key.StartsWith("dep:") || selected.Contains(key)) continue;
            try { File.Delete(Path.Combine(dir, Path.GetFileName(filename))); } catch { }
            man.Remove(key);
        }
        // Не удаляем зависимости при каждом старте: это вызывало
        // повторное скачивание даже при полностью установленной игре.
        bool lostDependency = false;
        foreach (var (key, filename) in man.ToArray())
            if (key.StartsWith("dep:") && !File.Exists(Path.Combine(dir, Path.GetFileName(filename))))
            {
                man.Remove(key);
                lostDependency = true;
            }
        SaveMan();
        const int MaxMods = 24;
        int CountJars() => Directory.GetFiles(dir, "*.jar").Length;
        var completeFile = Path.Combine(dir, ".lt6ms-mods-ready.txt");
        var expectedMods = string.Join("|", Const.Mods(mc));

        // Только после полного успешного прохода можно обходиться без сети.
        if (!cfg.VerifyFiles && !lostDependency && File.Exists(completeFile) &&
            File.ReadAllText(completeFile) == expectedMods &&
            Const.Mods(mc).All(slug =>
                man.TryGetValue(slug, out var file) && File.Exists(Path.Combine(dir, Path.GetFileName(file)))) &&
            man.Where(kv => kv.Key.StartsWith("dep:")).All(kv =>
                File.Exists(Path.Combine(dir, Path.GetFileName(kv.Value)))))
        {
            foreach (var slug in Const.Mods(mc))
                row(new ModRow { Name = slug, State = "установлен", Dot = Green });
            status("Все моды на месте — запускаю без запросов к серверам...");
            return 0;
        }

        async Task<ModFile?> FindQuick(ModSource source, string id)
        {
            using var requestCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            requestCts.CancelAfter(TimeSpan.FromSeconds(3));
            return await source.FindAsync(id, mc, requestCts.Token);
        }

        // Only the official source is allowed. Existing cached, installed jars
        // are reused, but new mod downloads must come from the Modrinth API.
        status("Загружаю моды только с Modrinth...");
        var sources = new List<ModSource> {
            new ModrinthSource(http, "Modrinth", "https://api.modrinth.com")
        };
        Log.W("Mod downloads: official api.modrinth.com / cdn.modrinth.com only");

        var done = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var pending = new Queue<string>();
        int missing = 0;
        bool depFailed = false;

        async Task<bool> Install(string key, ModFile f, string via)
        {
            var path = Path.Combine(dir, f.Name);
            if (!File.Exists(path))
            {
                if (CountJars() >= MaxMods) throw new InvalidOperationException("Лимит 24 модов: удалите лишние пользовательские моды или пересмотрите зависимости.");
                await Download(f, path, ct);
            }
            if (man.TryGetValue(key, out var old) && old != f.Name) { try { File.Delete(Path.Combine(dir, old)); } catch { } }
            man[key] = f.Name;
            SaveMan();
            Log.W($"OK {key} <- {via}: {f.Name}");
            return true;
        }

        foreach (var slug in Const.Mods(mc))
        {
            ct.ThrowIfCancellationRequested();
            done.Add(slug);
            if (man.TryGetValue(slug, out var have) && File.Exists(Path.Combine(dir, have))) { row(new ModRow { Name = slug, State = "установлен", Dot = Green }); continue; }
            status($"Мод {slug} — Modrinth...");
            ModFile? file = null;
            foreach (var src in sources)
            {
                try
                {
                    file = await FindQuick(src, slug);
                    if (file != null) { await Install(slug, file, src.Label); break; }
                }
                catch (OperationCanceledException) when (ct.IsCancellationRequested) { throw; }
                catch (Exception e) { Log.W($"{src.Label} {slug}: {e.GetBaseException().Message}"); file = null; }
            }
            if (file != null)
            {
                done.Add(file.ProjectId);
                foreach (var d in file.Deps) pending.Enqueue(d);
                row(new ModRow { Name = slug, State = "скачан (" + file.Source + ")", Dot = Blue });
            }
            else { missing++; row(new ModRow { Name = slug, State = "Modrinth недоступен", Dot = Red }); }
        }

        // обязательные зависимости
        int guard = 0;
        while (pending.Count > 0 && guard++ < 60)
        {
            var id = pending.Dequeue();
            if (done.Contains(id) || man.ContainsKey("dep:" + id)) continue;
            done.Add(id);
            status("Зависимость " + id + "...");
            try
            {
                bool foundDependency = false;
                foreach (var src in sources)
                {
                    var f = await FindQuick(src, id);
                    if (f == null) continue;
                    await Install("dep:" + id, f, src.Label);
                    foreach (var d in f.Deps) pending.Enqueue(d);
                    foundDependency = true;
                    break;
                }
                if (!foundDependency) depFailed = true;
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested) { throw; }
            catch (Exception e) { depFailed = true; Log.W($"dep {id}: {e.Message}"); }
        }
        if (missing == 0 && !depFailed && pending.Count == 0)
        {
            try { File.WriteAllText(completeFile, expectedMods); }
            catch (Exception e) { Log.W("mods cache marker: " + e.Message); }
        }
        else
        {
            try { File.Delete(completeFile); } catch { }
        }
        return missing;
    }

    async Task Download(ModFile f, string path, CancellationToken ct)
    {
        Exception? last = null;
        if (!Uri.TryCreate(f.Url, UriKind.Absolute, out var official)
            || official.Scheme != Uri.UriSchemeHttps || official.Host != "cdn.modrinth.com")
            throw new InvalidDataException("Mod JAR downloads must use the official Modrinth CDN.");
        var urls = new[] { f.Url };
        // Сначала один раз пробуем каждый адрес. При ошибке переключаемся
        // на зеркало за 250 мс, а не трижды ждём один нерабочий сервер.
        for (int round = 0; round < 2; round++)
        {
            foreach (var url in urls)
            {
                ct.ThrowIfCancellationRequested();
                try
                {
                    // Не более 5 с без ответа / без очередного блока данных.
                    using var sc = CancellationTokenSource.CreateLinkedTokenSource(ct);
                    sc.CancelAfter(TimeSpan.FromSeconds(5));
                    using var resp = await http.GetAsync(url!, HttpCompletionOption.ResponseHeadersRead, sc.Token);
                    resp.EnsureSuccessStatusCode();
                    using var stream = await resp.Content.ReadAsStreamAsync(sc.Token);
                    using var ms = new MemoryStream();
                    var buf = new byte[81920];
                    int n;
                    while ((n = await stream.ReadAsync(buf, sc.Token)) > 0)
                    {
                        ms.Write(buf, 0, n);
                        sc.CancelAfter(TimeSpan.FromSeconds(5));
                    }
                    var bytes = ms.ToArray();
                    if (f.Sha512 != null && !Convert.ToHexString(SHA512.HashData(bytes)).Equals(f.Sha512, StringComparison.OrdinalIgnoreCase))
                        throw new InvalidDataException("хеш SHA-512 не совпал");
                    if (f.Sha1 != null && f.Sha512 == null && !Convert.ToHexString(SHA1.HashData(bytes)).Equals(f.Sha1, StringComparison.OrdinalIgnoreCase))
                        throw new InvalidDataException("хеш SHA-1 не совпал");
                    var tmp = path + ".part";
                    await File.WriteAllBytesAsync(tmp, bytes, ct);
                    File.Move(tmp, path, true);
                    return;
                }
                catch (OperationCanceledException) when (ct.IsCancellationRequested) { throw; }
                catch (Exception e)
                {
                    last = e;
                    Log.W($"мод {f.Name}, {url}, раунд {round + 1}: {e.GetBaseException().Message}");
                    await Task.Delay(250, ct);
                }
            }
        }
        throw last ?? new IOException("не удалось скачать " + f.Name);
    }
}
