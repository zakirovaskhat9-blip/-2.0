using System.Windows;

namespace Enchanted;

public partial class App : Application
{
    public App()
    {
        DispatcherUnhandledException += (_, e) =>
        {
            Log.W("Необработанная ошибка: " + e.Exception);
            MessageBox.Show(e.Exception.Message + "\n\nПодробности: " + Dirs.LogFile, "Enchanted");
            e.Handled = true;
        };
    }
}
