using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace Enchanted;

/// <summary>
/// High-resolution Minecraft cape UV atlas. Minecraft 1.21.11's cape model
/// uses a 64x32 UV grid (the baked model halves the vertical UV scale).
/// 2048x1024 gives each 10x16 cape face a 320x512 pixel canvas.
/// The original PNG is saved separately and never modified.
</summary>
public static class CapeStudio
{
    public const int Resolution = 2048;
    public const int CanvasHeight = 1024;
    private const int Unit = Resolution / 64;

    public static string SourceFile(string destination) =>
        Path.Combine(Path.GetDirectoryName(destination)! , "lt6-cape-source.png");

    public static void Import(string source, string destination, bool isTemplate = false)
    {
        if (!source.EndsWith(".png", StringComparison.OrdinalIgnoreCase))
            throw new InvalidDataException("Загрузи PNG-файл.");
        if (new FileInfo(source).Length > 32L * 1024 * 1024)
            throw new InvalidDataException("Изображение больше 32 МБ.");

        BitmapFrame frame;
        using (var input = File.OpenRead(source))
        {
            var decoder = new PngBitmapDecoder(input, BitmapCreateOptions.PreservePixelFormat,
                                               BitmapCacheOption.OnLoad);
            frame = decoder.Frames[0];
            if (frame.PixelWidth > 8192 || frame.PixelHeight > 8192
                || frame.PixelWidth < 2 || frame.PixelHeight < 2)
                throw new InvalidDataException("Допустимый размер PNG: от 2×2 до 8192×8192.");
        }

        if (isTemplate && frame.PixelWidth != frame.PixelHeight
                && frame.PixelWidth != frame.PixelHeight * 2)
            throw new InvalidDataException("Развёртка должна иметь пропорции 64×32 или 64×64 (и кратные).");

        var visual = new DrawingVisual();
        RenderOptions.SetBitmapScalingMode(visual, BitmapScalingMode.HighQuality);
        using (var dc = visual.RenderOpen())
        {
            if (isTemplate)
            {
                // ModelPart bake: UV width=64, height=32. A 64x64 template
                // contains optional unused lower rows. Keep only its top half
                // rather than squeezing the artwork vertically.
                BitmapSource top = frame;
                if (frame.PixelWidth == frame.PixelHeight)
                    top = new CroppedBitmap(frame,
                        new Int32Rect(0, 0, frame.PixelWidth, frame.PixelHeight / 2));
                dc.DrawImage(top, new Rect(0, 0, Resolution, CanvasHeight));
            }
            else
            {
                // Picture mode (default). Preserve its original aspect ratio:
                // letterbox instead of stretching or cropping the image.
                // The visible outside cape surface is UV (12,1)-(22,17).
                DrawPicture(dc, frame, new Rect(12 * Unit, Unit, 10 * Unit, 16 * Unit));
                DrawPicture(dc, frame, new Rect(Unit, Unit, 10 * Unit, 16 * Unit));

                // Narrow strips wrap around the edges; don't squeeze a full
                // photo onto one pixel-wide side of the cloth.
                var edge = new SolidColorBrush(Color.FromRgb(24, 35, 48));
                dc.DrawRectangle(edge, null, new Rect(0, Unit, Unit, 16 * Unit));
                dc.DrawRectangle(edge, null, new Rect(11 * Unit, Unit, Unit, 16 * Unit));
                dc.DrawRectangle(edge, null, new Rect(Unit, 0, 10 * Unit, Unit));
                dc.DrawRectangle(edge, null, new Rect(11 * Unit, 0, 10 * Unit, Unit));
                dc.DrawRectangle(edge, null, new Rect(Unit, 17 * Unit, 10 * Unit, Unit));
                dc.DrawRectangle(edge, null, new Rect(11 * Unit, 17 * Unit, 10 * Unit, Unit));
            }
        }
        var atlas = new RenderTargetBitmap(Resolution, CanvasHeight, 96, 96, PixelFormats.Pbgra32);
        atlas.Render(visual);
        var png = new PngBitmapEncoder();
        png.Frames.Add(BitmapFrame.Create(atlas));

        Directory.CreateDirectory(Path.GetDirectoryName(destination)!);
        string staged = destination + ".tmp";
        using (var output = File.Create(staged)) png.Save(output);

        // Save the exact uploaded source, which is indispensable for choosing
        // a different UV mode without re-uploading/recompressing the artwork.
        string copy = SourceFile(destination);
        if (!Path.GetFullPath(source).Equals(Path.GetFullPath(copy),
                StringComparison.OrdinalIgnoreCase))
            File.Copy(source, copy, true);
        File.Move(staged, destination, true);
    }

    private static void DrawPicture(DrawingContext dc, BitmapSource image, Rect face)
    {
        dc.DrawRectangle(new SolidColorBrush(Color.FromRgb(24, 35, 48)), null, face);
        double factor = Math.Min(face.Width / image.PixelWidth,
                                 face.Height / image.PixelHeight);
        double w = image.PixelWidth * factor;
        double h = image.PixelHeight * factor;
        dc.DrawImage(image, new Rect(face.X + (face.Width - w) * 0.5,
                                     face.Y + (face.Height - h) * 0.5, w, h));
    }

    public static BitmapSource Preview(string path)
    {
        using var stream = File.Open(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite);
        var png = new PngBitmapDecoder(stream, BitmapCreateOptions.None, BitmapCacheOption.OnLoad);
        BitmapSource frame = png.Frames[0];
        // Show the actual OUTSIDE of the cape, not the whole atlas, so the
        // user knows exactly how their uploaded picture looks on the model.
        if (frame.PixelWidth == frame.PixelHeight * 2 && frame.PixelWidth >= 64)
        {
            int unit = frame.PixelWidth / 64;
            if (unit > 0)
                frame = new CroppedBitmap(frame,
                    new Int32Rect(12 * unit, unit, 10 * unit, 16 * unit));
        }
        frame.Freeze();
        return frame;
    }

    public static void MigrateLegacy(string destination)
    {
        if (!File.Exists(destination)) return;
        using var stream = File.Open(destination, FileMode.Open, FileAccess.Read, FileShare.ReadWrite);
        var png = new PngBitmapDecoder(stream, BitmapCreateOptions.None, BitmapCacheOption.OnLoad);
        if (png.Frames[0].PixelWidth != 64 || png.Frames[0].PixelHeight != 32)
            return;
        stream.Close();
        // The old 64x32 UV layout survives as an unfolded template, and its
        // original bytes are backed up before replacing the prepared texture.
        Import(destination, destination, true);
    }
}
