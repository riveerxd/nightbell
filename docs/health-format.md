# Health report format, version 1

A JSON document your service serves at an address you choose. Nightbell's
Health endpoint monitor reads it and pages on the verdict.

A plain uptime check can only tell you that something answered. This format
lets your service say what it thinks of itself: which part is in trouble,
which of your own rules decided that, and what happened along the way. You
decide what "down" means. You measure whatever matters inside your app, write
the rule that turns each measurement into up, degraded or down, and the report
carries the measurement, the rule and the evidence. Whoever gets woken up sees
why, not only that.

There is a machine-readable version too:
[`health-format.schema.json`](health-format.schema.json), JSON Schema 2020-12.

## The shortest valid document

```json
{ "healthReport": 1, "status": "up" }
```

## A full one

```json
{
  "healthReport": 1,
  "status": "down",
  "message": "Card payments are failing",
  "checkedAt": "2026-10-09T14:02:11Z",
  "service": "payments-api",
  "version": "4.12.0",
  "environment": "production",
  "components": [
    {
      "id": "payments",
      "name": "Payments",
      "status": "down",
      "message": "14 card payments failed in the last 5 minutes",
      "since": "2026-10-09T13:58:00Z",
      "value": 14,
      "unit": "failures",
      "durationMs": 38,
      "conditions": [
        {
          "id": "failed-payments",
          "name": "Failed card payments",
          "status": "down",
          "observed": 14,
          "rule": ">= 10",
          "unit": "failures",
          "window": "5m",
          "message": "14 failures, down at 10 or more"
        },
        {
          "id": "gateway-reachable",
          "name": "Gateway reachable",
          "status": "up",
          "observed": true,
          "rule": "down when false"
        }
      ],
      "log": [
        {
          "at": "2026-10-09T13:58:00Z",
          "level": "error",
          "message": "Gateway declined payment",
          "data": { "code": "card_declined_by_issuer", "attempt": 3 }
        },
        {
          "at": "2026-10-09T14:01:40Z",
          "level": "warn",
          "message": "Retry queue above 100",
          "data": { "queued": 140 }
        }
      ]
    },
    {
      "id": "database",
      "name": "Database",
      "status": "up",
      "value": 12,
      "unit": "ms",
      "conditions": [
        { "id": "query-latency", "status": "up", "observed": 12, "rule": "degraded when > 500, down when > 2000", "unit": "ms" }
      ]
    },
    {
      "id": "email-queue",
      "name": "Email queue",
      "status": "degraded",
      "message": "340 waiting, usually under 50",
      "value": 340,
      "conditions": [
        { "id": "queue-depth", "status": "degraded", "observed": 340, "rule": "> 100", "unit": "messages" }
      ]
    }
  ]
}
```

## Fields

### The document

| Field | Required | Type | Meaning |
| --- | --- | --- | --- |
| `healthReport` | yes | integer, `1` | The format version. It is also how Nightbell tells this document apart from any other JSON, so an endpoint that loses it is reported as "Not a health endpoint" rather than guessed at. |
| `status` | yes | `"up"`, `"degraded"` or `"down"` | **Your service's overall verdict. This is what pages.** Components explain it. They never override it. |
| `message` | no | string, first 200 characters shown | One line saying what is wrong. When `status` is down, this is the notification text. |
| `checkedAt` | no | RFC 3339 timestamp with an offset | When your service last checked itself. Lets Nightbell notice a report that has stopped updating. |
| `service` | no | string, up to 60 characters | Which service wrote this, when one address fronts several. |
| `version` | no | string, up to 40 characters | The running build. After "is it real", the next question is always "did someone just deploy". |
| `environment` | no | string, up to 40 characters | `production`, `staging`, and so on, so a staging report is never mistaken for the real one. |
| `components` | no | array, first 50 shown | The parts behind the verdict, in the order you want them shown. Nightbell puts the worst first. |

### A component

| Field | Required | Type | Meaning |
| --- | --- | --- | --- |
| `id` | yes | string, `[A-Za-z0-9._-]`, 1 to 64 characters | Stable identifier. Never change it once shipped: anything the monitor remembers about a component is keyed by it. |
| `name` | no | string, up to 60 characters | What a person reads. Defaults to `id`. |
| `status` | yes | `"up"`, `"degraded"` or `"down"` | This part's state. |
| `message` | no | string, up to 200 characters shown | What is wrong with this part, or a short reading when it is fine. |
| `since` | no | RFC 3339 timestamp | When this part entered its current status. Shown as "down since 13:58". |
| `value` | no | number | One reading worth seeing at a glance. |
| `unit` | no | string, up to 16 characters | Printed after `value`. |
| `durationMs` | no | number | How long this part's own check took. A check that is itself slow is worth knowing about. |
| `conditions` | no | array, up to 20 shown | The rules that decided this part's status. See below. |
| `log` | no | array, up to 50 shown | What happened, newest last. See below. |

### A condition

One of your rules: what it measured and what it decided. A component's status
should be the worst of its conditions. The format does not enforce that, and
the component's own `status` is what gets shown.

| Field | Required | Type | Meaning |
| --- | --- | --- | --- |
| `id` | yes | string, `[A-Za-z0-9._-]`, 1 to 64 characters | Stable identifier for the rule. |
| `name` | no | string, up to 60 characters | What a person reads. Defaults to `id`. |
| `status` | yes | `"up"`, `"degraded"` or `"down"` | What this rule decided. |
| `observed` | no | number, string or boolean | What was measured. Strings are cut to 80 characters. |
| `rule` | no | string, up to 80 characters | The threshold in words a person can check against `observed`. The one that matched, such as `">= 10"`, or for a condition that is up, every threshold it was held to, such as `"degraded when > 500, down when > 2000"`. |
| `unit` | no | string, up to 16 characters | Printed after `observed`. |
| `window` | no | string, up to 16 characters | The span the measurement covers, such as `"5m"`. |
| `message` | no | string, up to 200 characters shown | Anything the numbers do not say. |

### A log entry

Evidence: what your check saw on the way to its verdict, in the order it saw
it. This is what lets someone tell a real outage from a flaky probe.

| Field | Required | Type | Meaning |
| --- | --- | --- | --- |
| `at` | yes | RFC 3339 timestamp with an offset | When it happened. |
| `level` | yes | `"info"`, `"warn"` or `"error"` | How much it matters. |
| `message` | yes | string, up to 500 characters shown | What happened. |
| `data` | no | object, up to 20 keys shown | Flat key and value pairs. Values are strings (cut to 200 characters), numbers, booleans or null. Nested objects and arrays are ignored. |

Unknown fields are ignored, everywhere, so the format can grow without breaking
anyone. Field names are case sensitive.

## What the three statuses mean

- **up**: working as intended.
- **degraded**: working, but worse than it should be: slow, backed up,
  running on a fallback. Shown amber. It still answered, so it counts as up
  for uptime.
- **down**: not doing its job for some or all users. Shown red, and pages
  according to the monitor's alert policy.

Nightbell also accepts the words common health libraries already send, in any
letter case: `ok`, `pass` and `healthy` mean up; `warn` and `warning` mean
degraded; `fail`, `error` and `unhealthy` mean down. Any other word is read as
**down**, with the word quoted, because a status Nightbell cannot read is not
good news.

If `status` says up while a component says down, Nightbell believes `status`
and notes the disagreement on the detail screen. You know which parts are
critical. Nightbell does not.

## How the response is read

- **The body decides, not the HTTP status code.** A `503` carrying a valid
  document that says down is read as that document. Many health libraries
  answer `503` when unhealthy, and the body is where the useful part is.
- A `2xx` that is not this format is a failed check called "Not a health
  endpoint", with the first 200 characters of the body shown. It usually
  means a deploy replaced the endpoint. The classic case is a web app's
  catch-all route answering `200` with its own HTML for every path.
- `401` and `403` are covered under [Signing in](#signing-in).
- `404`, any other `4xx`, a `5xx` without a valid document, a timeout, a
  refused connection and a TLS failure are failed checks, the same as for any
  other monitor.
- Bodies over 256 KB are not read. Fifty log entries of a few hundred
  characters come to a few dozen kilobytes, so the limit only matters if
  something is wrong.
- Across the whole report, the first 200 conditions and the first 200 log
  entries are kept, in document order. Nightbell stores the report after
  every check, and more than that is more than anyone reads.

### Staleness

When `checkedAt` is there, the monitor can treat a report older than a chosen
number of minutes as down. It compares `checkedAt` with the `Date` header of
the same response, which comes from your server's own clock, so a phone with
the wrong time cannot make a fresh report look old. Without a `Date` header
the phone's clock is used, with two minutes of tolerance.

A timestamp Nightbell cannot read is ignored and noted. It is never a failure
on its own.

### Plain text health checks

A body that is just `Healthy`, `Degraded` or `Unhealthy` (what ASP.NET Core's
`UseHealthChecks` returns by default) is read as up, degraded or down, with no
components. So you can set up a monitor, sign it in and test it against an
existing ASP.NET health check before writing any JSON.

## Signing in

The endpoint can sit behind the same protection as the rest of your API.
Nightbell signs in the way your other clients do, so the endpoint needs no
special case.

| Your API expects | Choose in Nightbell |
| --- | --- |
| Nothing | None |
| A fixed key in a header (`Authorization: Bearer …`, `X-Api-Key: …`) | Headers |
| HTTP basic auth | Basic |
| An access token from an OpenID Connect provider (Keycloak, Auth0, Entra ID, Authentik, Zitadel and others) | Sign-on |

When you type the address, Nightbell tries it once without signing in, so the
setup screen can tell you whether it needs a sign-in at all.

### Single sign-on

Nightbell keeps its own sign-in, refreshes the access token before it expires,
and sends it as `Authorization: Bearer <token>` on every check. There are four
ways to set it up, depending on what your provider allows:

1. **Through the web app.** Nightbell opens your web app. You tap its own
   Sign in button and log in on your provider's page as usual. Nightbell
   reads the provider, the client ID and the return address from that
   sign-in, so you type nothing. It asks for `offline_access`, so the sign-in
   outlives a normal browser session where the provider allows it. Google
   blocks its sign-in page inside apps, so for Google use Paste a token.
2. **Username and password.** For a client that allows direct sign-in (the
   OAuth password grant). The password is stored encrypted on the phone and
   used to sign in again when the session ends.
3. **Service account.** For a confidential client with client credentials.
   Needs the client ID and secret.
4. **Paste a token.** A refresh token from a session you already have.
   Nightbell reads the provider and client from the token itself. The token
   belongs to that session, so logging out of it ends Nightbell's sign-in too.

Your API has to accept a bearer token issued by your provider to the client
your web app uses, or to the service account. If it already accepts the
tokens your web app sends, it accepts these. If it requires a role, the
account Nightbell signs in with needs that role.

If you later change the monitor's address to a different site, such as
staging to production, Nightbell forgets the provider it learned from the old
one and asks you to sign in again through the new one.

Sign-in trouble is kept apart from outages:

- **Nightbell's own sign-in has ended, or its password was refused:** the
  monitor says **Not checking · Sign in needed** and sends a notification.
  That is about Nightbell's access, not about your service, so it never pages.
- **Your provider cannot be reached at all:** **down**, headlined "Sign-in
  server unreachable". If Nightbell cannot sign in, neither can your users.
- **Nightbell has a fresh token and your API still refuses it with `401` or
  `403`:** **down**, because your users' tokens would be refused the same way.

A refused password is tried exactly once and never retried on a schedule, so
it cannot trip your provider's lockout.

## Writing the endpoint

**Decide your rules first.** For each part of your app, write down what you
measure and at which value it becomes degraded and down. Those become your
conditions. Everything else is plumbing.

**Keep it fast.** A monitor can call it every minute, and every 30 seconds
while it confirms a failure. If a check is expensive, run it in the background
and serve the last result, with `checkedAt` set to when it ran.

**Log evidence, not noise.** A log entry is worth including if it would change
someone's mind about whether the outage is real: the error the gateway sent
back, the count that crossed the line, the retry that also failed. At most 50
are shown, and a few that matter beat fifty that do not.

**Keep secrets and personal data out of it.** `message`, `name`, log messages
and log data can show up on a lock screen and in any webhook the monitor posts
to, exactly as you wrote them. "14 card payments failed" is fine. An email
address, a card number, a token or a stack trace is not.

**Never change an `id`.** Rename with `name` instead.

### ASP.NET Core

ASP.NET Core's health checks already have the right shape: each registered
check becomes a component. Two small files add the rest.
`HealthReportKit.cs` gives a check its conditions and its log, and
`HealthReportWriter.cs` writes the document.

```csharp
// HealthReportKit.cs
using Microsoft.Extensions.Diagnostics.HealthChecks;

public enum ReportStatus { Up = 0, Degraded = 1, Down = 2 }

public sealed record ConditionResult(
    string Id, string Name, ReportStatus Status,
    object? Observed, string? Rule, string? Unit, string? Window, string? Message);

public sealed record ReportLogEntry(
    DateTimeOffset At, string Level, string Message, IReadOnlyDictionary<string, object?>? Data);

/// One rule: a measurement, and the thresholds that turn it into a status.
public sealed class Condition
{
    private readonly string _id;
    private readonly object? _observed;
    private readonly double? _number;
    private string _name;
    private string? _unit, _window, _message;
    private readonly List<(ReportStatus Status, Func<bool> When, string Rule)> _rules = new();

    private Condition(string id, object? observed, double? number)
    {
        _id = id; _name = id; _observed = observed; _number = number;
    }

    /// A number, judged by thresholds.
    public static Condition Measure(string id, double observed) => new(id, observed, observed);

    /// A yes or no. Down when false unless told otherwise.
    public static Condition Flag(string id, bool observed) =>
        new Condition(id, observed, null).Down(() => !observed, "false");

    /// Shown for context, never changes the status.
    public static Condition Info(string id, object? observed) => new(id, observed, null);

    public Condition Named(string name) { _name = name; return this; }
    public Condition In(string unit) { _unit = unit; return this; }
    public Condition Over(string window) { _window = window; return this; }
    public Condition Note(string message) { _message = message; return this; }

    /// Any rule you can write as code. `rule` is what a person reads.
    public Condition Down(Func<bool> when, string rule) { _rules.Add((ReportStatus.Down, when, rule)); return this; }
    public Condition Degraded(Func<bool> when, string rule) { _rules.Add((ReportStatus.Degraded, when, rule)); return this; }

    public Condition DownAtLeast(double t) => Down(() => _number >= t, $">= {t}");
    public Condition DownAbove(double t) => Down(() => _number > t, $"> {t}");
    public Condition DownBelow(double t) => Down(() => _number < t, $"< {t}");
    public Condition DegradedAtLeast(double t) => Degraded(() => _number >= t, $">= {t}");
    public Condition DegradedAbove(double t) => Degraded(() => _number > t, $"> {t}");
    public Condition DegradedBelow(double t) => Degraded(() => _number < t, $"< {t}");

    /// The worst rule that matched, or up when none did.
    public ConditionResult Evaluate()
    {
        foreach (var (status, when, rule) in _rules.OrderByDescending(r => r.Status))
        {
            if (when()) return new(_id, _name, status, _observed, rule, _unit, _window, _message);
        }
        var all = string.Join(", ", _rules.Select(r => $"{r.Status.ToString().ToLowerInvariant()} when {r.Rule}"));
        return new(_id, _name, ReportStatus.Up, _observed, all.Length > 0 ? all : null, _unit, _window, _message);
    }
}

/// What one health check found: its conditions, its log, one headline reading.
public sealed class ComponentReport
{
    private readonly List<Condition> _conditions = new();
    private readonly List<ReportLogEntry> _log = new();
    private double? _value;
    private string? _unit;
    private string? _name;

    public Condition Add(Condition condition) { _conditions.Add(condition); return condition; }

    public ComponentReport Value(double value, string? unit = null) { _value = value; _unit = unit; return this; }

    /// What a person reads. The id is the name the check was registered under.
    public ComponentReport Named(string name) { _name = name; return this; }

    public ComponentReport Info(string message, object? data = null, DateTimeOffset? at = null) => Log("info", message, data, at);
    public ComponentReport Warn(string message, object? data = null, DateTimeOffset? at = null) => Log("warn", message, data, at);
    public ComponentReport Error(string message, object? data = null, DateTimeOffset? at = null) => Log("error", message, data, at);

    // `at` is when it happened, which for evidence read back from the database is not now.
    private ComponentReport Log(string level, string message, object? data, DateTimeOffset? at)
    {
        var fields = data?.GetType().GetProperties().ToDictionary(p => p.Name, p => p.GetValue(data));
        _log.Add(new ReportLogEntry(at ?? DateTimeOffset.UtcNow, level, message, fields));
        return this;
    }

    /// The component's status is the worst of its conditions.
    public HealthCheckResult ToResult(string? message = null)
    {
        var results = _conditions.Select(c => c.Evaluate()).ToList();
        var worst = results.Count == 0 ? ReportStatus.Up : results.Max(r => r.Status);
        var data = new Dictionary<string, object>
        {
            ["conditions"] = results,
            ["log"] = _log.OrderBy(e => e.At).ToList(),
        };
        if (_value is double v) data["value"] = v;
        if (_unit is string u) data["unit"] = u;
        if (_name is string n) data["name"] = n;
        var text = message ?? results.FirstOrDefault(r => r.Status == worst && worst != ReportStatus.Up)?.Message;
        return worst switch
        {
            ReportStatus.Down => HealthCheckResult.Unhealthy(text, data: data),
            ReportStatus.Degraded => HealthCheckResult.Degraded(text, data: data),
            _ => HealthCheckResult.Healthy(text, data),
        };
    }
}
```

```csharp
// HealthReportWriter.cs
using System.Text.Json;
using Microsoft.AspNetCore.Diagnostics.HealthChecks;
using Microsoft.Extensions.Diagnostics.HealthChecks;

// Admin only, because a report like this names orders and amounts. Change the role, or drop the
// check if nothing sensitive is in your report.
public static class HealthReportWriter
{
    public static string Service { get; set; } = "";
    public static string Version { get; set; } = "";
    public static string Environment { get; set; } = "";

    public static async Task Write(HttpContext context, HealthReport report)
    {
        if (!context.User.IsInRole("admin"))
        {
            context.Response.StatusCode = StatusCodes.Status403Forbidden;
            context.Response.ContentType = "text/plain; charset=utf-8";
            await context.Response.WriteAsync("The health report needs the admin role");
            return;
        }

        context.Response.ContentType = "application/json; charset=utf-8";
        context.Response.Headers.CacheControl = "no-store";
        await context.Response.Body.WriteAsync(Render(report));
    }

    public static byte[] Render(HealthReport report)
    {
        using var buffer = new MemoryStream();
        using (var json = new Utf8JsonWriter(buffer))
        {
            json.WriteStartObject();
            json.WriteNumber("healthReport", 1);
            json.WriteString("status", Map(report.Status));
            var worst = report.Entries
                .Where(e => e.Value.Status == report.Status && report.Status != HealthStatus.Healthy)
                .Select(e => e.Value.Description)
                .FirstOrDefault(d => !string.IsNullOrEmpty(d));
            if (worst != null) json.WriteString("message", worst);
            json.WriteString("checkedAt", DateTimeOffset.UtcNow);
            if (Service.Length > 0) json.WriteString("service", Service);
            if (Version.Length > 0) json.WriteString("version", Version);
            if (Environment.Length > 0) json.WriteString("environment", Environment);
            json.WriteStartArray("components");
            foreach (var (id, entry) in report.Entries)
            {
                json.WriteStartObject();
                json.WriteString("id", id);
                if (entry.Data.TryGetValue("name", out var name) && name is string label) json.WriteString("name", label);
                json.WriteString("status", Map(entry.Status));
                // A check that threw has no conditions, so the exception is the only evidence there is.
                string? description = entry.Description ?? entry.Exception?.Message;
                if (!string.IsNullOrEmpty(description)) json.WriteString("message", description);
                json.WriteNumber("durationMs", Math.Round(entry.Duration.TotalMilliseconds));
                if (entry.Data.TryGetValue("value", out var value) && value is IConvertible number)
                    json.WriteNumber("value", Convert.ToDouble(number));
                if (entry.Data.TryGetValue("unit", out var unit) && unit is string text)
                    json.WriteString("unit", text);
                if (entry.Data.TryGetValue("conditions", out var c) && c is IEnumerable<ConditionResult> conditions)
                    WriteConditions(json, conditions);
                if (entry.Data.TryGetValue("log", out var l) && l is IEnumerable<ReportLogEntry> log)
                    WriteLog(json, log);
                json.WriteEndObject();
            }
            json.WriteEndArray();
            json.WriteEndObject();
        }
        return buffer.ToArray();
    }

    private static void WriteConditions(Utf8JsonWriter json, IEnumerable<ConditionResult> conditions)
    {
        json.WriteStartArray("conditions");
        foreach (var c in conditions)
        {
            json.WriteStartObject();
            json.WriteString("id", c.Id);
            if (c.Name != c.Id) json.WriteString("name", c.Name);
            json.WriteString("status", c.Status.ToString().ToLowerInvariant());
            if (c.Observed is not null) WriteValue(json, "observed", c.Observed);
            if (!string.IsNullOrEmpty(c.Rule)) json.WriteString("rule", c.Rule);
            if (!string.IsNullOrEmpty(c.Unit)) json.WriteString("unit", c.Unit);
            if (!string.IsNullOrEmpty(c.Window)) json.WriteString("window", c.Window);
            if (!string.IsNullOrEmpty(c.Message)) json.WriteString("message", c.Message);
            json.WriteEndObject();
        }
        json.WriteEndArray();
    }

    private static void WriteLog(Utf8JsonWriter json, IEnumerable<ReportLogEntry> log)
    {
        json.WriteStartArray("log");
        foreach (var e in log.TakeLast(50))
        {
            json.WriteStartObject();
            json.WriteString("at", e.At);
            json.WriteString("level", e.Level);
            json.WriteString("message", e.Message);
            if (e.Data is { Count: > 0 })
            {
                json.WriteStartObject("data");
                foreach (var (key, v) in e.Data) WriteValue(json, key, v);
                json.WriteEndObject();
            }
            json.WriteEndObject();
        }
        json.WriteEndArray();
    }

    private static void WriteValue(Utf8JsonWriter json, string key, object? value)
    {
        switch (value)
        {
            case null: json.WriteNull(key); break;
            case bool b: json.WriteBoolean(key, b); break;
            case string s: json.WriteString(key, s); break;
            case DateTime d: json.WriteString(key, d); break;
            case DateTimeOffset o: json.WriteString(key, o); break;
            case Guid g: json.WriteString(key, g.ToString()); break;
            case IConvertible n when n is not char: json.WriteNumber(key, Convert.ToDouble(n)); break;
            default: json.WriteString(key, value.ToString()); break;
        }
    }

    private static string Map(HealthStatus status) => status switch
    {
        HealthStatus.Healthy => "up",
        HealthStatus.Degraded => "degraded",
        _ => "down",
    };
}
```

Registered where the health checks already are. If the endpoint is mapped after
your authentication middleware, it is protected exactly like your other routes
with no further work.

```csharp
builder.Services.AddHealthChecks()
    .AddCheck<PaymentsHealthCheck>("payments")
    .AddCheck<DatabaseHealthCheck>("database");

HealthReportWriter.Service = "payments-api";
HealthReportWriter.Version = typeof(Program).Assembly.GetName().Version?.ToString() ?? "";
HealthReportWriter.Environment = app.Environment.EnvironmentName;

app.UseHealthChecks("/health", new HealthCheckOptions
{
    ResponseWriter = HealthReportWriter.Write,
});
```

A check with two rules and its evidence. Anything your app can see can be a
condition: a count, a latency, a queue depth, a flag, the age of the last
successful job.

```csharp
public sealed class PaymentsHealthCheck(IPaymentStats stats, IGateway gateway) : IHealthCheck
{
    public async Task<HealthCheckResult> CheckHealthAsync(
        HealthCheckContext context, CancellationToken cancellationToken = default)
    {
        var report = new ComponentReport();

        var failures = await stats.FailuresSince(DateTime.UtcNow.AddMinutes(-5), cancellationToken);
        report.Add(Condition.Measure("failed-payments", failures.Count)
            .Named("Failed card payments").In("failures").Over("5m")
            .DegradedAtLeast(1)
            .DownAtLeast(10));
        foreach (var failure in failures.TakeLast(10))
            report.Error("Gateway declined payment", new { code = failure.Code, attempt = failure.Attempt });

        var reachable = await gateway.Ping(cancellationToken);
        report.Add(Condition.Flag("gateway-reachable", reachable).Named("Gateway reachable"));
        if (!reachable) report.Error("Gateway did not answer the ping");

        report.Value(failures.Count, "failures");
        return report.ToResult();
    }
}
```

`IPaymentStats` and `IGateway` stand in for whatever your service already
uses. The thresholds are yours to choose.

### Anything else

The document is plain JSON and needs no library. Build it with whatever your
framework uses for JSON responses and check it against the schema.

## Checking your endpoint

```bash
curl -s -H "Authorization: Bearer $TOKEN" https://example.com/health -o health.json
npx --yes ajv-cli validate -s health-format.schema.json -d health.json --spec=draft2020
```

Then add a Health endpoint monitor in Nightbell and tap **Test**. It shows the
verdict and a line for each part that is not up. After you save, the
monitor's detail screen shows the full report, with every part, its rules
and its log.

## Versioning

`healthReport` is `1`. New optional fields can be added within version 1,
and readers ignore fields they do not know. Any change that would make an old
reader misread a document gets a new number, and readers keep reading
version 1.
