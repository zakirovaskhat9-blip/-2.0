# LT6MS Launcher — исправление CS1597

Исправлено в `Enchanted/Core/MirrorHandler.cs` (строка 31):

- Было: `public Action? OnRequestAttempt { get; set; };`
- Стало: `public Action? OnRequestAttempt { get; set; }`

Дополнительно отображаемое имя GitHub Actions изменено на `Build LT6MS Launcher`.
Путь для загрузки готового EXE уже настроен: `publish/LT6MS Launcher.exe`.

## Установка

1. Распакуйте архив.
2. Скопируйте **содержимое** папки `-2.0-main` в корень GitHub-репозитория с заменой файлов.
3. В GitHub Actions запустите workflow `Build LT6MS Launcher`.
4. При успешном выполнении артефакт называется `LT6MS-Launcher` и содержит `LT6MS Launcher.exe`.

## Проверка

Проверены исправленная строка, XML проекта/XAML, YAML workflow и целостность ZIP.
Компиляция .NET и Gradle в этой среде не проводилась (SDK/Gradle не установлены).
