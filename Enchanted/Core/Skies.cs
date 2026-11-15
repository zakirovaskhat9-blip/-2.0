namespace Enchanted;

/// <summary>Папка своих небес (клиентский мод читает *.json отсюда) и стартовый набор.</summary>
public static class Skies
{
    public static string Dir() => Path.Combine(Dirs.Game("1.21.11"), "skies");

    static readonly (string file, string json)[] Defaults =
    {
        ("lavender_night.json", """{"name":"Лавандовая ночь","stars":true,"speed":10,"sky":["#120a2a","#1f1247","#2a1a5e"],"fog":["#241552","#34206e"],"cloud":["#6b4fd1","#a98bff"]}"""),
        ("mint_dream.json", """{"name":"Мятный сон","stars":false,"speed":9,"sky":["#7fe8c8","#8fd0ff","#b9a8ff"],"fog":["#c8fff0","#cfe9ff","#e3dcff"],"cloud":["#ffffff"]}"""),
        ("ash_sunset.json", """{"name":"Пепельный закат","stars":true,"speed":12,"sky":["#3b2430","#6a3a3a","#2a1c2e"],"fog":["#7a4a45","#5a3340"],"cloud":["#b07a6a","#8a5a62"]}"""),
        ("emerald_aurora.json", """{"name":"Изумрудное сияние","stars":true,"speed":6,"sky":["#03120f","#06261d","#0a2f3a","#10122e"],"fog":["#0a3a2c","#0d3f4a"],"cloud":["#2affb0","#3de0ff","#8a5bff"]}"""),
        ("cherry_pulse.json", """{"name":"Вишнёвый пульс","stars":false,"speed":3,"sky":["#ff5a8a","#ff9ac0","#c04bff"],"fog":["#ffb3cc","#ffd0e0"],"cloud":["#ffffff","#ffd6e8"]}"""),
    };

    const string Readme = "Свои небеса: положи сюда файл *.json и перезайди в меню (правый Shift -> Custom Sky -> Небо).\r\n" +
        "Формат: {\"name\":\"Моё небо\",\"stars\":true,\"speed\":8,\"sky\":[\"#101030\",\"#301060\"],\"fog\":[\"#202050\"],\"cloud\":[\"#ffffff\"]}\r\n" +
        "sky/fog/cloud - списки цветов #RRGGBB; если цветов несколько, небо плавно перетекает между ними (анимация).\r\n" +
        "speed - секунд на переход к следующему цвету, stars - показывать звёзды. fog и cloud можно не указывать.\r\n";

    /// <summary>Кладёт стартовые небеса один раз (удалённые файлы не возвращаются).</summary>
    public static void Seed()
    {
        try
        {
            var d = Dir();
            Directory.CreateDirectory(d);
            var marker = Path.Combine(d, ".seeded");
            if (File.Exists(marker)) return;
            foreach (var (file, json) in Defaults)
            {
                var p = Path.Combine(d, file);
                if (!File.Exists(p)) File.WriteAllText(p, json);
            }
            File.WriteAllText(Path.Combine(d, "README.txt"), Readme);
            File.WriteAllText(marker, "1");
        }
        catch (Exception e) { Log.W("небеса: " + e.Message); }
    }
}
