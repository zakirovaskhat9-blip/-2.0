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

    static readonly SemaphoreSlim Gate = new(6); // одновременных рукопожатий/заголовков (защита от «шторма» TLS)

    public bool PreferMirror { get; set; } = true;

    public MirrorHandler() : base(new SocketsHttpHandler
    {
        ConnectTimeout = TimeSpan.FromSeconds(8),
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
        if (!Map.TryGetValue(u.Host, out var prefix)) return null;
        return new Uri(prefix + u.PathAndQuery);
    }

    protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage req, CancellationToken ct)
    {
        var orig = req.RequestUri;
        var mir = req.Method == HttpMethod.Get && orig != null ? Rewrite(orig) : null;
        if (mir == null || orig == null) return await base.SendAsync(req, ct).ConfigureAwait(false);

        // как в обычных лаунчерах: зеркало несколько раз с паузой, оригинал - только запасной
        var plan = PreferMirror
            ? new[] { mir, mir, mir, orig }
            : new[] { orig, orig, mir, mir, mir };

        var errors = new List<string>();
        var dead = new HashSet<Uri>();          // адреса, где файла точно нет (404/403)
        HttpResponseMessage? bad = null;

        for (int i = 0; i < plan.Length; i++)
        {
            var u = plan[i];
            if (dead.Contains(u)) continue;
            if (i > 0) await Task.Delay(i == 1 ? 300 : 900, ct).ConfigureAwait(false);

            await Gate.WaitAsync(ct).ConfigureAwait(false);
            try
            {
                using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
                cts.CancelAfter(TimeSpan.FromSeconds(10)); // таймаут только на ответ сервера, очередь не считается
                var res = await base.SendAsync(Clone(req, u), cts.Token).ConfigureAwait(false);
                cts.CancelAfter(Timeout.InfiniteTimeSpan);
                if (res.IsSuccessStatusCode) { bad?.Dispose(); return res; }

                int code = (int)res.StatusCode;
                errors.Add($"{u.Host}: HTTP {code}");
                if (code is 404 or 403 or 410) dead.Add(u);
                bad?.Dispose();
                bad = res;
            }
            catch (Exception e) when (!ct.IsCancellationRequested)
            {
                var why = e.GetBaseException().Message;
                errors.Add($"{u.Host}: {why}");
                Log.W($"{u.Host} не ответил ({i + 1}/{plan.Length}): {e.GetType().Name}: {why}");
            }
            finally { Gate.Release(); }
        }

        ct.ThrowIfCancellationRequested();
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
