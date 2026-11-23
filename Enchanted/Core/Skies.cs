using System.IO.Compression;
using System.Reflection;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace Enchanted;

/// <summary>
/// Installs six bundled Custom Sky packs. Repair a 2-pixel perimeter within
/// each of the six 3x2 atlas tiles to prevent cross-face texture bleeding.
/// Only overwrite unmodified original packs; preserve the player's edits.
/// </summary>
public static class Skies
{
    private const string SkyImage = "assets/minecraft/optifine/sky/world0/sky1.png";

    public static void Seed()
    {
        string target = Dirs.ResourcePacks("1.21.11");
        Directory.CreateDirectory(target);
        var asm = Assembly.GetExecutingAssembly();
        foreach (string resName in asm.GetManifestResourceNames())
        {
            if (!resName.Contains("sky_packs.", StringComparison.OrdinalIgnoreCase)
                || !resName.EndsWith(".zip", StringComparison.OrdinalIgnoreCase)) continue;
            int offset = resName.LastIndexOf("LT6_", StringComparison.OrdinalIgnoreCase);
            if (offset < 0) continue;
            string file = resName[offset..];
            var dest = Path.Combine(target, file);
            using var stream = asm.GetManifestResourceStream(resName);
            if (stream == null) continue;
            using var raw = new MemoryStream();
            stream.CopyTo(raw);
            byte[] original = raw.ToArray();

            // Don't replace manual overrides of LT6's original sky packs.
            if (File.Exists(dest) && !File.ReadAllBytes(dest).AsSpan().SequenceEqual(original))
                continue;
            byte[] patched = RepairSkyZip(original);
            string tmp = dest + ".lt6tmp";
            File.WriteAllBytes(tmp, patched);
            File.Move(tmp, dest, true);
        }
    }

    private static byte[] RepairSkyZip(byte[] original)
    {
        using var incomingStream = new MemoryStream(original);
        using var incoming = new ZipArchive(incomingStream, ZipArchiveMode.Read);
        using var outputStream = new MemoryStream();
        using (var output = new ZipArchive(outputStream, ZipArchiveMode.Create, leaveOpen: true))
        {
            foreach (var item in incoming.Entries)
            {
                var entry = output.CreateEntry(item.FullName, CompressionLevel.Optimal);
                if (item.FullName.EndsWith("/")) continue;
                using var src = item.Open();
                using var dst = entry.Open();
                if (item.FullName.Equals(SkyImage, StringComparison.OrdinalIgnoreCase))
                {
                    using var png = new MemoryStream();
                    src.CopyTo(png);
                    byte[] fixedPng = RepairAtlas(png.ToArray());
                    dst.Write(fixedPng, 0, fixedPng.Length);
                }
                else src.CopyTo(dst);
            }
        }
        return outputStream.ToArray();
    }

    private static byte[] RepairAtlas(byte[] original)
    {
        using var png = new MemoryStream(original);
        var decoder = new PngBitmapDecoder(png, BitmapCreateOptions.PreservePixelFormat, BitmapCacheOption.OnLoad);
        var frame = decoder.Frames[0];
        int width = frame.PixelWidth, height = frame.PixelHeight;
        // OptiFine/Skyboxify 3 x 2 layout: every tile is a square cube face.
        if (width % 3 != 0 || height % 2 != 0 || width / 3 != height / 2
            || width / 3 < 16) return original;
        BitmapSource rgba = new FormatConvertedBitmap(frame, PixelFormats.Bgra32, null, 0);
        rgba.Freeze();
        int stride = width * 4;
        var pixels = new byte[stride * height];
        rgba.CopyPixels(pixels, stride, 0);
        byte[] fixedPixels = (byte[])pixels.Clone();
        int tile = width / 3;
        const int guard = 2;
        for (int faceY = 0; faceY < 2; faceY++)
            for (int faceX = 0; faceX < 3; faceX++)
            {
                int left = faceX * tile, top = faceY * tile;
                for (int y = 0; y < tile; y++)
                    for (int x = 0; x < tile; x++)
                    {
                        if (x >= guard && x < tile - guard
                            && y >= guard && y < tile - guard) continue;
                        int sourceX = left + Math.Clamp(x, guard, tile - guard - 1);
                        int sourceY = top + Math.Clamp(y, guard, tile - guard - 1);
                        int from = sourceY * stride + sourceX * 4;
                        int to = (top + y) * stride + (left + x) * 4;
                        Buffer.BlockCopy(pixels, from, fixedPixels, to, 4);
                    }
            }
        var result = BitmapSource.Create(width, height, 96, 96, PixelFormats.Bgra32,
            null, fixedPixels, stride);
        var encoder = new PngBitmapEncoder();
        encoder.Frames.Add(BitmapFrame.Create(result));
        using var output = new MemoryStream();
        encoder.Save(output);
        return output.ToArray();
    }
}
