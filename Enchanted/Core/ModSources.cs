using System.Collections.ObjectModel;
using System.Security.Cryptography;
using System.Text.Json;
using System.Windows.Media;

namespace Enchanted;

public sealed record ModFile(string Name, string Url, string? Alt, string? Sha512, string? Sha1, string ProjectId, string Source, List<string> Deps);

public abstract class ModSource
{
    public abstract string Label { get; }
    /// <summary>Найти лучшую Fabric-версию мода (slug или id проекта) под версию игры.</summary>
    public abstract Task<ModFile?> FindAsync(string idOrSlug, string mc, CancellationToken ct);
}

/// <summary>Modrinth и его API-совместимые зеркала (MCIM: mod.mcimirror.top/modrinth).</summary>
public sealed class ModrinthSource : ModSource
{
    readonly HttpClient http;
    readonly string api, label, cdnFrom, cdnTo;
    public ModrinthSource(HttpClient http, string label, string api, string cdnFrom = "", string cdnTo = "")
    { this.http = http; this.label = label; this.api = api.TrimEnd('/'); this.cdnFrom = cdnFrom; this.cdnTo = cdnTo; }
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
        string? alt = null;
        if (cdnFrom != "" && fileUrl.StartsWith(cdnFrom)) fileUrl = cdnTo + fileUrl[cdnFrom.Length..];
        else if (cdnFrom == "" && fileUrl.StartsWith("https://cdn.modrinth.com")) alt = "https://mod.mcimirror.top" + fileUrl["https://cdn.modrinth.com".Length..];

        var hashes = f.GetProperty("hashes");
        var deps = new List<string>();
        foreach (var d in best.GetProperty("dependencies").EnumerateArray())
        {
            if (d.GetProperty("dependency_type").GetString() == "required"
                && d.TryGetProperty("project_id", out var pid) && pid.ValueKind == JsonValueKind.String)
                deps.Add(pid.GetString()!);
        }
        return new ModFile(f.GetProperty("filename").GetString()!, fileUrl, alt,
            hashes.TryGetProperty("sha512", out var h5) ? h5.GetString() : null,
            hashes.TryGetProperty("sha1", out var h1) ? h1.GetString() : null,
            best.GetProperty("project_id").GetString()!, label, deps);
    }
}

/// <summary>CurseForge (нужен ключ в launcher.json) или его зеркало MCIM (mod.mcimirror.top/curseforge).</summary>
public sealed class CurseForgeSource : ModSource
{
    readonly HttpClient http;
    readonly string api, key, label;
    readonly bool mirror;
    public CurseForgeSource(HttpClient http, string label, string api, string key, bool mirror)
    { this.http = http; this.label = label; this.api = api.TrimEnd('/'); this.key = key; this.mirror = mirror; }
    public override string Label => label;

    async Task<JsonDocument?> Get(string path, CancellationToken ct)
    {
        using var req = new HttpRequestMessage(HttpMethod.Get, api + path);
        if (key != "") req.Headers.TryAddWithoutValidation("x-api-key", key);
        using var res = await http.SendAsync(req, ct);
        if (!res.IsSuccessStatusCode) return null;
        return JsonDocument.Parse(await res.Content.ReadAsStringAsync(ct));
    }

    public async Task<string?> SlugOf(string cfId, CancellationToken ct)
    {
        using var d = await Get("/v1/mods/" + cfId[3..], ct);
        return d?.RootElement.GetProperty("data").GetProperty("slug").GetString();
    }

    public override async Task<ModFile?> FindAsync(string idOrSlug, string mc, CancellationToken ct)
    {
        string modId;
        if (idOrSlug.StartsWith("cf:")) modId = idOrSlug[3..];
        else
        {
            using var s = await Get($"/v1/mods/search?gameId=432&classId=6&slug={Uri.EscapeDataString(idOrSlug)}", ct);
            var hit = s?.RootElement.GetProperty("data").EnumerateArray()
                .FirstOrDefault(m => m.GetProperty("slug").GetString() == idOrSlug);
            if (hit == null || hit.Value.ValueKind != JsonValueKind.Object) return null;
            modId = hit.Value.GetProperty("id").GetInt32().ToString();
        }
        using var fl = await Get($"/v1/mods/{modId}/files?gameVersion={mc}&modLoaderType=4&pageSize=20", ct);
        if (fl == null) return null;
        var best = fl.RootElement.GetProperty("data").EnumerateArray()
            .OrderBy(f => f.GetProperty("releaseType").GetInt32() == 1 ? 0 : 1)
            .ThenByDescending(f => f.GetProperty("fileDate").GetString())
            .FirstOrDefault();
        if (best.ValueKind != JsonValueKind.Object) return null;

        int fid = best.GetProperty("id").GetInt32();
        string name = best.GetProperty("fileName").GetString()!;
        string? url = best.TryGetProperty("downloadUrl", out var du) && du.ValueKind == JsonValueKind.String ? du.GetString() : null;
        url ??= $"https://edge.forgecdn.net/files/{fid / 1000}/{fid % 1000}/{name}";
        string? alt = null;
        foreach (var host in new[] { "https://edge.forgecdn.net", "https://mediafilez.forgecdn.net" })
        {
            if (!url.StartsWith(host)) continue;
            var m = "https://mod.mcimirror.top" + url[host.Length..];
            if (mirror) url = m; else alt = m;
            break;
        }
        string? sha1 = null;
        foreach (var h in best.GetProperty("hashes").EnumerateArray())
            if (h.GetProperty("algo").GetInt32() == 1) sha1 = h.GetProperty("value").GetString();
        var deps = new List<string>();
        foreach (var d in best.GetProperty("dependencies").EnumerateArray())
            if (d.GetProperty("relationType").GetInt32() == 3) deps.Add("cf:" + d.GetProperty("modId").GetInt32());
        return new ModFile(name, url, alt, null, sha1, "cf:" + modId, label, deps);
    }
}

/// <summary>Моды, заранее скачанные workflow "Fetch mods" в GitHub Release mods-&lt;версия&gt;.</summary>
public sealed class ReleaseIndex
{
    public Dictionary<string, string> Mods { get; set; } = new();
    public Dictionary<string, string> Deps { get; set; } = new();
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
        // Dependencies are resolved afresh. Remove tracked legacy dependencies first.
        foreach (var (key, filename) in man.ToArray())
        {
            if (!key.StartsWith("dep:")) continue;
            try { File.Delete(Path.Combine(dir, Path.GetFileName(filename))); } catch { }
            man.Remove(key);
        }
        SaveMan();
        const int MaxMods = 12;
        int CountJars() => Directory.GetFiles(dir, "*.jar").Length;

        // 1. свои jar-ы рядом с exe: mods_bundle/<версия>/ или mods_bundle/
        var bundleNames = new List<string>();
        foreach (var b in new[] { Path.Combine(AppContext.BaseDirectory, "mods_bundle", mc), Path.Combine(AppContext.BaseDirectory, "mods_bundle") })
        {
            if (!Directory.Exists(b)) continue;
            foreach (var j in Directory.GetFiles(b, "*.jar"))
            {
                var dst = Path.Combine(dir, Path.GetFileName(j));
                if (!File.Exists(dst))
                {
                    if (CountJars() >= MaxMods) throw new InvalidOperationException("Лимит 12 модов: проверьте mods_bundle и папку mods.");
                    File.Copy(j, dst);
                }
                bundleNames.Add(Path.GetFileName(j).ToLowerInvariant().Replace("-", "").Replace("_", ""));
            }
        }

        // 2. индекс из GitHub Release
        ReleaseIndex? rel = null;
        var relBase = $"https://github.com/{Const.Repo}/releases/download/mods-{mc}/";
        try
        {
            status("Проверяю релиз с модами...");
            using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            cts.CancelAfter(TimeSpan.FromSeconds(10));
            var js = await http.GetStringAsync(relBase + "index.json", cts.Token);
            rel = JsonSerializer.Deserialize<ReleaseIndex>(js, new JsonSerializerOptions { PropertyNameCaseInsensitive = true });
        }
        catch (Exception e) { Log.W("release index: " + e.Message); }

        // 3. онлайн-источники: Modrinth (если доступен), зеркало MCIM, CurseForge
        status("Проверяю связь с Modrinth...");
        bool mrUp = await Ping("https://api.modrinth.com/v2/", 5);
        var sources = new List<ModSource>();
        if (mrUp) sources.Add(new ModrinthSource(http, "Modrinth", "https://api.modrinth.com"));
        sources.Add(new ModrinthSource(http, "MCIM", "https://mod.mcimirror.top/modrinth", "https://cdn.modrinth.com", "https://mod.mcimirror.top"));
        CurseForgeSource? cf = null;
        if (cfg.CurseForgeKey != "") sources.Add(cf = new CurseForgeSource(http, "CurseForge", "https://api.curseforge.com", cfg.CurseForgeKey, false));
        sources.Add(cf ??= new CurseForgeSource(http, "CurseForge (MCIM)", "https://mod.mcimirror.top/curseforge", "", true));
        Log.W($"моды {mc}: release={(rel != null)}, modrinth={mrUp}");

        var done = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var pending = new Queue<string>();
        int missing = 0;

        async Task<bool> Install(string key, ModFile f, string via)
        {
            var path = Path.Combine(dir, f.Name);
            if (!File.Exists(path))
            {
                if (CountJars() >= MaxMods) throw new InvalidOperationException("Лимит 12 модов: удалите лишние пользовательские моды или пересмотрите зависимости.");
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
            if (bundleNames.Any(n => n.Contains(slug.Replace("-", "")))) { row(new ModRow { Name = slug, State = "из mods_bundle", Dot = Green }); continue; }

            status($"Мод {slug}...");
            try
            {
                if (rel != null && rel.Mods.TryGetValue(slug, out var rf))
                {
                    await Install(slug, new ModFile(rf, relBase + Uri.EscapeDataString(rf), null, null, null, slug, "Release", new()), "Release");
                    row(new ModRow { Name = slug, State = "скачан (Release)", Dot = Blue });
                    continue;
                }
            }
            catch (Exception e) { Log.W($"release {slug}: {e.Message}"); }

            ModFile? file = null;
            foreach (var src in sources)
            {
                try
                {
                    file = await src.FindAsync(slug, mc, ct);
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
            else { missing++; row(new ModRow { Name = slug, State = "не найден", Dot = Red }); }
        }

        // обязательные зависимости
        if (rel != null)
            foreach (var (s, fn) in rel.Deps)
            {
                try { if (!(man.TryGetValue("dep:" + s, out var h) && File.Exists(Path.Combine(dir, h)))) await Install("dep:" + s, new ModFile(fn, relBase + Uri.EscapeDataString(fn), null, null, null, s, "Release", new()), "Release"); }
                catch (Exception e) { Log.W($"dep {s}: {e.Message}"); }
            }
        int guard = 0;
        while (pending.Count > 0 && guard++ < 60)
        {
            var id = pending.Dequeue();
            if (done.Contains(id) || man.ContainsKey("dep:" + id)) continue;
            done.Add(id);
            status("Зависимость " + id + "...");
            try
            {
                if (id.StartsWith("cf:") && cf != null)
                {
                    var sl = await cf.SlugOf(id, ct);
                    if (sl != null && done.Contains(sl)) continue;
                }
                foreach (var src in sources)
                {
                    if ((src is CurseForgeSource) != id.StartsWith("cf:")) continue;
                    var f = await src.FindAsync(id, mc, ct);
                    if (f == null) continue;
                    await Install("dep:" + id, f, src.Label);
                    foreach (var d in f.Deps) pending.Enqueue(d);
                    break;
                }
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested) { throw; }
            catch (Exception e) { Log.W($"dep {id}: {e.Message}"); }
        }
        return missing;
    }

    async Task Download(ModFile f, string path, CancellationToken ct)
    {
        Exception? last = null;
        foreach (var url in new[] { f.Url, f.Alt }.Where(u => u != null))
        {
            for (int attempt = 0; attempt < 3; attempt++)
            {
                try
                {
                    // 5 секунд тишины - обрыв и повтор (12 с на ответ сервера)
                    using var sc = CancellationTokenSource.CreateLinkedTokenSource(ct);
                    sc.CancelAfter(TimeSpan.FromSeconds(12));
                    using var resp = await http.GetAsync(url!, HttpCompletionOption.ResponseHeadersRead, sc.Token);
                    resp.EnsureSuccessStatusCode();
                    using var stream = await resp.Content.ReadAsStreamAsync(sc.Token);
                    var ms = new MemoryStream();
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
                catch (Exception e) { last = e; }
            }
        }
        throw last ?? new IOException("не удалось скачать " + f.Name);
    }
}
