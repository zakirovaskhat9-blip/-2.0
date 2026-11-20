# LT6 / LT6MS — автоматические проверки исходников

Сборка Minecraft: **1.21.11**. Мод: **LT6** (`dev.lt6.client`), лаунчер: **LT6MS Launcher** (C# WPF/.NET 8).

## Проверено в рабочей среде

- Синтаксис 18 Java-файлов разобран JavaCompiler parser: ошибок синтаксиса нет.
- Валидация `fabric.mod.json`, `lt6.mixins.json`, всех шейдерных профилей, XAML, `.csproj` и GitHub Actions.
- Проверены 231 JSON-профиль цветокоррекции и базовые GLSL-контракты, ссылки на `minecraft:core/screenquad`.
- Проверены 6 пользовательских наборов Custom Sky, архивы не повреждены.
- По исходникам проверены регистрация Right Shift, клиентский entrypoint Fabric, список mixin-классов, отсутствие устаревшего пакета `dev.enchanted.client` и необходимая загрузка `lt6.jar` в лаунчер.
- В GitHub Actions добавлена проверка `tools/check_lt6_release.py`. Если пропадёт JAR, шейдер или ресурс-пак, сборка должна остановиться с объяснимой ошибкой.

## Какие проверки ещё обязательны

Текущая среда не имеет доступных репозиториев Gradle и Windows .NET SDK. **Полный `gradle build`, `dotnet publish`, запуск Minecraft, тест смешивания шейдеров с Skyboxify, плавность 3D-эффектов и производительность GPU не выполнены**.  Нельзя называть архив гарантированно рабочим до успешного GitHub Actions и реального игрового теста.

1. Запустить GitHub Actions → Build LT6MS Launcher. В артефактах должны появиться `LT6-Mod-JAR` и `LT6MS-Launcher`.
2. Проверить наличие LT6 и Skyboxify в Mod Menu в Minecraft 1.21.11.
3. Проверить Right Shift, `/lt6`, `Target ESP`, `Hit Color`, `Jump Circles`, цветовые фильтры и каждое из 6 небес.
4. При сбое сохранять `launcher.log` и `game-output.log` из `%APPDATA%\\.enchanted` плюс `latest.log` игровой папки.

Не модифицируются стандартные LWJGL DLL и JAR. Чужие закрытые GUI и ассеты Fly Visuals, Lunar Client и Pulse не копируются.
