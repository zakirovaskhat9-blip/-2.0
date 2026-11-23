using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Diagnostics;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using Microsoft.Win32;

namespace Enchanted;

/// <summary>One toggle per base/extra mod, backed by launcher.json.</summary>
public sealed class ModChoice : INotifyPropertyChanged
{
    private bool enabled;
    public string Id { get; init; } = "";
    public string Title { get; init; } = "";
    public string Subtitle { get; init; } = "";
    public string Type { get; init; } = "base";
    public bool IsExtra => Type != "base";
    public bool CanDisable => Id != "fabric-api";
    public Visibility RemoveVisibility => IsExtra ? Visibility.Visible : Visibility.Collapsed;
    public Action<ModChoice>? OnChange { get; init; }
    public bool Enabled
    {
        get => enabled;
        set
        {
            if (enabled == value || (!CanDisable && !value)) return;
            enabled = value;
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(Enabled)));
            OnChange?.Invoke(this);
        }
    }
    public event PropertyChangedEventHandler? PropertyChanged;
}

public partial class MainWindow : Window
{
    private readonly Config cfg = Config.Load();
    private readonly GameService svc;
    private readonly ModrinthCatalog catalog;
    private readonly ObservableCollection<ModChoice> installed = new();
    private readonly ObservableCollection<ModrinthHit> results = new();
    private CancellationTokenSource? gameCts;
    private CancellationTokenSource? searchCts;

    private string Mc => V2.IsChecked == true ? "1.21.4" : "1.21.11";

    public MainWindow()
    {
        InitializeComponent();
        svc = new GameService(cfg);
        catalog = new ModrinthCatalog(svc.Http);
        InstalledMods.ItemsSource = installed;
        SearchResults.ItemsSource = results;
        NickBox.Text = cfg.Nick;
        (cfg.Version == "1.21.4" ? V2 : V1).IsChecked = true;
        (cfg.Mirrors ? S1 : S2).IsChecked = true;
        VerifyBox.IsChecked = cfg.VerifyFiles;
        CapeEnabledBox.IsChecked = cfg.CapeEnabled;
        RefreshMods();
        RefreshCape();
        Show(HomePage);
        Loaded += (_, _) => SaveCapeFlag();
    }

    private void Title_Down(object sender, MouseButtonEventArgs e)
    {
        if (e.ChangedButton == MouseButton.Left && e.ClickCount == 2) Max_Click(sender, e);
        else if (e.LeftButton == MouseButtonState.Pressed) DragMove();
    }
    private void Min_Click(object sender, RoutedEventArgs e) => WindowState = WindowState.Minimized;
    private void Max_Click(object sender, RoutedEventArgs e) =>
        WindowState = WindowState == WindowState.Maximized ? WindowState.Normal : WindowState.Maximized;
    private void Close_Click(object sender, RoutedEventArgs e)
    {
        gameCts?.Cancel();
        searchCts?.Cancel();
        Close();
    }
    private void Window_KeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.F11) { Max_Click(sender, e); e.Handled = true; }
    }

    private void Show(UIElement page)
    {
        HomePage.Visibility = page == HomePage ? Visibility.Visible : Visibility.Collapsed;
        ModsPage.Visibility = page == ModsPage ? Visibility.Visible : Visibility.Collapsed;
        CapesPage.Visibility = page == CapesPage ? Visibility.Visible : Visibility.Collapsed;
        FilesPage.Visibility = page == FilesPage ? Visibility.Visible : Visibility.Collapsed;
        foreach (var b in new[] { NavHome, NavMods, NavCapes, NavFiles })
        {
            b.Background = new SolidColorBrush(Color.FromRgb(16, 26, 40));
            b.Foreground = new SolidColorBrush(Color.FromRgb(173, 193, 213));
        }
        Button active = page == HomePage ? NavHome
                : page == ModsPage ? NavMods
                : page == CapesPage ? NavCapes : NavFiles;
        active.Background = new SolidColorBrush(Color.FromRgb(35, 60, 88));
        active.Foreground = Brushes.White;
    }
    private void Nav_Home(object s, RoutedEventArgs e) => Show(HomePage);
    private void Nav_Mods(object s, RoutedEventArgs e) => Show(ModsPage);
    private void Nav_Capes(object s, RoutedEventArgs e) => Show(CapesPage);
    private void Nav_Files(object s, RoutedEventArgs e) => Show(FilesPage);

    private static readonly Dictionary<string, string> FriendlyMods = new(StringComparer.OrdinalIgnoreCase)
    {
        ["fabric-api"] = "Fabric API",
        ["sodium"] = "Sodium · рендер",
        ["lithium"] = "Lithium · логика",
        ["ferrite-core"] = "FerriteCore · память",
        ["immediatelyfast"] = "ImmediatelyFast · интерфейс",
        ["entityculling"] = "Entity Culling · видимость",
        ["modernfix"] = "ModernFix · оптимизация",
        ["modmenu"] = "Mod Menu",
        ["skyboxify"] = "Skyboxify · Custom Sky",
        ["wavey-capes"] = "Wavey Capes · физика плаща"
    };
    private void VersionChanged(object sender, RoutedEventArgs e)
    {
        if (!IsLoaded) return;
        cfg.Version = Mc;
        cfg.Save();
        RefreshMods();
        results.Clear();
        SearchStatus.Text = "Выбрана версия " + Mc + ". Запусти поиск для совместимых модов.";
    }

    private void RefreshMods()
    {
        string mc = Mc;
        installed.Clear();
        foreach (var id in Const.Mods(mc))
        {
            var label = FriendlyMods.GetValueOrDefault(id, id);
            var item = new ModChoice
            {
                Id = id,
                Title = label,
                Subtitle = id == "fabric-api" ? "Обязателен для LT6 · не отключается"
                    : id == "skyboxify" ? "Необходим для Custom Sky; без него небо не отобразится"
                    : id == "wavey-capes" ? "Анимированная физика ткани"
                    : "Опциональный Fabric-мод · Modrinth",
                Type = "base",
                OnChange = change => {
                    cfg.SetEnabled(mc, change.Id, change.Enabled);
                    cfg.Save();
                    UpdateModCount();
                }
            };
            item.Enabled = cfg.IsEnabled(mc, id);
            installed.Add(item);
        }
        foreach (var extra in cfg.Extra(mc).ToArray())
        {
            var item = new ModChoice
            {
                Id = extra.Id,
                Title = extra.Name,
                Type = extra.Type,
                Subtitle = extra.Type == "local" ? "Локальный JAR — установка при запуске"
                        : "Добавлен из Modrinth · установка при запуске",
                OnChange = change => {
                    var current = cfg.Extra(mc).FirstOrDefault(x => x.Id == change.Id);
                    if (current == null) return;
                    current.Enabled = change.Enabled;
                    cfg.Save();
                    UpdateModCount();
                }
            };
            item.Enabled = extra.Enabled;
            installed.Add(item);
        }
        UpdateModCount();
    }
    private void UpdateModCount()
    {
        int count = cfg.SelectedMods(Mc).Count
            + cfg.Extra(Mc).Count(x => x.Type == "local" && x.Enabled);
        HomeModsText.Text = $"{count} активных модов · Fabric {Mc}";
    }

    private async void Search_Click(object sender, RoutedEventArgs e)
    {
        searchCts?.Cancel();
        searchCts?.Dispose();
        searchCts = new CancellationTokenSource(TimeSpan.FromSeconds(25));
        var token = searchCts.Token;
        SearchStatus.Text = "Ищу совместимые Fabric-моды на Modrinth...";
        string cat = (CategoryBox.SelectedItem as ComboBoxItem)?.Tag?.ToString() ?? "all";
        string version = Mc;
        string query = SearchBox.Text;
        try
        {
            var hits = await catalog.SearchAsync(query, version, cat, token);
            if (token.IsCancellationRequested || version != Mc) return;
            results.Clear();
            foreach (var hit in hits) results.Add(hit);
            SearchStatus.Text = hits.Count == 0 ? "Совместимых модов не найдено." :
                $"Найдено {hits.Count} модов для Fabric {version}. Нажми «+ Добавить».";
        }
        catch (OperationCanceledException) { }
        catch (Exception ex)
        {
            if (!token.IsCancellationRequested)
                SearchStatus.Text = "Modrinth не ответил: " + ex.GetBaseException().Message;
        }
    }
    private void Search_KeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key != Key.Enter) return;
        e.Handled = true;
        Search_Click(sender, e);
    }

    private void AddModrinth_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not Button { Tag: ModrinthHit hit }) return;
        string mc = Mc;
        var exists = Const.Mods(mc).Contains(hit.Slug, StringComparer.OrdinalIgnoreCase)
                     || cfg.Extra(mc).Any(m => m.Id == hit.Id);
        if (exists)
        {
            SearchStatus.Text = "Мод уже есть в списке. Его можно включить слева.";
            return;
        }
        cfg.Extra(mc).Add(new AddedMod { Id = hit.Id, Name = hit.Title, Type = "modrinth", Enabled = true });
        cfg.Save();
        RefreshMods();
        SearchStatus.Text = $"«{hit.Title}» добавлен. Скачается с Modrinth перед запуском.";
    }

    private void AddJar_Click(object sender, RoutedEventArgs e)
    {
        var picker = new OpenFileDialog {
            Title = "Выбери свой Fabric-мод (.jar)",
            Filter = "Minecraft mods (*.jar)|*.jar",
            CheckFileExists = true
        };
        if (picker.ShowDialog(this) != true) return;
        try
        {
            string mc = Mc, id = Guid.NewGuid().ToString("N");
            if (new FileInfo(picker.FileName).Length > 256L * 1024 * 1024)
                throw new IOException("Файл больше 256 МБ");
            var dir = Dirs.CustomMods(mc);
            Directory.CreateDirectory(dir);
            string destName = id + ".jar";
            File.Copy(picker.FileName, Path.Combine(dir, destName));
            cfg.Extra(mc).Add(new AddedMod {
                Id = id, Name = Path.GetFileNameWithoutExtension(picker.FileName),
                Type = "local", File = destName, Enabled = true
            });
            cfg.Save();
            RefreshMods();
            SearchStatus.Text = "Локальный JAR добавлен. При запуске он будет помещён в mods.";
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, ex.Message, "Не удалось добавить JAR", MessageBoxButton.OK, MessageBoxImage.Warning);
        }
    }
    private void RemoveMod_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not Button { Tag: ModChoice choice } || !choice.IsExtra) return;
        string mc = Mc;
        var old = cfg.Extra(mc).FirstOrDefault(x => x.Id == choice.Id);
        if (old == null) return;
        cfg.Extra(mc).Remove(old);
        // The original imported file is owned by LT6's staging directory;
        // never delete a file from an arbitrary external folder.
        if (old.Type == "local" && old.File == Path.GetFileName(old.File))
            try { File.Delete(Path.Combine(Dirs.CustomMods(mc), old.File)); } catch { }
        cfg.Save();
        RefreshMods();
        SearchStatus.Text = "Удалён из списка. Лаунчер уберёт установленную копию при следующем запуске.";
    }

    private void RefreshCape()
    {
        string file = Dirs.CapeFile("1.21.11");
        if (!File.Exists(file))
        {
            CapePreview.Source = null;
            CapeEmptyText.Visibility = Visibility.Visible;
            CapeStatusText.Text = "Загрузи PNG, чтобы создать свой плащ.";
            return;
        }
        try
        {
            CapeStudio.MigrateLegacy(file);
            CapePreview.Source = CapeStudio.Preview(file);
            CapeEmptyText.Visibility = Visibility.Collapsed;
            CapeStatusText.Text = "Плащ готов: 2048×1024, правильные UV. Исходный PNG сохранён отдельно.";
        }
        catch (Exception ex)
        {
            CapeStatusText.Text = "Ошибка чтения плаща: " + ex.Message;
            CapeEmptyText.Visibility = Visibility.Visible;
        }
    }
    private void SaveCapeFlag()
    {
        string path = Dirs.CapeEnabled("1.21.11");
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        File.WriteAllText(path, cfg.CapeEnabled ? "true" : "false");
    }
    private void CapeEnabledChanged(object sender, RoutedEventArgs e)
    {
        if (!IsLoaded) return;
        cfg.CapeEnabled = CapeEnabledBox.IsChecked == true;
        cfg.Save();
        SaveCapeFlag();
    }
    private void ImportCape_Click(object sender, RoutedEventArgs e)
    {
        var picker = new OpenFileDialog {
            Filter = "PNG изображения (*.png)|*.png",
            Title = "Загрузи изображение для плаща",
            CheckFileExists = true
        };
        if (picker.ShowDialog(this) != true) return;
        try
        {
            CapeStudio.Import(picker.FileName, Dirs.CapeFile("1.21.11"),
                CapeTemplateBox.IsChecked == true);
            cfg.CapeEnabled = true;
            CapeEnabledBox.IsChecked = true;
            cfg.Save();
            SaveCapeFlag();
            RefreshCape();
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, ex.Message, "Ошибка импорта плаща", MessageBoxButton.OK, MessageBoxImage.Warning);
        }
    }
    private void ClearCape_Click(object sender, RoutedEventArgs e)
    {
        try {
            File.Delete(Dirs.CapeFile("1.21.11"));
            File.Delete(CapeStudio.SourceFile(Dirs.CapeFile("1.21.11")));
            cfg.CapeEnabled = false;
            CapeEnabledBox.IsChecked = false;
            cfg.Save();
            SaveCapeFlag();
            RefreshCape();
        }
        catch (Exception ex) { CapeStatusText.Text = ex.Message; }
    }

    private static void Open(string dir)
    {
        Directory.CreateDirectory(dir);
        Process.Start(new ProcessStartInfo(dir) { UseShellExecute = true });
    }
    private void OpenGame_Click(object sender, RoutedEventArgs e) => Open(Dirs.Game(Mc));
    private void OpenMods_Click(object sender, RoutedEventArgs e) => Open(Dirs.Mods(Mc));
    private void OpenPacks_Click(object sender, RoutedEventArgs e) => Open(Dirs.ResourcePacks(Mc));

    private async void Play_Click(object sender, RoutedEventArgs e)
    {
        cfg.Nick = NickBox.Text.Trim();
        cfg.Version = Mc;
        cfg.Mirrors = S1.IsChecked == true;
        cfg.VerifyFiles = VerifyBox.IsChecked == true;
        cfg.Save();
        SaveCapeFlag();
        PlayBtn.IsEnabled = false;
        Bar.Value = 0;
        gameCts = new CancellationTokenSource();
        CancellationToken token = gameCts.Token;
        void Status(string text) => Dispatcher.BeginInvoke(() => StatusText.Text = text);
        void Progress(double value) => Dispatcher.BeginInvoke(() => Bar.Value = Math.Clamp(value, 0, 1));
        void ModStatus(ModRow row) => Dispatcher.BeginInvoke(() => StatusText.Text =
            row.Name + " — " + row.State);
        try
        {
            StatusText.Text = "Готовлю Fabric и активные моды...";
            await Task.Run(() => svc.LaunchAsync(Status, Progress, ModStatus, token), token);
            await Task.Delay(400);
            WindowState = WindowState.Minimized;
        }
        catch (OperationCanceledException) { StatusText.Text = "Запуск отменён"; }
        catch (Exception ex)
        {
            Log.W("запуск: " + ex);
            StatusText.Text = "Ошибка: " + ex.GetBaseException().Message + " (см. launcher.log)";
        }
        finally { PlayBtn.IsEnabled = true; }
    }
}
