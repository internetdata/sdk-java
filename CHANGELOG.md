# Changelog

What each release changed for you, newest first. Each line is a commit's summary, linked to its full description and diff. Releases before 2.1.1 are described by their release commits.

## 2.7.1 - 2026-11-09

### Fixes

- Re-pin the spec to 2026.11.08: the token and revoke descriptions ([`0fcca89`](https://github.com/internetdata/sdk-java/commit/0fcca893767f505e983793c0ebea99a1707f38cf))

## 2.7.0 - 2026-11-07

### Features

- Download a named build: downloadUrl, download and downloadBytes take a build ([`f028cb9`](https://github.com/internetdata/sdk-java/commit/f028cb9a1a5ead76bf0c353ab203c0335d6fab63))

### Fixes

- Re-pin the spec to 2026.11.05: the download's build parameter ([`3fb0533`](https://github.com/internetdata/sdk-java/commit/3fb0533eeef9f8e897450315aa4dc36107369ecb))

## 2.6.4 - 2026-11-07

### Fixes

- End the device poll at its deadline without asking again ([`782cf3b`](https://github.com/internetdata/sdk-java/commit/782cf3b65f50e6c6dfb764545092e527485d6235))

## 2.6.3 - 2026-11-03

### Fixes

- Re-pin the spec to 2026.11.02: ID and IDs in its descriptions ([`5c974ee`](https://github.com/internetdata/sdk-java/commit/5c974ee9ed352f7748c62994dad4e1035ede8e2b))
- Write ID and IDs in prose, never id or ids ([`41af5ee`](https://github.com/internetdata/sdk-java/commit/41af5ee116a1ab16ce9b39ff931226c2d1f0f891))

## 2.6.2 - 2026-10-26

### Fixes

- Refuse an API key holding a character outside ASCII ([`b284da1`](https://github.com/internetdata/sdk-java/commit/b284da13156b7fa3105ddd40e83967ed81f4ce2e))
- Read dates as RFC 3339 alone ([`9041e6c`](https://github.com/internetdata/sdk-java/commit/9041e6c9a39a0b68a1c2bcd180fd5371171e57b3))
- Refuse an answer holding a null, an unknown license type or a bad row ([`079fabf`](https://github.com/internetdata/sdk-java/commit/079fabf0dbeadb2052c7bd5c027ce6cf8bf85586))

## 2.6.1 - 2026-10-21

### Fixes

- Re-pin the spec to 2026.10.21: a 403 for a refused address ([`3ee56f7`](https://github.com/internetdata/sdk-java/commit/3ee56f785bdad07dc9cdcd7837e6a4834dbddee7))

## 2.6.0 - 2026-10-18

### Features

- Add oauth().register, dynamic client registration ([`6a27a1b`](https://github.com/internetdata/sdk-java/commit/6a27a1b9607105b20e92ce42548d285a10f183f4))

### Fixes

- Refuse a database answer member of the wrong type ([`6be411a`](https://github.com/internetdata/sdk-java/commit/6be411aca21b3ab7f2ce2d1c78f677e6320c1eb7))
- Re-pin the spec to 2026.10.18: client_id_issued_at is int64 ([`057370f`](https://github.com/internetdata/sdk-java/commit/057370f2f3a4ab825823e661999cb33d61064b32))

## 2.5.0 - 2026-10-17

### Features

- Re-pin the spec to 2026.10.16: dynamic client registration ([`2fceb71`](https://github.com/internetdata/sdk-java/commit/2fceb718361dcd0ff5106821593ba4f47dc04f53))

## 2.4.7 - 2026-10-16

### Fixes

- Re-pin the spec to 2026.10.15: three descriptions in US spelling ([`c1f29cf`](https://github.com/internetdata/sdk-java/commit/c1f29cf8948a54cbb498d1f1159c5e8dcb4c1253))

## 2.4.6 - 2026-10-14

### Fixes

- Refuse a database entry without a member the spec requires ([`2868229`](https://github.com/internetdata/sdk-java/commit/2868229b492bd2e9b6f88f68cb5c851f0312bb14))

## 2.4.5 - 2026-10-13

### Fixes

- Re-pin the spec to 2026.10.13: Open databases come with a license ([`3cb7e90`](https://github.com/internetdata/sdk-java/commit/3cb7e90f8f4f8119646ea0f632c190c0597d1e75))
- Trim the API key, and refuse one holding a control character ([`e166033`](https://github.com/internetdata/sdk-java/commit/e1660338a86bebcfb7426ddd1444fa646d0e9b46))
- Retry {} from list and downloads as a server error with its status ([`ac68297`](https://github.com/internetdata/sdk-java/commit/ac682971271a900c6ba49e8e868a53e347a88c6b))

## 2.4.4 - 2026-10-11

### Fixes

- Retry a 200 the call cannot read, carrying its status ([`2449f83`](https://github.com/internetdata/sdk-java/commit/2449f835dccf8d8dddbc53b938220f0d18e1129a))

## 2.4.3 - 2026-10-11

### Fixes

- Read Retry-After as digits or an RFC 9110 date in GMT ([`5225044`](https://github.com/internetdata/sdk-java/commit/5225044240ed6c27be61de10d1d562c24e84751e))

## 2.4.2 - 2026-10-10

### Fixes

- Re-pin the spec to 2026.10.09: rotating a key needs apikeys.reveal ([`f999c7d`](https://github.com/internetdata/sdk-java/commit/f999c7d44bc4299688e2a8c3683bba43fa1f6549))

## 2.4.1 - 2026-10-10

### Fixes

- Publish to Maven Central, which refused 2.4.0: release on Maven 3.9 ([`f16bbf9`](https://github.com/internetdata/sdk-java/commit/f16bbf9b5729b328cc143dff7c2dcc53f0c6ba99))

## 2.4.0 - 2026-10-09

### Features

- Re-pin the spec to 2026.10.08, adding the Open databases' open flag ([`6b4ff3d`](https://github.com/internetdata/sdk-java/commit/6b4ff3d282eca9369b836636b3f028cef6ff3aa6))

## 2.3.2 - 2026-10-04

### Fixes

- Re-pin the spec to 2026.10.03: metadata needs no license ([`9254897`](https://github.com/internetdata/sdk-java/commit/92548972c6cddb0aa8ef8d520801f5593de7a0c9))

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
