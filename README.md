# [<img src="https://s3.internetdata.io/internetdata-public/brand/mark.svg" alt="InternetData" height="28"/>](https://internetdata.io/) InternetData Java Client Library

[![Maven Central](https://img.shields.io/maven-central/v/io.internetdata/internetdata.svg)](https://central.sonatype.com/artifact/io.internetdata/internetdata)
[![license](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

The official Java client library for the [InternetData](https://internetdata.io) API.

It looks up what every InternetData database says about an IP address, an AS or a domain, and lists, describes and downloads the databases your organization is licensed for.

## Getting Started

```xml
<dependency>
    <groupId>io.internetdata</groupId>
    <artifactId>internetdata</artifactId>
    <version>2.9.0</version>
</dependency>
```

```groovy
implementation 'io.internetdata:internetdata:2.9.0'
```

Requires Java 17 or newer. HTTP is the JDK's own `java.net.http.HttpClient`, so there is no third-party HTTP stack to reconcile with yours.

## Usage

**No API key needed to start.** Unlimited, the free plan, answers the Tor, relay, anycast, carrier, satellite and service flags, `location` to the country, `asn`, `company`, `hostname` and the abuse email, with no daily or monthly cap, with a key or without one.

```java
import io.internetdata.InternetData;
import io.internetdata.Result;

InternetData client = InternetData.create();

Result result = client.lookup("146.70.116.130");
System.out.println(result.isTor());                       // false
System.out.println(result.location().getCountry());       // the ISO 3166 country code
System.out.println(result.asn().getName());               // who runs the announcing network
```

Build the client once and keep it. It owns a connection pool, a cache and a thread pool, and it is thread safe.

### With an API key

Every paid plan answers every field: `is_vpn`, the four proxy flags, `is_hosting` and `is_cdn`, the detail behind each flag, the city and coordinates, whois and more. The plans differ only in how many lookups they include, and a free account tries every field, 100 lookups a day. Create a key in the [console](https://app.internetdata.io), then pass it in:

```java
InternetData client = InternetData.create(System.getenv("INTERNETDATA_API_KEY"));

Result result = client.lookup("146.70.116.130");
System.out.println(result.isVpn());                       // Optional[true], on a paid plan
System.out.println(result.vpn().get().getProvider());     // the VPN service, on a paid plan
System.out.println(result.location().getCity());          // null on Unlimited
```

### AS numbers and domains

```java
AsnResult asn = client.lookupAsn(9009);
System.out.println(asn.name());                           // M247 Europe SRL
System.out.println(asn.prefixes());                       // what it announces, on a paid plan

DomainResult domain = client.lookupDomain("example.com");
System.out.println(domain.isRegistered());                // true
System.out.println(domain.dns());                         // its records, on a paid plan
```

Neither is cached, and both have `CompletableFuture` variants: `lookupAsnAsync` and `lookupDomainAsync`.

### Your own address

```java
Result result = client.myIp();
System.out.println(result.ip());   // the address we saw this call come from
```

Same answer `lookup` would give for that address, and the same cost against your allowance. It is deliberately not cached: which address you are is the whole question, and a machine that moves between networks would otherwise be told where it used to be.

### Your plan and usage

```java
Entitlement acct = client.myEntitlement();
System.out.println(acct.getPlan().getKey());         // the plan your key is on
System.out.println(acct.getUsage().getRequests());  // 580
System.out.println(acct.getUsage().getWindowEnd()); // when the allowance resets
```

Usage counts against the anniversary of your subscription on a paid plan, not the calendar month and not the billing period, and against the UTC day on the free plan; it is the same number a lookup is gated on. `getHardLimit()` is `null` on an uncapped plan, which is not the same as zero.

`myIpAsync()` and `myEntitlementAsync()` return a `CompletableFuture` of the same thing.

### Batch lookup

Look up as many addresses as you like in one call. Bogons and cached answers are handled locally, and everything else goes to the batch endpoint in chunks of up to 1000 addresses, in parallel. The batch endpoint needs a paid plan:

```java
var results = client.lookupBatch(List.of("146.70.116.130", "8.8.8.8", "1.1.1.1"));

for (var entry : results.entrySet()) {
    if (!entry.getValue().isSuccess()) {
        System.err.println(entry.getKey() + ": " + entry.getValue().error().get().getMessage());
        continue;
    }
    System.out.println(entry.getKey() + ": " + entry.getValue().orElseThrow().isVpn());
}
```

Results are keyed by address, in the order you first listed each one, so duplicates in your list collapse into a single entry and one address failing never loses the rest: it carries its error as its value, with the status the API would have given that address on its own.

How many chunks are in flight at once, how many times a failed chunk is retried, and how long each attempt may take are configurable per call:

```java
var results = client.lookupBatch(manyIps,
        new BatchOptions().concurrency(4).retries(4).requestTimeout(Duration.ofSeconds(10)));
```

There are `CompletableFuture` variants of both, for when you would rather not block:

```java
client.lookupAsync("146.70.116.130").thenAccept(r -> System.out.println(r.isTor()));
client.lookupBatchAsync(manyIps).thenAccept(results -> System.out.println(results.size()));
```

### Caching

Address lookups are cached by default, so repeat lookups of the same address are free:

```java
InternetData client = InternetData.create();

Result result = client.lookup("146.70.116.130");    // API request
Result result2 = client.lookup("146.70.116.130");   // no API request, the answer was cached
```

You can change the default cache variables (max size, TTL, etc) on initialization, or even disable it:

```java
InternetData client = InternetData.builder()
        .cacheSize(50_000)
        .cacheTtl(Duration.ofHours(6))
        .build();

InternetData clientNoCache = InternetData.builder().cacheEnabled(false).build();
```

### Private and reserved addresses

Private, loopback, link-local, documentation and multicast addresses (and their IPv6 equivalents, including the 6to4 and Teredo ranges) are answered by the library itself, so they cost no request and no quota:

```java
Result result = client.lookup("192.168.1.1");
result.isBogon();   // true
result.isTor();     // false
```

The check is available on the client, which is handy when your inputs are addresses anyway:

```java
client.isBogon("10.0.0.1");    // true
client.isBogon("8.8.8.8");     // false
```

It is also available on its own, if you want it without a client:

```java
import static io.internetdata.Bogon.isBogon;

isBogon("10.0.0.1");    // true
```

### Errors

Failures throw an `InternetDataException` carrying a `kind()` and a `retryable()` flag. It is unchecked, so it travels through a stream or a future without being wrapped first:

```java
import io.internetdata.InternetDataException;

try {
    client.database().download("bogon_ip_v1", DatabaseFormat.MMDB, Path.of("bogon_ip_v1.mmdb"));
} catch (InternetDataException e) {
    System.err.println(e.kind() + " " + e.getMessage() + " retryable=" + e.retryable());
}
```

`kind()` is one of `BAD_REQUEST`, `UNAUTHORIZED`, `FORBIDDEN`, `RATE_LIMITED`, `QUOTA_EXCEEDED`, `SERVER_ERROR` or `NETWORK`. `getMessage()` is the API's own word where it sent one, so a database refusal reads `NOT_LICENSED` or `LICENSE_EXPIRED` rather than "403".

Note that `RATE_LIMITED` and `QUOTA_EXCEEDED` both arrive as HTTP 429 and are not the same thing. A rate limit carries `Retry-After`, so retrying later works, and the library retries it for you, as it does 5xx and transport failures; a spent quota needs your allowance raised or the window to roll over, and the library never retries it, nor any other client error.

### Timeouts

Each attempt at an API call may take 30 seconds, response body included, before it fails as a retryable `NETWORK` error. Change it for the client, or for a single call:

```java
InternetData client = InternetData.builder().requestTimeout(Duration.ofSeconds(5)).build();

Result result = client.lookup("146.70.116.130", new LookupOptions().requestTimeout(Duration.ofSeconds(2)));
```

The bound is per attempt, so a call that is retried can take longer in total. Database downloads are not bounded by it, since a large one can take minutes.

### Databases

If your key carries the `db.download` scope, the databases your organization is licensed for are available through `client.database()`, which is where the sibling VPNDetection library keeps the same seven calls.

#### What you can see

```java
import io.internetdata.model.Database;

for (Database family : client.database().list()) {
    System.out.println(family.getBase() + " " + family.getStanding() + " " + family.getLicenseType());
    family.getVersions().forEach(v -> System.out.println("  " + v.getId() + " " + v.getFormats()));
}
```

A license covers a database *family* (`bogon_ip`), while a download names a *version* (`bogon_ip_v1`), so the IDs every other method takes come from `getVersions()`.

`getStanding()` is `LICENSED` for a live grant, `EXPIRED` for one whose term has ended, and `UNLICENSED` for a database published but never bought, which is how you discover what else exists.

#### What is inside one

```java
import io.internetdata.model.DatabaseMetadata;

DatabaseMetadata meta = client.database().metadata("bogon_ip_v1");
meta.getUpdated();     // the date this build was generated
meta.getEntries();     // rows in it
meta.getSize();        // bytes, per format
meta.getSchema();      // columns, per format
meta.getSample();      // a few real rows, per format
```

Poll this to decide whether today's build is worth fetching, and to budget a transfer before you start one.

#### Downloading

```java
import io.internetdata.DatabaseFormat;
import java.nio.file.Path;

// Streamed straight to disk, so nothing bigger than a chunk is ever held in memory.
long written = client.database().download("bogon_ip_v1", DatabaseFormat.CSVGZ, Path.of("bogon_ip_v1.csv.gz"));
```

A transfer that dies half way is reported rather than left on disk: the bytes land in a neighboring `.part` file, what arrived is checked against the length the server promised, and only a whole file is moved into place.

The API answers a download with a redirect to a time-limited URL on object storage. If you would rather run the transfer yourself, ask for that link instead. It authorizes itself, so it carries none of your credentials and can be handed to any downloader:

```java
String url = client.database().downloadUrl("bogon_ip_v1", DatabaseFormat.CSVGZ);
```

The link authorizes the *start* of a transfer, so one already running is not interrupted when it lapses.

There is also an in-memory form, for the small end of the catalog:

```java
byte[] raw = client.database().downloadBytes("bogon_asn_v1", DatabaseFormat.CSVGZ);
```

`downloadBytes` holds the whole file in memory, and the catalog spans a few hundred bytes to several gigabytes, so check `client.database().metadata(...).getSize()` before reaching for it and use `download` for anything you have not measured.

Each of the three downloads the build that is live unless you name one. A `database.updated` webhook names the build it announces and carries its `sha256`, so a receiver that downloads after the next publish asks for that build to get the file the hash is of:

```java
client.database().download("vpn_ip_v1", DatabaseFormat.MMDB, Path.of("vpn_ip_v1.mmdb"), build);
```

A build is kept 7 days. Past that the call throws `BAD_REQUEST` with the message `BUILD_GONE`, and downloading without a build fetches the live one.

#### Verifying a download

```java
import io.internetdata.model.DatabaseChecksums;

DatabaseChecksums sums = client.database().checksums("bogon_ip_v1", DatabaseFormat.CSVGZ);
sums.getSha256();
```

#### Download history

Your organization's recent attempts, newest first, refusals included, which is what answers "it stopped working":

```java
client.database().downloads().forEach(d ->
        System.out.println(d.getCreated() + " " + d.getDatasetId() + " " + d.getOutcome()));

client.database().downloads(10);   // newest ten
```

### Sign in with OAuth (device flow)

A program running on the person's own machine can let them sign in with a browser and pick one of their API keys, instead of asking them to paste it:

```java
InternetData client = InternetData.create();

DeviceAuthorization device = client.oauth().deviceAuthorization("your-client-id",
        new DeviceAuthorizationOptions().scope("account.read apikeys.read apikeys.reveal"));
System.out.println("Open " + device.getVerificationUri() + " and enter " + device.getUserCode());

TokenResponse token = client.oauth().pollDeviceToken("your-client-id", device);
if (token.getApikey() == null) {
    throw new IllegalStateException("no API key came back: none was picked, or it cannot be shown again");
}
InternetData keyed = InternetData.builder().apiKey(token.getApikey()).build();
```

A denied sign-in throws `OauthAccessDeniedException` and a code that ran out `OauthExpiredTokenException`, and client IDs are issued on request from support@internetdata.io. `client.oauth().revoke("your-client-id", token.getRefreshToken())` signs the machine out again.

### Sign in with OAuth (authorization code)

An app that can take a browser redirect signs the person in there instead, with a PKCE pair made for that one sign-in:

```java
InternetData client = InternetData.create();
String redirectUri = "http://127.0.0.1:8765/callback";
Pkce pkce = client.oauth().createPkce();

String url = client.oauth().authorizationUrl("your-client-id", redirectUri, pkce.challenge(),
        new AuthorizationUrlOptions().scope("apikeys.use").state("your-state"));
// Open url in the browser. Its redirect to redirectUri carries code and state.
TokenResponse token = client.oauth().exchangeAuthorizationCode("your-client-id", code, pkce.verifier(), redirectUri);
```

Check that `state` came back as you sent it before you exchange `code`, which works once. The client ID can also be the https URL of a client metadata document your app serves, and such an app is never handed a key, so `token.getApikey()` stays null.

### Register a client

An app with no client ID, and no client metadata document to use as one, registers itself, then signs in with the `client_id` it gets back as the client ID for `authorizationUrl` and `exchangeAuthorizationCode`:

```java
InternetData client = InternetData.create();
ClientRegistration registration = client.oauth().register(List.of("http://127.0.0.1:8765/callback"),
        new RegisterOptions().clientName("Your App"));
```

Each redirect URI must be https on a public host name, or http on a loopback address, which then matches any port. Nothing is kept until a person approves the client, so an app with no client ID saved can simply register again.

### Absent is not false

Unlimited leaves out `isVpn()`, the four proxy flags, `isHosting()` and `isCdn()`, the detail behind every flag but Tor's and relay's, whois and `hostedDomains()`, and inside `location()` the city, region and coordinates; a free-plan key's answer carries `trialExpired()`, true once its daily trial is spent. Every member a plan may leave out is an `Optional`, where empty means "not in your plan" rather than "checked, and no", and one inside `location()`, `asn()`, `company()` or `abuse()` is null.

```java
result.isVpn().orElse(false);         // when you only want the flag
result.isVpn().isEmpty();             // on Unlimited: not in your plan
result.trialExpired().orElse(false);  // a free-plan key's daily trial is spent
```

## Other Libraries

There are official InternetData client libraries available for many languages including PHP, Python, Go, Java, and Ruby. See our GitHub at https://github.com/internetdata for more.

## About InternetData

Geolocation, anonymity, ownership and network databases for IP addresses and AS numbers, licensed as files you download and query yourself.

[<img src="https://s3.internetdata.io/internetdata-public/brand/mark.svg" alt="InternetData" height="64"/>](https://internetdata.io/)

## License

This project is licensed under the [MIT License](LICENSE).
