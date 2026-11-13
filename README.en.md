# Lantai (兰台)

> Official repository: <https://github.com/punk64/Lantai>
>
> 中文文档：[README.md](README.md)

**Lantai is an independent open-source distribution based on [Komga](https://github.com/gotson/komga).** Built on Komga 1.29.2, it keeps the upstream data model, scanner, page streaming and client protocols, replaces the web frontend, and adds ebook formats, upstream Komga server integration, metadata scraping, reading statistics, full-text search and text-to-speech.

> This is not an official Komga project and is not affiliated with gotson/komga or its author.
> The original project is copyright Gauthier Roebroeck, licensed under MIT.
>
> **The online-source feature exists to stay compatible with the upstream Komga project: an upstream Komga server can be connected as an online source, and its library, series, chapters and page images are merged into this instance's library.** This project does not provide, maintain or distribute any third-party plugin or content; the servers connected and the content read are supplied and used by the user alone.
>
> **Lantai is self-hosted software, not an online service. It is meant for personal and household use, and must not be deployed as a public service open to an unspecified audience.** See [Online sources and responsibility](#online-sources-and-responsibility).

The name comes from *Lantai* (兰台), the imperial library and archive of Han-dynasty China — established in the Western Han (202 BC – AD 8) at Chang'an, continued through the Eastern Han (AD 25–220), where Ban Gu once served as Lantai ling-shi. The relationship with Komga in one phrase: **same origin, different road**.

## Release status

**Every release before V1.0.0 is a test build.** Feature updates during this period aim to avoid breaking the existing data structures; the focus is on interface fixes and experience improvements. Any breaking change across versions will be documented separately with a migration path.

## Relationship with Komga

| Item | Detail |
|---|---|
| This repository | [punk64/Lantai](https://github.com/punk64/Lantai) |
| Upstream | [gotson/komga](https://github.com/gotson/komga), MIT |
| Baseline | 1.29.2 (tag `1.29.2`, commit `829f7588`, 2026-10-28) |
| Forked on | 2026-10-30 |
| Upstream sync | **Ended on 2026-10-31.** Package names, config prefixes, module layout and class names have all been renamed; upstream merges are no longer possible |
| Inherited from Komga | Library / series / book model, file scanning and metadata parsing, page streaming and thumbnails, OPDS v1/v2, Kobo Sync, KOReader Sync, WebPub, permissions and multi-user |
| Added or rewritten by Lantai | Web frontend (dependency-free), EPUB / TXT readers, upstream Komga server integration (no plugins or addresses bundled), Douban / AniList scraping, reading-time statistics, annotations and bookmarks, want-to-read marks, in-book full-text search, sentence-level TTS highlighting, 39 UI languages, instant-open loading model |
| Full diff against upstream | see `FORK_BASE.md` |

## Capabilities

| Area | Details |
|---|---|
| Formats | CBZ / CBR / CB7 / PDF / EPUB / TXT, read directly |
| Readers | Comic page flow (double page, webtoon, reading direction); ebook dual mode (scroll / paged) preserving original typesetting, with adjustable font, size, line height and theme |
| Reading aids | Sentence-level TTS with highlighting, in-book full-text search, annotations and bookmarks |
| Want to read | Books and series can be marked for later reading and shown as their own home section; independent of read status |
| Online sources | An upstream Komga server connected as an online source; its library, series, chapters and page images are merged into this instance's library. **No third-party plugin or content is preset, provided or maintained** |
| Metadata | Douban Books + AniList search and matching, field-by-field conflict review; the scrape list exports to JSON / CSV for external filling and re-import, or external programs can submit through a scrape token |
| Statistics | Reading-time detail plus daily aggregation, fed by both web heartbeat and client reports |
| Protocols | OPDS v1 / v2, Kobo Sync, KOReader Sync, WebPub |
| Accounts | Multi-user, role permissions, sub-accounts, one-time password reset |
| Interface | 39 languages, Simplified Chinese by default |

## Languages

39 languages, Simplified Chinese by default. Switch instantly under *Settings → Appearance*, no restart needed.

| Language | Code | Language | Code | Language | Code |
|---|---|---|---|---|---|
| Simplified Chinese | `zh-Hans` | Traditional Chinese | `zh-Hant` | English | `en` |
| Japanese | `ja` | Korean | `ko` | Russian | `ru` |
| German | `de` | French | `fr` | Spanish | `es` |
| Portuguese | `pt` | Portuguese (Brazil) | `pt-BR` | Italian | `it` |
| Dutch | `nl` | Polish | `pl` | Czech | `cs` |
| Slovak | `sk` | Slovenian | `sl` | Croatian | `hr` |
| Bulgarian | `bg` | Ukrainian | `uk` | Swedish | `sv` |
| Danish | `da` | Norwegian | `nb` | Finnish | `fi` |
| Catalan | `ca` | Galician | `gl` | Arabic | `ar` |
| Hebrew | `he` | Persian | `fa` | Turkish | `tr` |
| Greek | `el` | Hungarian | `hu` | Indonesian | `id` |
| Vietnamese | `vi` | Thai | `th` | Tamil | `ta` |
| Malayalam | `ml` | Tigrinya | `ti` | Esperanto | `eo` |

Simplified Chinese ships in the main bundle; the other 38 load on demand, fetched only when selected.

## Tech stack

Kotlin · Spring Boot 3.5 · Spring Data JDBC + jOOQ · Flyway · SQLite · Lucene · Gradle 9 · JDK 21

## Layout

```
lantai/                  Spring Boot backend (API, scanner, client protocols)
lantai/docker/           Image build files and both compose files
lantai-webui-v2/         Web frontend source (dependency-free vanilla JS)
plugins/official/        Official plugin sources and build scripts
res/                     Application icons
gradle/                  Gradle wrapper
```

The frontend uses no framework and no npm packages; the build script is `lantai-webui-v2/build.js` inside the repo.

## Requirements

| Item | Version | Purpose | Notes |
|---|---|---|---|
| JDK | **21** | Build and run | Hard requirement; older versions fail to compile |
| Node.js | **22** | Frontend build only | Dependency-free, no `npm install` |
| Gradle | Bundled wrapper | Build | No separate install; `gradlew` downloads the right version |
| Memory | 2 GB+ for builds | — | `org.gradle.jvmargs=-Xmx2G` |
| Disk | ~2 GB for source and dependencies | — | First build downloads Gradle and Maven artifacts |

If Java or Node are not on `PATH`, set the environment first:

```bash
export JAVA_HOME=/path/to/jdk-21
export PATH="$JAVA_HOME/bin:$PATH"
```

## Building a working package from source

```bash
git clone https://github.com/punk64/Lantai.git
cd Lantai

cd lantai-webui-v2 && node build.js && cd ..

./gradlew :lantai:lantaiuiCopyIndex :lantai:bootJar -x test
```

Output:

```
lantai/build/libs/lantai-<version>.jar
```

**`lantaiuiCopyIndex` is not optional.** It copies `lantai-webui-v2/dist/` into `lantai/src/main/resources/public/` and generates `index-lantai.html` with the Thymeleaf tags. Running `bootJar` alone produces a jar with no frontend, or with the previous build's frontend, and the home page will not open.

Before copying, it removes the previous `js/lantai.*.js`, `css/lantai.*.css` and `index-lantai.html` from `public/`, so no stale hashed files accumulate.

`-x test` skips unit tests. The suite contains several long-standing failures; without the flag the build stops.

## Running and deploying

```bash
java -jar lantai/build/libs/lantai-<version>.jar --lantai.config-dir=/srv/lantai
```

| Item | Default | Notes |
|---|---|---|
| Port | `25600` | Change with `--server.port=8080` |
| Data directory | `~/.lantai` | Strongly recommended to set `--lantai.config-dir` explicitly |
| URL | `http://localhost:25600` | — |

The data directory will contain:

```
database.sqlite      libraries, series, books, users and metadata
tasks.sqlite         background task queue and history
sessions.sqlite      login sessions
lucene/              full-text search index
fonts/               uploaded custom fonts
logs/                runtime logs, including lantai.log and extensions.log
```

**Never delete the data directory when upgrading** — everything lives there.

> It listens on localhost only by default. Before binding `0.0.0.0`, putting it behind a reverse proxy or deploying to a public host, confirm it fits the [terms of use](#terms-of-use): public registration open to an unspecified audience is not permitted.

### First start

When the database contains no users, startup generates a random administrator account, **printed exactly once** to both the console and `logs/lantai.log`:

```
============================================================
  兰台已生成管理员账号（仅首次启动生成，请立即记录）
  登录名：admin-xxxxxxxx
  密  码：************************
  登录后请尽快在「账户」中修改密码
============================================================
```

Log in, change the password under *Settings → Account*, then create regular users as needed. The account is generated only on the first start and is not printed again on restarts; a forgotten password means editing the database or starting over.

### Start on boot

On Linux with systemd:

```ini
[Unit]
Description=Lantai
After=network.target

[Service]
User=lantai
ExecStart=/usr/bin/java -jar /opt/lantai/lantai-<version>.jar --lantai.config-dir=/srv/lantai
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

On Windows, create a Task Scheduler task triggered "At startup" running `javaw -jar <jar> --lantai.config-dir=<data dir>`.

### Docker

Images are named by version and base system. Both run the same `lantai-<version>.jar`, and the upgrade package is shared:

| Tag | Base image | Size | Difference |
|---|---|---|---|
| `lantai:<version>-alpine` | `eclipse-temurin:21-jre-alpine` | ~531 MB | Slim. JRE, fonts, timezone and image decoders only. **Calibre is unavailable** (no official musl build), so MOBI/AZW3 need an external converter |
| `lantai:<version>-ubuntu` | `ubuntu:26.04` | ~1.09 GB | Full. Adds poppler, kepubify, libjxl / libheif / libwebp and Qt runtime, supporting KEPUB conversion and more image formats |

Build (from the repo root, `context` pointing at the `lantai/` module):

```bash
cd lantai-webui-v2 && node build.js && cd ..
./gradlew :lantai:bootJar -x test
cd lantai
docker build -f docker/Dockerfile.alpine --build-arg VERSION=<version> -t lantai:<version>-alpine .
docker build -f docker/Dockerfile.lantai --build-arg VERSION=<version> -t lantai:<version>-ubuntu .
```

Inside the container the data directory is fixed at `/config` (SQLite, Lucene index, avatars, upgrade cache) and the library is mounted at `/books`.

#### Full `docker-compose.full.yml`

Host networking, online sources available; the image ships no plugin, and the server address is filled in by the user. Two volumes.

> `network_mode: host` makes the service listen on every network interface of the host. If the host has a public address, restrict access with a firewall or reverse proxy yourself, and confirm it fits the [terms of use](#terms-of-use).

```yaml
name: lantai

services:
  lantai:
    image: lantai:${LANTAI_VERSION:-0.7.6}-ubuntu
    build:
      context: ..
      dockerfile: docker/Dockerfile.lantai
      args:
        VERSION: ${LANTAI_VERSION:-0.7.6}
        WITH_KEPUBIFY: "true"
    container_name: lantai
    network_mode: host
    environment:
      TZ: Asia/Shanghai
      SERVER_PORT: ${LANTAI_PORT:-25600}
      JAVA_TOOL_OPTIONS: "-Xmx2g -Djava.net.preferIPv4Stack=true"
      LANTAI_CONFIGDIR: /config
    volumes:
      - ./config:/config
      - ./books:/books
    restart: unless-stopped
    stop_grace_period: 40s
    shm_size: 256m
    mem_limit: 3g
    healthcheck:
      test: ["CMD", "curl", "-fsS", "-o", "/dev/null", "http://127.0.0.1:25600/"]
      interval: 30s
      timeout: 5s
      start_period: 120s
      retries: 3
```

#### Slim `docker-compose.slim.yml`

Bridge network with a port mapping, only the data directory and library mounted, online sources disabled, 1.5 GB memory cap — suited to small machines or purely offline reading.

```yaml
name: lantai

services:
  lantai:
    image: lantai:${LANTAI_VERSION:-0.7.6}-alpine
    build:
      context: ..
      dockerfile: docker/Dockerfile.alpine
      args:
        VERSION: ${LANTAI_VERSION:-0.7.6}
    container_name: lantai
    ports:
      - "${LANTAI_PORT:-25600}:25600"
    environment:
      TZ: Asia/Shanghai
      JAVA_TOOL_OPTIONS: "-Xmx1g -Djava.net.preferIPv4Stack=true"
      LANTAI_CONFIGDIR: /config
      LANTAI_ONLINE_ENABLED: "false"
    volumes:
      - ./config:/config
      - ./books:/books:ro
    restart: unless-stopped
    stop_grace_period: 40s
    mem_limit: 1536m
    healthcheck:
      test: ["CMD", "wget", "-q", "--spider", "http://127.0.0.1:25600/"]
      interval: 30s
      timeout: 5s
      start_period: 90s
      retries: 3
```

```bash
docker compose -f docker-compose.slim.yml up -d
docker compose -f docker-compose.full.yml logs -f lantai
```

The first start still prints the random administrator account to the log. If `./config` is auto-created by Docker it is owned by root; the container runs as root and can write directly. Running as non-root requires a manual `chown`.

## Not in this repository

The following are intentionally excluded:

- Both legacy Komga frontends (Vue 2 and Vite) and all their build output
- The desktop tray application `lantai-tray`
- Local packaging and launch scripts (upgrade package, portable build, `启动兰台.bat`)
- Runtime data `RunData/`
- Any third-party plugin or online content, or mirror / re-upload of them (see below)

## Online sources and responsibility

**The online-source feature exists to stay compatible with the upstream Komga project: an upstream Komga server is connected as an online source, its library, series, chapters and page images are read and merged into this instance's library. This project does not provide, maintain, distribute or recommend any third-party plugin or content.**

### Release packages and plugins

- Neither the repository nor the release packages contain any third-party plugin (`.jar` / `.apk`) or online content, nor any mirror or re-upload of them
- No server address is preset; the list is empty on first visit and must be filled in by the user
- No web scraping engine is included; all online-source content comes from the Komga server the user connects, and local TXT / EPUB files are parsed by the scanner with no network fetching
- This project does not provide any specific server address or plugin origin in its documentation, issues or discussions

### Integration boundary

The online-source feature does one thing only: it connects a Komga-compatible server as an online source and reads its library, series, chapters and page images. It embeds no server, plugin or content. Its boundary is:

- It only loads plugin programs supplied and installed by the user
- It does not select, review, recommend or distribute any third-party plugin or content
- Every request is initiated by the user; network requests are issued directly by the deployed instance, with no relay through this project or any third-party server
- Plugin versions, availability and behaviour are decided by their authors, with no warranty from this project

### Terms of use

Lantai is self-hosted software, not an online service. The following boundaries are part of its terms of use:

- **Permitted**: deployment on a personal device or home network for yourself and household members, importing content you lawfully own, connecting to Komga servers you have verified yourself
- **Not permitted**: deployment as a public service open to an unspecified audience, including public registration, public share links and public server entry points
- **Not permitted**: providing paid services, download-on-behalf services or content distribution services built on Lantai
- **Not permitted**: redistributing Lantai bundled with preloaded plugins or repository URLs

Crossing either of the first two changes the nature of the activity from private copying to making works available to the public, with entirely different rules and consequences, and that shift rests with the person deploying it.

### Ownership of downloaded content

Chapters read through online sources are currently saved as CBZ files and copied into the library directory of the deployed instance, with the book record pointing at that copy. Those bytes are runtime data, managed and cleaned up by the user:

- Not distributed with this repository or its release packages
- Library directories are outside the default backup scope; back up each library root yourself when needed
- A second copy exists under the online-source download directory as the original file, included in backups only when the "downloaded content" option is enabled

### User responsibility

- The legality, accuracy and availability of the content on a connected server are the responsibility of that server and its operator; this project makes no warranty
- Users are responsible for confirming that the content they access is lawful where they are, and for complying with the terms of the connected server
- Any server address, index, plugin collection or "dedicated source" offered or promoted under the Lantai name is unrelated to this project and is not endorsed by it
- Mirrors, bundles or one-click deployment scripts repackaged by third parties with preloaded plugins or server addresses are not releases of this project, and this project is not responsible for their content or legality

If you are a rights holder and believe this project is at fault, please open an issue in the repository; we will respond within a reasonable time after receiving a valid notice.

## License

MIT, see `LICENSE`. The original project is copyright Gauthier Roebroeck;
Lantai's derivative parts are also released under MIT, retaining the original copyright notice.
