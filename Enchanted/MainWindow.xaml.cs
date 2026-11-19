using System.Collections.ObjectModel;
using System.Diagnostics;
using System.Windows;
using System.Windows.Input;

namespace Enchanted;

public partial class MainWindow : Window
{
    readonly Config cfg = Config.Load();
    readonly GameService svc;
    readonly ObservableCollection<ModRow> rows = new();
    CancellationTokenSource? cts;

    public MainWindow()
    {
        InitializeComponent();
        svc = new GameService(cfg);
        ModsList.ItemsSource = rows;
        NickBox.Text = cfg.Nick;
        (cfg.Version == "1.21.4" ? V2 : V1).IsChecked = true;
        (cfg.Mirrors ? S1 : S2).IsChecked = true;
        VerifyBox.IsChecked = cfg.VerifyFiles;
        SlimBox.IsChecked = cfg.SkinSlim;
        SkinServerBox.Text = cfg.SkinServer;
        SkinFace.Source = SkinStore.Face();
        ready = true;
    }

    bool ready;

    async Task PushSkinAsync()
    {
        var nick = NickBox.Text.Trim();
        StatusText.Text = SkinStore.Has ? "Скин сохранён" : "Скин не выбран";
        if (cfg.SkinServer.Trim() == "" || !SkinStore.Has) return;
        StatusText.Text = "Загружаю скин на сервер...";
        var err = await SkinStore.UploadAsync(cfg, nick);
        StatusText.Text = err ?? "Скин загружен: его увидят другие игроки с LT6";
    }

    async void PickSkin_Click(object s, RoutedEventArgs e)
    {
        var dlg = new Microsoft.Win32.OpenFileDialog { Filter = "Скин (PNG 64x64)|*.png", Title = "Выбери скин" };
        if (dlg.ShowDialog(this) != true) return;
        var err = SkinStore.Import(dlg.FileName);
        if (err != null) { StatusText.Text = "Скин: " + err; return; }
        SkinFace.Source = SkinStore.Face();
        cfg.SkinServer = SkinServerBox.Text.Trim();
        cfg.Save();
        await PushSkinAsync();
    }

    async void Slim_Changed(object s, RoutedEventArgs e)
    {
        if (!ready) return;
        cfg.SkinSlim = SlimBox.IsChecked == true;
        cfg.Save();
        await PushSkinAsync();
    }

    async void SkinServer_LostFocus(object s, RoutedEventArgs e)
    {
        var v = SkinServerBox.Text.Trim();
        if (v == cfg.SkinServer) return;
        cfg.SkinServer = v;
        cfg.Save();
        await PushSkinAsync();
    }

    void Title_Down(object s, MouseButtonEventArgs e) { if (e.ButtonState == MouseButtonState.Pressed) DragMove(); }
    void Min_Click(object s, RoutedEventArgs e) => WindowState = WindowState.Minimized;
    void Close_Click(object s, RoutedEventArgs e) { cts?.Cancel(); Close(); }

    void Window_KeyDown(object s, KeyEventArgs e)
    {
        if (e.Key == Key.F11) WindowState = WindowState == WindowState.Maximized ? WindowState.Normal : WindowState.Maximized;
    }

    string Mc => V2.IsChecked == true ? "1.21.4" : "1.21.11";

    static void Open(string dir)
    {
        Directory.CreateDirectory(dir);
        Process.Start(new ProcessStartInfo(dir) { UseShellExecute = true });
    }
    void OpenGame_Click(object s, RoutedEventArgs e) => Open(Dirs.Game(Mc));
    void OpenMods_Click(object s, RoutedEventArgs e) => Open(Dirs.Mods(Mc));
    void OpenPacks_Click(object s, RoutedEventArgs e) => Open(Dirs.ResourcePacks(Mc));

    async void Play_Click(object s, RoutedEventArgs e)
    {
        cfg.Nick = NickBox.Text.Trim();
        cfg.Version = Mc;
        cfg.Mirrors = S1.IsChecked == true;
        cfg.VerifyFiles = VerifyBox.IsChecked == true;
        cfg.SkinServer = SkinServerBox.Text.Trim();
        cfg.Save();

        PlayBtn.IsEnabled = false;
        rows.Clear();
        Bar.Value = 0;
        cts = new CancellationTokenSource();
        // колбэки вызываются из фоновых потоков - переводим на UI
        void St(string t) => Dispatcher.BeginInvoke(() => StatusText.Text = t);
        void Pr(double v) => Dispatcher.BeginInvoke(() => Bar.Value = Math.Clamp(v, 0, 1));
        void Rw(ModRow r) => Dispatcher.BeginInvoke(() => rows.Add(r));
        try
        {
            await Task.Run(() => svc.LaunchAsync(St, Pr, Rw, cts.Token));
            await Task.Delay(400);
            WindowState = WindowState.Minimized;
        }
        catch (OperationCanceledException) { StatusText.Text = "Отменено"; }
        catch (Exception ex)
        {
            Log.W("запуск: " + ex);
            StatusText.Text = "Ошибка: " + ex.GetBaseException().Message + "  (лог: " + Dirs.LogFile + ")";
        }
        finally { PlayBtn.IsEnabled = true; }
    }
}
