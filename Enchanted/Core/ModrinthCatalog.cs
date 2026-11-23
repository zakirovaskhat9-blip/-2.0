using System.Text.Json;

namespace Enchanted;

/// <summary>
/// A lightweight in-launcher Modrinth catalog. Only the official API is queried;
/// downloads are performed later by ModManager against official Modrinth CDN.
/// </summary>
public sealed record ModrinthHit(string Id, string Slug, string Title,
    string Description, long Downloads, string IconUrl)
{
    public string DownloadsText => Downloads switch {
        >= 1_000_000 => $"{Downloads / 1_000_000.0:0.0}M загрузок",
        >= 1_000 => $"{Downloads / 1_000.0:0.#}K загрузок",
        _ => $"{Downloads} загрузок"
    };
}

public sealed class ModrinthCatalog
{
    private readonly HttpClient http;
    public ModrinthCatalog(HttpClient http) { this.http = http; }

    public async Task<IReadOnlyList<ModrinthHit>> SearchAsync(string query, string version,
                                                              string category, CancellationToken ct)
    {
        // Facet grammar: inner OR, outer AND. Require a Fabric mod that
        // actually advertises compatibility with the selected MC version.
        var facets = new List<string[]> {
            new[] { "project_type:mod" },
            new[] { "categories:fabric" },
            new[] { "versions:" + version }
        };
        if (category is "optimization" or "utility" or "decoration")
            facets.Add(new[] { "categories:" + category });
        string url = "https://api.modrinth.com/v2/search?query="
            + Uri.EscapeDataString(query.Trim())
            + "&facets=" + Uri.EscapeDataString(JsonSerializer.Serialize(facets))
            + "&limit=30&index=" + (string.IsNullOrWhiteSpace(query) ? "downloads" : "relevance");
        using var request = new HttpRequestMessage(HttpMethod.Get, url);
        using var response = await http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, ct);
        response.EnsureSuccessStatusCode();
        using var stream = await response.Content.ReadAsStreamAsync(ct);
        using var root = await JsonDocument.ParseAsync(stream, cancellationToken: ct);
        if (!root.RootElement.TryGetProperty("hits", out var hits) || hits.ValueKind != JsonValueKind.Array)
            throw new InvalidDataException("Modrinth не вернул список модов");
        var items = new List<ModrinthHit>();
        foreach (var x in hits.EnumerateArray()) {
            string Get(string key) => x.TryGetProperty(key, out var value) && value.ValueKind == JsonValueKind.String
                ? value.GetString() ?? "" : "";
            long downloads = x.TryGetProperty("downloads", out var count) && count.TryGetInt64(out var n) ? n : 0;
            var id = Get("project_id");
            if (string.IsNullOrEmpty(id)) continue;
            items.Add(new ModrinthHit(id, Get("slug"), Get("title"), Get("description"), downloads, Get("icon_url")));
        }
        return items;
    }
}
