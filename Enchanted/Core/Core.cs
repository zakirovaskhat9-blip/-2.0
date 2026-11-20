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
                  "immediatelyfast", "entityculling", "modernfix", "modmenu", "skyboxify" }
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

public sealed class ModRow
{
    public string Name { get; init; } = "";
    public string State { get; init; } = "";
    public System.Windows.Media.Brush Dot { get; init; } = System.Windows.Media.Brushes.Gray;
}
