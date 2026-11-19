using System.Reflection;

namespace Enchanted;

/// <summary>Installs LT6 user-supplied sky atlases as optional resource packs.</summary>
public static class Skies
{
    public static void Seed()
    {
        var target = Dirs.ResourcePacks("1.21.11");
        Directory.CreateDirectory(target);
        var asm = Assembly.GetExecutingAssembly();
        foreach (var resName in asm.GetManifestResourceNames())
        {
            // Only our six bundled skies; never touch users' own resource packs.
            if (!resName.Contains("sky_packs.", StringComparison.OrdinalIgnoreCase)
                || !resName.EndsWith(".zip", StringComparison.OrdinalIgnoreCase)) continue;
            var file = resName[(resName.LastIndexOf("LT6_", StringComparison.OrdinalIgnoreCase))..];
            if (!file.StartsWith("LT6_", StringComparison.OrdinalIgnoreCase)) continue;
            var dest = Path.Combine(target, file);
            if (File.Exists(dest)) continue; // manual edits and selections are respected
            using var stream = asm.GetManifestResourceStream(resName);
            if (stream == null) continue;
            using var output = File.Create(dest);
            stream.CopyTo(output);
        }
    }
}
