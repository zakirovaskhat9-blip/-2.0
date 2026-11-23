using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace Enchanted;

/// <summary>
/// Produce a vanilla-compatible 64x32 cape UV atlas from an arbitrary PNG.
/// Entire 2:1 cape templates are preserved; photos become a two-sided cape.
/// </summary>
public static class CapeStudio
{
    public static void Import(string source, string destination)
    {
        if (!source.EndsWith(".png", StringComparison.OrdinalIgnoreCase))
            throw new InvalidDataException("Выбери PNG, а не JPEG или другой формат.");
        if (new FileInfo(source).Length > 25L * 1024 * 1024)
            throw new InvalidDataException("PNG должен быть меньше 25 МБ.");

        BitmapFrame frame;
        using (var input = File.OpenRead(source))
        {
            var decoder = new PngBitmapDecoder(input, BitmapCreateOptions.PreservePixelFormat,
                                               BitmapCacheOption.OnLoad);
            frame = decoder.Frames[0];
            if (frame.PixelWidth > 4096 || frame.PixelHeight > 4096
                || frame.PixelWidth < 2 || frame.PixelHeight < 2)
                throw new InvalidDataException("Размер PNG: от 2×2 до 4096×4096.");
        }

        var visual = new DrawingVisual();
        RenderOptions.SetBitmapScalingMode(visual, BitmapScalingMode.HighQuality);
        using (var dc = visual.RenderOpen())
        {
            if (frame.PixelWidth == frame.PixelHeight * 2 && frame.PixelWidth >= 64)
            {
                // A fully unfolded cape atlas (64x32 or any proportional size).
                dc.DrawImage(frame, new Rect(0, 0, 64, 32));
            }
            else
            {
                // A photo / illustration: fill both visible sides of the cape;
                // use edge colors on the 1px-wide side and top/bottom strips.
                dc.DrawImage(frame, new Rect(1, 1, 10, 16));
                dc.DrawImage(frame, new Rect(12, 1, 10, 16));
                dc.DrawImage(frame, new Rect(0, 1, 1, 16));
                dc.DrawImage(frame, new Rect(11, 1, 1, 16));
                dc.DrawImage(frame, new Rect(1, 0, 10, 1));
                dc.DrawImage(frame, new Rect(11, 0, 10, 1));
                dc.DrawImage(frame, new Rect(1, 17, 10, 1));
            }
        }
        var cape = new RenderTargetBitmap(64, 32, 96, 96, PixelFormats.Pbgra32);
        cape.Render(visual);
        var png = new PngBitmapEncoder();
        png.Frames.Add(BitmapFrame.Create(cape));
        Directory.CreateDirectory(Path.GetDirectoryName(destination)!);
        var tmp = destination + ".tmp";
        using (var stream = File.Create(tmp)) png.Save(stream);
        File.Move(tmp, destination, true);
    }

    public static BitmapSource Preview(string path)
    {
        using var stream = File.Open(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite);
        var png = new PngBitmapDecoder(stream, BitmapCreateOptions.None, BitmapCacheOption.OnLoad);
        var frame = png.Frames[0];
        frame.Freeze();
        return frame;
    }
}
