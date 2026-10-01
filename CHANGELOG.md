# Changelog

What each release changed for you, newest first. Each line is a commit's summary, linked to its full description and diff. Releases before 2.1.1 are described by their release commits.

## 2.3.1 - 2026-10-01

### Fixes

- Require jackson-databind 2.22.3, which fixes two advisories ([`73f9012`](https://github.com/internetdata/sdk-java/commit/73f9012e440040c5732faacafe2f3c354e33aaf2))
- Wait out a Retry-After too long to count on the backoff ([`6e811e8`](https://github.com/internetdata/sdk-java/commit/6e811e8f7e0bd3d0c8c39cbf86bb061a29b26258))
- Never sleep the device poll past its deadline ([`55714c6`](https://github.com/internetdata/sdk-java/commit/55714c60f1457fc53055f971b2024c007bb6d7f7))

## 2.3.0 - 2026-09-29

### Features

- Add the authorization code sign-in, with PKCE ([`79277cb`](https://github.com/internetdata/sdk-java/commit/79277cb9ddc6d90477a09d183dfc001ef133077a))

## 2.2.0 - 2026-09-27

### Features

- Re-pin the spec to 2026.09.26, adding its evaluation-sample fields ([`124ab74`](https://github.com/internetdata/sdk-java/commit/124ab748f626ae01cd4956108bd5bcd788f040de))

## 2.1.1 - 2026-09-22

### Fixes

- Stop explaining in the docs how a private database is hidden ([`ce5fef6`](https://github.com/internetdata/sdk-java/commit/ce5fef6beb5f5932d202003ea0230cae80cac76e))
