using System.Text.Json;

namespace Enchanted;

/// <summary>Репозиторий с Release-ами модов (mods-&lt;версия&gt;) и список поддерживаемых версий.</summary>
public static class Const
{
    public const string Repo = "zakirovaskhat9-blip/-2.0";
    public static readonly string[] Versions = { "1.21.11", "1.21.4" };
    public const string UserAgent = "LT6MSLauncher/2.0 (+https://github.com/zakirovaskhat9-blip/-2.0)";

    /// <summary>
    /// Seven performance/dependency mods + Fabric Mod Menu for the in-game Mods button.
    /// LT6 is embedded separately by GameService for Minecraft 1.21.11.
    /// ModManager resolves the right Mod Menu version for each Minecraft version.
    /// </summary>
    public static string[] Mods(string mc) => mc == "1.21.11"
        ? new[] { "fabric-api", "sodium", "lithium", "ferrite-core",
                  "immediatelyfast", "entityculling", "modernfix", "modmenu", "skyboxify", "wavey-capes" }
        : new[] { "fabric-api", "sodium", "lithium", "ferrite-core",
                  "immediatelyfast", "entityculling", "modernfix", "modmenu" };

}

public static class Dirs
{
    public static readonly string Root = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), ".enchanted");
    public static readonly string LogFile = Path.Combine(Root, "launcher.log");
    public static string Game(string mc) => mc == "1.21.11" ? Path.Combine(Root, "game") : Path.Combine(Root, "instances", mc);
    public static string Mods(string mc) => Path.Combine(Game(mc), "mods");
    public static string ResourcePacks(string mc) => Path.Combine(Game(mc), "resourcepacks");
    public static string CustomMods(string mc) => Path.Combine(Game(mc), "lt6-custom-mods");
    public static string CapeFile(string mc) => Path.Combine(Game(mc), "config", "lt6-cape.png");
    public static string CapeEnabled(string mc) => Path.Combine(Game(mc), "config", "lt6-cape.enabled");
}

public static class Log
{
    static readonly object L = new();
    public static void W(string msg)
    {
        try
        {
            lock (L)
            {
                Directory.CreateDirectory(Dirs.Root);
                File.AppendAllText(Dirs.LogFile, $"{DateTime.Now:yyyy-MM-dd HH:mm:ss} {msg}{Environment.NewLine}");
            }
        }
        catch { }
    }
}

public sealed class Config
{
    public string Nick { get; set; } = "Player";
    public string Version { get; set; } = "1.21.11";
    /// <summary>true - сначала зеркала (BMCLAPI/MCIM), потом официальные серверы.</summary>
    public bool Mirrors { get; set; } = true;
    public bool VerifyFiles { get; set; } = false;
    public int RamMb { get; set; } = 4096;
    public string CurseForgeKey { get; set; } = "";
    public Dictionary<string, List<string>> DisabledMods { get; set; } = new();
    public Dictionary<string, List<AddedMod>> AddedMods { get; set; } = new();
    public bool CapeEnabled { get; set; } = true;

    // Fabric API remains required; every other bundled mod can be toggled.
    public bool IsEnabled(string mc, string slug) =>
        slug.Equals("fabric-api", StringComparison.OrdinalIgnoreCase)
        || !(DisabledMods.TryGetValue(mc, out var disabled)
             && disabled.Contains(slug, StringComparer.OrdinalIgnoreCase));

    public void SetEnabled(string mc, string slug, bool enabled)
    {
        if (slug.Equals("fabric-api", StringComparison.OrdinalIgnoreCase)) return;
        if (!DisabledMods.TryGetValue(mc, out var disabled))
            DisabledMods[mc] = disabled = new List<string>();
        disabled.RemoveAll(x => x.Equals(slug, StringComparison.OrdinalIgnoreCase));
        if (!enabled) disabled.Add(slug);
    }

    public IReadOnlyList<string> SelectedMods(string mc)
    {
        var mods = Const.Mods(mc).Where(id => IsEnabled(mc, id)).ToList();
        foreach (var entry in AddedMods.GetValueOrDefault(mc) ?? new())
            if (entry.Enabled && entry.Type == "modrinth" && !mods.Contains(entry.Id, StringComparer.OrdinalIgnoreCase))
                mods.Add(entry.Id);
        return mods;
    }
    public List<AddedMod> Extra(string mc)
    {
        if (!AddedMods.TryGetValue(mc, out var list)) AddedMods[mc] = list = new();
        return list;
    }


    static string FilePath => Path.Combine(Dirs.Root, "launcher.json");

    public static Config Load()
    {
        try
        {
            if (File.Exists(FilePath))
                return JsonSerializer.Deserialize<Config>(File.ReadAllText(FilePath)) ?? new Config();
        }
        catch (Exception e) { Log.W("config: " + e.Message); }
        return new Config();
    }

    public void Save()
    {
        try
        {
            Directory.CreateDirectory(Dirs.Root);
            File.WriteAllText(FilePath, JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true }));
        }
        catch (Exception e) { Log.W("config save: " + e.Message); }
    }
}

public sealed class AddedMod
{
    public string Id { get; set; } = "";
    public string Name { get; set; } = "";
    // "modrinth" IDs are resolved by the official API; "local" is a copied JAR.
    public string Type { get; set; } = "modrinth";
    public string File { get; set; } = "";
    public bool Enabled { get; set; } = true;
}

public sealed class ModRow
{
    public string Name { get; init; } = "";
    public string State { get; init; } = "";
    public System.Windows.Media.Brush Dot { get; init; } = System.Windows.Media.Brushes.Gray;
}
