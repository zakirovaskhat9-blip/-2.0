LT6 / client-mod — исправления по логу GitHub Actions от 20.11.2026

1. src/main/java/dev/lt6/client/Binds.java
   Window.getWindow() заменён на Window.handle() (Minecraft 1.21.11 / Mojang mappings).

2. src/main/java/dev/lt6/client/Cfg.java
   Устранено обращение к SKY в его собственном инициализаторе.
   Обработчик SkyPacks.select регистрируется отдельным static-блоком после создания SKY.

ZIP содержит только папку client-mod. Распакуйте её в корень репозитория,
заменив существующую client-mod, после чего выполните Commit и Push в GitHub Desktop.
Остальные исходники лаунчера и workflow не затрагиваются.

Проверено: содержимое ZIP, JSON-ресурсы, отсутствие старых классов,
ровно две правки Java-файлов. Полная Gradle-компиляция и запуск Minecraft
в этой среде не выполнялись; успешная сборка подтверждается GitHub Actions.
