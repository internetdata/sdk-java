# [<img src="https://s3.internetdata.io/internetdata-public/brand/mark.svg" alt="InternetData" width="24"/>](https://internetdata.io/) InternetData Java Client Library

[![Maven Central](https://img.shields.io/maven-central/v/io.internetdata/internetdata.svg)](https://central.sonatype.com/artifact/io.internetdata/internetdata)
[![license](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

The official Java client library for the [InternetData](https://internetdata.io) API.

The library lists the IP databases your organization is licensed for, tells you what is inside each one, and downloads them.

## Getting Started

```xml
<dependency>
    <groupId>io.internetdata</groupId>
    <artifactId>internetdata</artifactId>
    <version>1.0.0</version>
</dependency>
```

```groovy
implementation 'io.internetdata:internetdata:1.0.0'
```

Requires Java 17 or newer. HTTP is the JDK's own `java.net.http.HttpClient`, so there is no third-party HTTP stack to reconcile with yours.

## Usage

Every endpoint is licensed, so you need an API key carrying the `db.download` scope. Create one in the console, then:

```java
import io.internetdata.InternetData;

InternetData client = InternetData.create(System.getenv("INTERNETDATA_API_KEY"));
```

Build the client once and keep it. It owns a connection pool, and it is thread safe.

Everything the API offers hangs off `client.database()`, which is where the sibling VPNDetection library keeps the same seven calls.

### What you can see

```java
import io.internetdata.model.Database;

for (Database family : client.database().list()) {
    System.out.println(family.getBase() + " " + family.getStanding() + " " + family.getRedistribution());
    family.getVersions().forEach(v -> System.out.println("  " + v.getId() + " " + v.getFormats()));
}
```

A license covers a database *family* (`bogon_ip`), while a download names a *version* (`bogon_ip_v1`), so the ids every other method takes come from `getVersions()`.

`getStanding()` is `LICENSED` for a live grant, `EXPIRED` for one whose term has ended, and `UNLICENSED` for a database published but never bought, which is how you discover what else exists. Note that this listing is your organization's view rather than a global catalog: a database commissioned for a single customer is absent from everyone else's listing rather than shown as unlicensed. The server decides what you see, so treat the answer as the catalog, and do not cache one key's listing for another.

### What is inside one

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

### Downloading

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

### Verifying a download

```java
import io.internetdata.model.DatabaseChecksums;

DatabaseChecksums sums = client.database().checksums("bogon_ip_v1", DatabaseFormat.CSVGZ);
sums.getSha256();
```

### Download history

Your organization's recent attempts, newest first, refusals included, which is what answers "it stopped working":

```java
client.database().downloads().forEach(d ->
        System.out.println(d.getCreated() + " " + d.getDatasetId() + " " + d.getOutcome()));

client.database().downloads(10);   // newest ten
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

`kind()` is one of `BAD_REQUEST`, `UNAUTHORIZED`, `FORBIDDEN`, `RATE_LIMITED`, `QUOTA_EXCEEDED`, `SERVER_ERROR` or `NETWORK`. `getMessage()` is the API's own result code where it sent one, so a refusal reads `NOT_LICENSED` or `LICENSE_EXPIRED` rather than "403".

Note that `RATE_LIMITED` and `QUOTA_EXCEEDED` both arrive as HTTP 429 and are not the same thing. A rate limit is the API protecting itself and retrying later works; a spent quota needs your allowance raised or the window to roll over. The library retries rate limits for you, and 5xx and transport failures, but never a spent quota or any other client error.

## Other Libraries

There are official InternetData client libraries available for many languages including PHP, Python, Go, Java, Ruby, and many popular frameworks such as Django, Rails, and Laravel. See our GitHub at https://github.com/internetdata for more.

## About InternetData

InternetData publishes IP intelligence databases: hosting and datacenter ranges, proxy and VPN infrastructure, CDN and relay space, and the reference catalogs behind them.

[<img src="https://s3.internetdata.io/internetdata-public/brand/mark.svg" alt="InternetData" width="96"/>](https://internetdata.io/)

## License

This project is licensed under the [MIT License](LICENSE).
