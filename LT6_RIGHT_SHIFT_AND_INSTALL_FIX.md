# LT6 — Right Shift + проверка установки

- GUI по умолчанию: **правый Shift (Right Shift)**. Левый Shift остаётся приседанием.
- В игре: Настройки → Управление → Назначение клавиш → **LT6**. Если ранее был установлен левый Shift, Minecraft хранит привязку в `options.txt`; нажми «Сбросить» для пункта LT6, чтобы применился новый default.
- Резервный способ открыть GUI: клиентская команда `/lt6` в игровом чате (после входа в мир).
- Лаунчер теперь не запускает Minecraft 1.21.11 без встроенного `lt6.jar`: покажет понятную ошибку. При установке проверяется ZIP-структура JAR и наличие точки входа.
- Проект .NET больше не позволяет публиковать EXE без `Enchanted/client_mod/lt6.jar`.
- GitHub Actions после Gradle проверяет, что собранный JAR содержит `fabric.mod.json` и `LT6Client.class`.
- `launcher.log` (%APPDATA%\.enchanted\launcher.log) содержит сообщение `LT6 установлен`, а `game-output.log` — `LT6 client initialized` при успешной загрузке Fabric.

## Как обновить GitHub
Распакуй ZIP и скопируй содержимое `-2.0-main` в корень репозитория, включая `.github/workflows/build.yml`. GitHub через веб не удаляет старые файлы автоматически. Запусти Actions → Build LT6MS Launcher и скачай новый EXE. При повторении ошибки пришли `launcher.log`, `game-output.log`, и первые ошибки GitHub Actions.

**Не подтверждено:** нет доступа к Gradle/Fabric артефактам, .NET SDK и Windows GUI в среде подготовки архива. Полная сборка Minecraft 1.21.11 здесь не выполнялась. Это исправление не гарантирует отсутствие других runtime-ошибок.
