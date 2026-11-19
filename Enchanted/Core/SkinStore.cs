using System.Security.Cryptography;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace Enchanted;

/// <summary>Свой скин: импорт PNG (64x64 или старый 64x32), заливка на сервер скинов и настройки для мода.</summary>
public static class SkinStore
{
    public static string SkinPath => Path.Combine(Dirs.Root, "skin.png");
    static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(12) };
    static readonly Regex NickRx = new("^[A-Za-z0-9_]{3,16}$");

    public static bool Has => File.Exists(SkinPath);
    public static bool NickOk(string nick) => NickRx.IsMatch(nick);

    /// <summary>Приводит картинку к 64x64 и сохраняет как skin.png. null - готово, иначе текст ошибки.</summary>
    public static string? Import(string src)
    {
        try
        {
            var bi = new BitmapImage();
            bi.BeginInit();
            bi.UriSource = new Uri(src);
            bi.CacheOption = BitmapCacheOption.OnLoad;
            bi.EndInit();
            int w = bi.PixelWidth, h = bi.PixelHeight;
            if (w != 64 || (h != 64 && h != 32)) return $"нужен скин 64x64 (или старый 64x32), а тут {w}x{h}";

            var conv = new FormatConvertedBitmap(bi, PixelFormats.Bgra32, null, 0);
            const int stride = 64 * 4;
            var px = new byte[stride * 64];
            conv.CopyPixels(new Int32Rect(0, 0, 64, h), px, stride, 0);
            if (h == 32) Legacy(px);
            // базовые слои тела непрозрачные (как в игре)
            NoAlpha(px, 0, 0, 32, 16);
            NoAlpha(px, 0, 16, 64, 32);
            NoAlpha(px, 16, 48, 48, 64);

            var enc = new PngBitmapEncoder();
            enc.Frames.Add(BitmapFrame.Create(BitmapSource.Create(64, 64, 96, 96, PixelFormats.Bgra32, null, px, stride)));
            Directory.CreateDirectory(Dirs.Root);
            using var fs = File.Create(SkinPath);
            enc.Save(fs);
            return null;
        }
        catch (Exception e)
        {
            Log.W("скин: " + e.Message);
            return "не удалось прочитать картинку: " + e.Message;
        }
    }

    static void NoAlpha(byte[] px, int x0, int y0, int x1, int y1)
    {
        for (int y = y0; y < y1; y++)
            for (int x = x0; x < x1; x++) px[(y * 64 + x) * 4 + 3] = 255;
    }

    /// <summary>Старый формат 64x32: левая нога и рука берутся зеркально от правых (как делает игра).</summary>
    static void Legacy(byte[] px)
    {
        int[][] r =
        {
            new[] { 4, 16, 16, 32, 4, 4 }, new[] { 8, 16, 16, 32, 4, 4 }, new[] { 0, 20, 24, 32, 4, 12 }, new[] { 4, 20, 16, 32, 4, 12 },
            new[] { 8, 20, 8, 32, 4, 12 }, new[] { 12, 20, 16, 32, 4, 12 }, new[] { 44, 16, -8, 32, 4, 4 }, new[] { 48, 16, -8, 32, 4, 4 },
            new[] { 40, 20, 0, 32, 4, 12 }, new[] { 44, 20, -8, 32, 4, 12 }, new[] { 48, 20, -16, 32, 4, 12 }, new[] { 52, 20, -8, 32, 4, 12 },
        };
        foreach (var a in r)
            for (int yy = 0; yy < a[5]; yy++)
                for (int xx = 0; xx < a[4]; xx++)
                {
                    int sx = a[0] + (a[4] - 1 - xx), sy = a[1] + yy, dx = a[0] + a[2] + xx, dy = a[1] + a[3] + yy;
                    Array.Copy(px, (sy * 64 + sx) * 4, px, (dy * 64 + dx) * 4, 4);
                }
    }

    /// <summary>Голова (8x8 + слой шапки) для превью.</summary>
    public static BitmapSource? Face()
    {
        try
        {
            if (!Has) return null;
            var bi = new BitmapImage();
            bi.BeginInit();
            bi.UriSource = new Uri(SkinPath);
            bi.CacheOption = BitmapCacheOption.OnLoad;
            bi.CreateOptions = BitmapCreateOptions.IgnoreImageCache;
            bi.EndInit();
            var head = new CroppedBitmap(bi, new Int32Rect(8, 8, 8, 8));
            var hat = new CroppedBitmap(bi, new Int32Rect(40, 8, 8, 8));
            var dv = new DrawingVisual();
            RenderOptions.SetBitmapScalingMode(dv, BitmapScalingMode.NearestNeighbor);
            using (var dc = dv.RenderOpen())
            {
                dc.DrawImage(head, new Rect(0, 0, 64, 64));
                dc.DrawImage(hat, new Rect(0, 0, 64, 64));
            }
            var rtb = new RenderTargetBitmap(64, 64, 96, 96, PixelFormats.Pbgra32);
            rtb.Render(dv);
            rtb.Freeze();
            return rtb;
        }
        catch { return null; }
    }

    public static string EnsureToken(Config cfg)
    {
        if (string.IsNullOrEmpty(cfg.SkinToken)) { cfg.SkinToken = Convert.ToHexString(RandomNumberGenerator.GetBytes(16)); cfg.Save(); }
        return cfg.SkinToken;
    }

    /// <summary>Заливает skin.png на сервер скинов. null - ок (или нечего заливать), иначе текст ошибки.</summary>
    public static async Task<string?> UploadAsync(Config cfg, string nick)
    {
        var server = cfg.SkinServer.Trim().TrimEnd('/');
        if (server == "" || !Has) return null;
        if (!NickOk(nick)) return "ник для скинов: 3-16 символов, латиница, цифры и _";
        try
        {
            using var req = new HttpRequestMessage(HttpMethod.Put, $"{server}/skin/{Uri.EscapeDataString(nick)}");
            req.Headers.TryAddWithoutValidation("X-Token", EnsureToken(cfg));
            req.Headers.TryAddWithoutValidation("X-Model", cfg.SkinSlim ? "slim" : "classic");
            req.Content = new ByteArrayContent(await File.ReadAllBytesAsync(SkinPath));
            req.Content.Headers.ContentType = new("image/png");
            using var res = await Http.SendAsync(req);
            if (res.IsSuccessStatusCode) return null;
            var body = (await res.Content.ReadAsStringAsync()).Trim();
            Log.W($"скин: сервер ответил {(int)res.StatusCode} {body}");
            return (int)res.StatusCode == 403 ? "ник уже занят другим игроком" : $"сервер скинов ответил {(int)res.StatusCode}";
        }
        catch (Exception e)
        {
            Log.W("скин: " + e.Message);
            return "сервер скинов недоступен";
        }
    }

    /// <summary>Настройки для мода: config/lt6-skins.json в папке игры.</summary>
    public static void WriteModConfig(string mc, Config cfg, string nick)
    {
        try
        {
            var dir = Path.Combine(Dirs.Game(mc), "config");
            Directory.CreateDirectory(dir);
            var path = Path.Combine(dir, "lt6-skins.json");
            // Preserve /eskin selection written from inside Minecraft.
            string selectedNick = "";
            if (File.Exists(path))
            {
                try
                {
                    using var previous = JsonDocument.Parse(File.ReadAllText(path));
                    if (previous.RootElement.TryGetProperty("selectedNick", out var chosen))
                        selectedNick = chosen.GetString() ?? "";
                }
                catch (Exception e) { Log.W("старый конфиг скинов: " + e.Message); }
            }
            var json = JsonSerializer.Serialize(new { server = cfg.SkinServer.Trim(), nick, file = Has ? SkinPath : "", slim = cfg.SkinSlim, selectedNick });
            File.WriteAllText(path, json);
        }
        catch (Exception e) { Log.W("скин: конфиг мода: " + e.Message); }
    }
}
