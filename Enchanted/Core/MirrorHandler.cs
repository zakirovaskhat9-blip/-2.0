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

    public bool PreferMirror { get; set; } = true;

    public MirrorHandler() : base(new SocketsHttpHandler
    {
        ConnectTimeout = TimeSpan.FromSeconds(10),
        AutomaticDecompression = System.Net.DecompressionMethods.All,
        PooledConnectionLifetime = TimeSpan.FromMinutes(5),
        MaxConnectionsPerServer = 16,
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

        var order = PreferMirror ? new[] { mir, orig } : new[] { orig, mir };
        for (int i = 0; i < order.Length; i++)
        {
            var u = order[i];
            bool last = i == order.Length - 1;
            var m = last && u == orig ? req : Clone(req, u);
            if (last) return await base.SendAsync(m, ct).ConfigureAwait(false);
            try
            {
                using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
                cts.CancelAfter(TimeSpan.FromSeconds(12));
                var res = await base.SendAsync(m, cts.Token).ConfigureAwait(false);
                cts.CancelAfter(Timeout.InfiniteTimeSpan);
                if (res.IsSuccessStatusCode) return res;
                res.Dispose();
            }
            catch (Exception e) when (!ct.IsCancellationRequested)
            {
                Log.W($"{u.Host} не ответил: {e.GetType().Name}: {e.Message} | внутри: {e.GetBaseException().Message}");
            }
        }
        return await base.SendAsync(req, ct).ConfigureAwait(false);
    }

    static HttpRequestMessage Clone(HttpRequestMessage req, Uri u)
    {
        var m = new HttpRequestMessage(HttpMethod.Get, u);
        foreach (var h in req.Headers) m.Headers.TryAddWithoutValidation(h.Key, h.Value);
        return m;
    }
}
