using System.Text.RegularExpressions;

namespace Enchanted;

/// <summary>
/// Подменяет адреса Mojang/Fabric на зеркала BMCLAPI (в России Mojang недоступен).
/// Если зеркало не отвечает или даёт ошибку - запрос уходит на оригинальный адрес.
/// </summary>
public sealed class MirrorHandler : DelegatingHandler
{
    const string B = "https://bmclapi2.bangbang93.com";
    static readonly Dictionary<string, string> Map = new(StringComparer.OrdinalIgnoreCase)
    {
        ["launchermeta.mojang.com"] = B,
        ["piston-meta.mojang.com"] = B,
        ["piston-data.mojang.com"] = B,
        ["launcher.mojang.com"] = B,
        ["libraries.minecraft.net"] = B + "/maven",
        ["resources.download.minecraft.net"] = B + "/assets",
        ["meta.fabricmc.net"] = B + "/fabric-meta",
        ["maven.fabricmc.net"] = B + "/maven",
    };

    static readonly SemaphoreSlim Gate = new(10); // одновременных рукопожатий/заголовков (защита от «шторма» TLS)

    const string Log4j = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<Configuration status=\"WARN\"><Appenders><Console name=\"SysOut\" target=\"SYSTEM_OUT\"><PatternLayout pattern=\"[%d{HH:mm:ss}] [%t/%level]: %msg%n\"/></Console></Appenders><Loggers><Root level=\"info\"><AppenderRef ref=\"SysOut\"/></Root></Loggers></Configuration>\n";

    public bool PreferMirror { get; set; } = true;

    // Обновляет таймер активности во время переключения адресов.
    public Action? OnRequestAttempt { get; set; };


    // запасной канал: только TLS 1.2, HTTP/1.1 (другой отпечаток рукопожатия, как у Python/OpenSSL)
    static readonly HttpMessageInvoker Tls12 = new(new SocketsHttpHandler
    {
        ConnectTimeout = TimeSpan.FromSeconds(3),
        AutomaticDecompression = System.Net.DecompressionMethods.All,
        PooledConnectionLifetime = TimeSpan.FromMinutes(5),
        MaxConnectionsPerServer = 16,
        SslOptions = new System.Net.Security.SslClientAuthenticationOptions
        {
            EnabledSslProtocols = System.Security.Authentication.SslProtocols.Tls12,
        },
    });

    public MirrorHandler() : base(new SocketsHttpHandler
    {
        ConnectTimeout = TimeSpan.FromSeconds(3),
        AutomaticDecompression = System.Net.DecompressionMethods.All,
        PooledConnectionLifetime = TimeSpan.FromMinutes(5),
        MaxConnectionsPerServer = 16,
        SslOptions = new System.Net.Security.SslClientAuthenticationOptions
        {
            EnabledSslProtocols = System.Security.Authentication.SslProtocols.Tls12 | System.Security.Authentication.SslProtocols.Tls13,
        },
    }) { }

    static Uri? Rewrite(Uri u)
    {
        if (u.Host.Equals("mod.mcimirror.top", StringComparison.OrdinalIgnoreCase)) return u; // тот же адрес, но с запасными каналами
        if (!Map.TryGetValue(u.Host, out var prefix)) return null;
        return new Uri(prefix + u.PathAndQuery);
    }

    protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage req, CancellationToken ct)
    {
        var orig = req.RequestUri;
        var mir = req.Method == HttpMethod.Get && orig != null ? Rewrite(orig) : null;
        if (mir == null || orig == null) return await base.SendAsync(req, ct).ConfigureAwait(false);

        // Через 3 секунды без заголовков переключаемся на другой источник.
        // Не повторяем один и тот же медленный адрес 5 раз подряд.
        var plan = mir == orig
            ? new[] { (mir, 0), (mir, 1) }
            : PreferMirror
                ? new[] { (mir, 0), (orig, 0), (mir, 1) }
                : new[] { (orig, 0), (mir, 0), (mir, 1) };

        var errors = new List<string>();
        var dead = new HashSet<Uri>();          // адреса, где файла точно нет (404/403)
        HttpResponseMessage? bad = null;

        for (int i = 0; i < plan.Length; i++)
        {
            var (u, mode) = plan[i];
            if (dead.Contains(u)) continue;
            if (i > 0) await Task.Delay(150, ct).ConfigureAwait(false);

            await Gate.WaitAsync(ct).ConfigureAwait(false);
            try
            {
                OnRequestAttempt?.Invoke();
                using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
                cts.CancelAfter(TimeSpan.FromSeconds(3)); // ожидание заголовков, не всей загрузки
                using var msg = Clone(req, u);
                var res = mode == 1
                    ? await Tls12.SendAsync(msg, cts.Token).ConfigureAwait(false)
                    : await base.SendAsync(msg, cts.Token).ConfigureAwait(false);
                cts.CancelAfter(Timeout.InfiniteTimeSpan);
                if (res.IsSuccessStatusCode) { bad?.Dispose(); return res; }

                int code = (int)res.StatusCode;
                errors.Add($"{u.Scheme}{(mode == 1 ? "/tls1.2" : "")}://{u.Host}: HTTP {code}");
                if (code is 404 or 403 or 410) dead.Add(u);
                bad?.Dispose();
                bad = res;
            }
            catch (Exception e) when (!ct.IsCancellationRequested)
            {
                var why = e.GetBaseException().Message;
                errors.Add($"{u.Scheme}{(mode == 1 ? "/tls1.2" : "")}://{u.Host}: {why}");
                Log.W($"{u.Host} не ответил ({i + 1}/{plan.Length}): {e.GetType().Name}: {why}");
            }
            finally { Gate.Release(); }
        }

        ct.ThrowIfCancellationRequested();
        if (bad != null && Regex.IsMatch(orig.AbsolutePath, @"/client-[\d.]+\.xml$"))
        {
            // конфиг логирования log4j: на зеркале его нет, а Mojang недоступен - отдаём простой аналог (игре он не критичен)
            Log.W("зеркало не имеет " + orig.AbsolutePath + ", подставлен стандартный log4j-конфиг");
            bad.Dispose();
            return new HttpResponseMessage(System.Net.HttpStatusCode.OK) { Content = new StringContent(Log4j, System.Text.Encoding.UTF8, "application/xml"), RequestMessage = req };
        }
        if (bad != null) { Log.W($"{orig.AbsolutePath}: " + string.Join(" | ", errors)); return bad; }
        throw new HttpRequestException($"Не удалось скачать {orig.Host}{orig.AbsolutePath} -> " + string.Join(" | ", errors));
    }

    static HttpRequestMessage Clone(HttpRequestMessage req, Uri u)
    {
        var m = new HttpRequestMessage(HttpMethod.Get, u);
        foreach (var h in req.Headers) m.Headers.TryAddWithoutValidation(h.Key, h.Value);
        return m;
    }
}
