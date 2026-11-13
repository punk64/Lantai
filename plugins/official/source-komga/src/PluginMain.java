import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class PluginMain {
  private static final String VERSION = "0.1.8";
  private static final String PLUGIN_ID = env("LANTAI_PLUGIN_ID", "lantai.source-komga");
  private static final int PORT = intEnv("LANTAI_PLUGIN_PORT", 26920);
  private static final String HOST_KEY = env("LANTAI_PLUGIN_HOST_KEY", "");
  private static final Path PLUGIN_HOME = pluginHome();
  private static final Path DATA_DIR = Paths.get(env("LANTAI_PLUGIN_DATA", "data")).toAbsolutePath();
  private static final Path CONFIG_FILE = DATA_DIR.resolve("config.json");
  private static final Path MAP_FILE = DATA_DIR.resolve("map.jsonl");
  private static final Path WEB_INDEX = PLUGIN_HOME.resolve("web").resolve("index.html");
  private static final long STARTED = System.currentTimeMillis();
  private static final int PAGE_SIZE = 24;
  private static final int CHAPTER_BATCH = 500;
  private static final int MAX_JSON_BYTES = 8 * 1024 * 1024;
  private static final int MAX_IMAGE_BYTES = 24 * 1024 * 1024;
  private static final int MAX_FILE_BYTES = 320 * 1024 * 1024;
  private static final Object MAP_LOCK = new Object();
  private static final Object LOGIN_LOCK = new Object();
  private static final Object CONFIG_LOCK = new Object();
  private static final Deque<String> LOG_BUFFER = new ConcurrentLinkedDeque<>();
  private static final ExecutorService POOL = Executors.newFixedThreadPool(12);

  private static final Map<String, Long> SERIES_ID = new ConcurrentHashMap<>();
  private static final Map<Long, String> SERIES_ULID = new ConcurrentHashMap<>();
  private static final Map<String, Long> CHAPTER_ID = new ConcurrentHashMap<>();
  private static final Map<Long, String> CHAPTER_ULID = new ConcurrentHashMap<>();
  private static final Map<Long, Integer> CHAPTER_INDEX = new ConcurrentHashMap<>();
  private static final Map<Long, Long> CHAPTER_MANGA = new ConcurrentHashMap<>();
  private static final AtomicLong NEXT_SERIES = new AtomicLong(1);
  private static final AtomicLong NEXT_CHAPTER = new AtomicLong(1);

  private static final Map<String, String> SETTINGS = new LinkedHashMap<>();
  private static volatile String baseUrl = "";
  private static volatile String email = "";
  private static volatile String password = "";
  private static volatile String apiKey = "";
  private static volatile String cookie = "";
  private static volatile String user = "";
  private static volatile String lastError = "";
  private static volatile long lastChecked = 0L;
  private static volatile boolean ready = false;
  private static volatile List<Object> librariesCache = new ArrayList<>();
  private static volatile long librariesCacheAt = 0L;
  private static volatile String lastLoginError = "";
  private static volatile String loginTrace = "";
  private static volatile String basicAuth = "";

  public static void main(String[] args) throws Exception {
    Files.createDirectories(DATA_DIR);
    loadSettings();
    loadConfig();
    loadMap();
    ServerSocket server = new ServerSocket(PORT, 128, InetAddress.getByName("127.0.0.1"));
    log("listening on 127.0.0.1:" + PORT + " version " + VERSION);
    Thread probe = new Thread(PluginMain::probeOnBoot, "komga-probe");
    probe.setDaemon(true);
    probe.start();
    while (true) {
      final Socket socket = server.accept();
      POOL.submit(
          () -> {
            try {
              handle(socket);
            } catch (Throwable error) {
              log("handle error: " + message(error));
              try {
                respond(socket, 500, "{\"error\":" + quote(error.getClass().getSimpleName()) + ",\"message\":" + quote(message(error)) + "}");
              } catch (IOException ignored) {
              }
            } finally {
              try {
                socket.close();
              } catch (IOException ignored) {
              }
            }
          });
    }
  }

  private static void probeOnBoot() {
    if (baseUrl.isEmpty()) return;
    try {
      refreshSession(true);
      loadLibraries(true);
      log("boot probe ok: " + librariesCache.size() + " libraries");
    } catch (Exception error) {
      lastError = message(error);
      log("boot probe failed: " + lastError);
    }
  }

  private static Path pluginHome() {
    String home = env("LANTAI_PLUGIN_HOME", "");
    if (!home.isEmpty()) return Paths.get(home).toAbsolutePath();
    try {
      Path self = Paths.get(PluginMain.class.getProtectionDomain().getCodeSource().getLocation().toURI());
      Path bin = self.getParent();
      return bin == null ? Paths.get(".").toAbsolutePath() : bin.getParent();
    } catch (Exception error) {
      return Paths.get(".").toAbsolutePath();
    }
  }

  private static void loadSettings() {
    String raw = env("LANTAI_PLUGIN_SETTINGS", "");
    if (raw.isEmpty()) return;
    Object parsed = parseJson(raw);
    if (!(parsed instanceof Map)) return;
    for (Map.Entry<?, ?> entry : ((Map<?, ?>) parsed).entrySet()) {
      if (entry.getValue() != null) SETTINGS.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
    }
  }

  private static int timeoutSeconds() {
    String raw = SETTINGS.getOrDefault("timeoutSeconds", "");
    try {
      int value = Integer.parseInt(raw.trim());
      if (value >= 5 && value <= 120) return value;
    } catch (Exception ignored) {
    }
    return 20;
  }

  private static void loadConfig() {
    if (!Files.isRegularFile(CONFIG_FILE)) return;
    try {
      Object parsed = parseJson(Files.readString(CONFIG_FILE, StandardCharsets.UTF_8));
      if (!(parsed instanceof Map)) return;
      Map<String, Object> root = castMap(parsed);
      baseUrl = str(root.get("baseUrl")).trim();
      email = str(root.get("email")).trim();
      password = str(root.get("password"));
      apiKey = str(root.get("apiKey")).trim();
      cookie = str(root.get("cookie"));
      user = str(root.get("user")).trim();
      if (!email.isEmpty() && !password.isEmpty()) {
        basicAuth = Base64.getEncoder().encodeToString((email + ":" + password).getBytes(StandardCharsets.UTF_8));
      }
    } catch (Exception error) {
      log("config read failed: " + message(error));
    }
  }

  private static void saveConfig() {
    synchronized (CONFIG_LOCK) {
      Map<String, Object> out = new LinkedHashMap<>();
      out.put("baseUrl", baseUrl);
      out.put("email", email);
      out.put("password", password);
      out.put("apiKey", apiKey);
      out.put("cookie", cookie);
      out.put("user", user);
      try {
        Files.createDirectories(DATA_DIR);
        Path temp = DATA_DIR.resolve("config.json.tmp");
        Files.writeString(temp, toJson(out), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.move(temp, CONFIG_FILE, StandardCopyOption.REPLACE_EXISTING);
        tighten(CONFIG_FILE);
      } catch (Exception error) {
        log("config write failed: " + message(error));
      }
    }
  }

  private static void tighten(Path file) {
    try {
      Set<PosixFilePermission> perms = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
      Files.setPosixFilePermissions(file, perms);
    } catch (Exception ignored) {
    }
  }

  private static void loadMap() {
    if (!Files.isRegularFile(MAP_FILE)) return;
    synchronized (MAP_LOCK) {
      long maxSeries = 0L;
      long maxChapter = 0L;
      try {
        for (String line : Files.readAllLines(MAP_FILE, StandardCharsets.UTF_8)) {
          if (line.isBlank()) continue;
          Object parsed = parseJson(line);
          if (!(parsed instanceof Map)) continue;
          Map<String, Object> row = castMap(parsed);
          String type = str(row.get("t"));
          long id = (long) num(row.get("i"), 0);
          String key = str(row.get("k"));
          if (id <= 0 || key.isEmpty()) continue;
          if ("s".equals(type)) {
            SERIES_ID.put(key, id);
            SERIES_ULID.put(id, key.substring(key.indexOf('|') + 1));
            maxSeries = Math.max(maxSeries, id);
          } else if ("c".equals(type)) {
            long manga = (long) num(row.get("m"), 0);
            int index = (int) num(row.get("x"), 0);
            CHAPTER_ULID.put(id, key);
            CHAPTER_INDEX.put(id, index);
            CHAPTER_MANGA.put(id, manga);
            CHAPTER_ID.put(manga + "|" + index, id);
            maxChapter = Math.max(maxChapter, id);
          }
        }
      } catch (Exception error) {
        log("map read failed: " + message(error));
      }
      NEXT_SERIES.set(maxSeries + 1);
      NEXT_CHAPTER.set(maxChapter + 1);
      log("map loaded: series=" + SERIES_ULID.size() + " chapters=" + CHAPTER_ULID.size());
    }
  }

  private static void appendMap(Map<String, Object> row) {
    synchronized (MAP_LOCK) {
      try {
        Files.writeString(
            MAP_FILE,
            toJson(row) + "\n",
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND);
      } catch (Exception error) {
        log("map write failed: " + message(error));
      }
    }
  }

  private static long seriesId(String sourceId, String ulid) {
    String key = sourceId + "|" + ulid;
    Long known = SERIES_ID.get(key);
    if (known != null) return known;
    synchronized (MAP_LOCK) {
      Long again = SERIES_ID.get(key);
      if (again != null) return again;
      long id = NEXT_SERIES.getAndIncrement();
      SERIES_ID.put(key, id);
      SERIES_ULID.put(id, ulid);
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("t", "s");
      row.put("i", id);
      row.put("k", key);
      appendMap(row);
      return id;
    }
  }

  private static long chapterId(long mangaId, int index, String ulid) {
    String key = mangaId + "|" + index;
    Long known = CHAPTER_ID.get(key);
    if (known != null) return known;
    synchronized (MAP_LOCK) {
      Long again = CHAPTER_ID.get(key);
      if (again != null) return again;
      long id = NEXT_CHAPTER.getAndIncrement();
      CHAPTER_ID.put(key, id);
      CHAPTER_ULID.put(id, ulid);
      CHAPTER_INDEX.put(id, index);
      CHAPTER_MANGA.put(id, mangaId);
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("t", "c");
      row.put("i", id);
      row.put("m", mangaId);
      row.put("x", index);
      row.put("k", ulid);
      appendMap(row);
      return id;
    }
  }

  private static String chapterUlid(long mangaId, int index) {
    Long id = CHAPTER_ID.get(mangaId + "|" + index);
    return id == null ? "" : CHAPTER_ULID.getOrDefault(id, "");
  }

  private static void handle(Socket socket) throws IOException {
    socket.setSoTimeout(120_000);
    InputStream input = socket.getInputStream();
    String requestLine = readLine(input);
    if (requestLine == null || requestLine.isEmpty()) return;
    String[] parts = requestLine.split(" ");
    if (parts.length < 2) {
      respond(socket, 400, "{\"error\":\"bad request line\"}");
      return;
    }
    String method = parts[0].toUpperCase(Locale.ROOT);
    String target = parts[1];
    String rawPath = target;
    String query = "";
    int mark = target.indexOf('?');
    if (mark >= 0) {
      rawPath = target.substring(0, mark);
      query = target.substring(mark + 1);
    }
    String path = decodePath(rawPath);

    Map<String, String> headers = new LinkedHashMap<>();
    while (true) {
      String line = readLine(input);
      if (line == null || line.isEmpty()) break;
      int colon = line.indexOf(':');
      if (colon > 0) headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
    }

    boolean open = "/".equals(rawPath) || rawPath.startsWith("/v1/panel");
    if (!HOST_KEY.isEmpty() && !open && !HOST_KEY.equals(headers.get("x-lantai-host-key"))) {
      respond(socket, 401, "{\"error\":\"host key mismatch\"}");
      return;
    }

    byte[] rawBody = readBody(input, headers);
    String body = rawBody.length == 0 ? "" : new String(rawBody, StandardCharsets.UTF_8);
    Map<String, String> params = parseQuery(query);

    if ("GET".equals(method) && ("/".equals(path) || "/v1/panel".equals(path) || "/v1/panel/".equals(path))) {
      respondHtml(socket);
      return;
    }
    if ("GET".equals(method) && ("/v1/manifest".equals(path) || "/v1/panel/manifest".equals(path))) {
      respond(socket, 200, manifest());
      return;
    }
    if ("GET".equals(method) && "/v1/health".equals(path)) {
      respond(socket, 200, "{\"ok\":true}");
      return;
    }
    if ("GET".equals(method) && "/v1/panel/status".equals(path)) {
      respond(socket, 200, toJson(panelStatus()));
      return;
    }
    if ("GET".equals(method) && "/v1/panel/log".equals(path)) {
      Map<String, Object> out = new LinkedHashMap<>();
      out.put("lines", new ArrayList<>(LOG_BUFFER));
      respond(socket, 200, toJson(out));
      return;
    }
    if ("POST".equals(method) && "/v1/panel/login".equals(path)) {
      handleLogin(socket, body);
      return;
    }
    if ("POST".equals(method) && "/v1/panel/logout".equals(path)) {
      baseUrl = "";
      email = "";
      password = "";
      apiKey = "";
      cookie = "";
      user = "";
      ready = false;
      librariesCache = new ArrayList<>();
      librariesCacheAt = 0L;
      saveConfig();
      respond(socket, 200, "{\"ok\":true}");
      return;
    }
    if ("POST".equals(method) && "/v1/panel/test".equals(path)) {
      handleTest(socket, body);
      return;
    }
    if ("GET".equals(method) && "/v1/panel/libraries".equals(path)) {
      try {
        loadLibraries(true);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", librariesCache);
        out.put("total", librariesCache.size());
        respond(socket, 200, toJson(out));
      } catch (Exception error) {
        respond(socket, 502, "{\"error\":" + quote(message(error)) + "}");
      }
      return;
    }

    if (!path.startsWith("/v1/source")) {
      respond(socket, 404, "{\"error\":\"not found\"}");
      return;
    }

    String tail = path.substring("/v1/source".length());
    if (tail.startsWith("/")) tail = tail.substring(1);
    String[] segments = tail.isEmpty() ? new String[0] : tail.split("/");
    for (int index = 0; index < segments.length; index++) segments[index] = decodePath(segments[index]);

    try {
      if (segments.length == 1 && "sources".equals(segments[0])) {
        respond(socket, 200, toJson(providedSources()));
        return;
      }
      if (segments.length == 3 && "sources".equals(segments[0])) {
        String sourceId = segments[1];
        if ("search".equals(segments[2])) {
          String q = params.getOrDefault("q", "").trim();
          int page = intParam(params, "page", 1);
          respond(socket, 200, toJson(search(sourceId, q, page)));
          return;
        }
        if ("browse".equals(segments[2])) {
          String type = params.getOrDefault("type", "POPULAR").trim().toUpperCase(Locale.ROOT);
          String q = params.getOrDefault("q", "").trim();
          int page = intParam(params, "page", 1);
          respond(socket, 200, toJson(browse(sourceId, type, q, page)));
          return;
        }
        if ("filters".equals(segments[2])) {
          respond(socket, 200, "[]");
          return;
        }
      }
      if (segments.length >= 2 && "manga".equals(segments[0])) {
        long mangaId = parseLong(segments[1]);
        if (segments.length == 3 && "chapters".equals(segments[2])) {
          respond(socket, 200, toJson(chapters(mangaId)));
          return;
        }
        if (segments.length == 3 && "thumbnail".equals(segments[2])) {
          byte[] bytes = thumbnail(mangaId);
          if (bytes == null || bytes.length == 0) {
            respond(socket, 404, "{\"error\":\"no thumbnail\"}");
          } else {
            respondBytes(socket, 200, "image/jpeg", bytes);
          }
          return;
        }
        if (segments.length == 6 && "chapters".equals(segments[2]) && "pages".equals(segments[4])) {
          int chapterIndex = (int) parseLong(segments[3]);
          int pageIndex = (int) parseLong(segments[5]);
          byte[] bytes = page(mangaId, chapterIndex, pageIndex);
          if (bytes == null || bytes.length == 0) {
            respond(socket, 404, "{\"error\":\"no page\"}");
          } else {
            respondBytes(socket, 200, "image/jpeg", bytes);
          }
          return;
        }
        if (segments.length == 5 && "chapters".equals(segments[2]) && "download".equals(segments[4])) {
          int chapterIndex = (int) parseLong(segments[3]);
          byte[] bytes = download(mangaId, chapterIndex);
          if (bytes == null || bytes.length == 0) {
            respond(socket, 404, "{\"error\":\"no file\"}");
          } else {
            respondBytes(socket, 200, "application/octet-stream", bytes);
          }
          return;
        }
        if (segments.length == 2) {
          respond(socket, 200, toJson(manga(mangaId)));
          return;
        }
      }
      if (segments.length >= 3 && "chapters".equals(segments[0])) {
        long chapterId = parseLong(segments[1]);
        if (segments.length == 3 && "pages".equals(segments[2])) {
          Map<String, Object> out = new LinkedHashMap<>();
          out.put("pageCount", chapterPages(chapterId));
          respond(socket, 200, toJson(out));
          return;
        }
        if (segments.length == 3 && "content".equals(segments[2])) {
          Object content = content(chapterId);
          if (content == null) {
            respond(socket, 404, "{\"error\":\"no text\"}");
          } else {
            respond(socket, 200, toJson(content));
          }
          return;
        }
        if (segments.length >= 4 && "assets".equals(segments[2])) {
          respond(socket, 404, "{\"error\":\"no assets\"}");
          return;
        }
      }
      respond(socket, 404, "{\"error\":\"not found\"}");
    } catch (Exception error) {
      lastError = message(error);
      log("source error " + method + " " + path + ": " + lastError);
      respond(socket, 502, "{\"error\":" + quote(lastError) + "}");
    }
  }

  private static void handleLogin(Socket socket, String body) throws IOException {
    Object parsed = parseJson(body);
    if (!(parsed instanceof Map)) {
      respond(socket, 400, "{\"ok\":false,\"error\":{\"message\":\"请求体不是 JSON 对象\"}}");
      return;
    }
    Map<String, Object> root = castMap(parsed);
    String url = normalizeBase(str(root.get("baseUrl")));
    String mail = str(root.get("email")).trim();
    String pass = str(root.get("password"));
    String key = str(root.get("apiKey")).trim();
    if (url.isEmpty()) {
      respond(socket, 400, "{\"ok\":false,\"error\":{\"message\":\"请填写 Komga 地址，例如 http://192.168.1.10:25600\"}}");
      return;
    }
    boolean passwordLogin = !mail.isEmpty() && !pass.isEmpty();
    if (key.isEmpty() && !passwordLogin) {
      key = apiKey;
      if (mail.isEmpty()) mail = email;
      if (pass.isEmpty()) pass = password;
    }
    if (key.isEmpty() && (mail.isEmpty() || pass.isEmpty())) {
      respond(socket, 400, "{\"ok\":false,\"error\":{\"message\":\"请填写邮箱与密码，或改用 API Key 登录\"}}");
      return;
    }
    synchronized (LOGIN_LOCK) {
      baseUrl = url;
      email = mail;
      password = pass;
      apiKey = key;
      cookie = "";
      user = "";
      basicAuth = "";
      ready = false;
      librariesCache = new ArrayList<>();
      librariesCacheAt = 0L;
      try {
        refreshSession(true);
        loadLibraries(true);
        ready = true;
        lastError = "";
        lastChecked = System.currentTimeMillis();
        saveConfig();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("user", user);
        out.put("libraries", librariesCache.size());
        respond(socket, 200, toJson(out));
      } catch (Exception error) {
        ready = false;
        lastError = message(error);
        lastChecked = System.currentTimeMillis();
        saveConfig();
        respond(socket, 400, "{\"ok\":false,\"error\":{\"message\":" + quote(lastError) + "}}");
      }
    }
  }

  private static void handleTest(Socket socket, String body) throws IOException {
    Map<String, Object> root = new LinkedHashMap<>();
    Object parsed = parseJson(body);
    if (parsed instanceof Map) root = castMap(parsed);
    String url = normalizeBase(str(root.get("baseUrl")));
    String mail = str(root.get("email")).trim();
    String pass = str(root.get("password"));
    String key = str(root.get("apiKey")).trim();
    synchronized (LOGIN_LOCK) {
      String keepUrl = baseUrl;
      String keepMail = email;
      String keepPass = password;
      String keepKey = apiKey;
      String keepCookie = cookie;
      String keepUser = user;
      String keepError = lastError;
      long keepChecked = lastChecked;
      long keepAt = librariesCacheAt;
      boolean keepReady = ready;
      List<Object> keepLibs = librariesCache;
      try {
        if (!url.isEmpty()) {
          baseUrl = url;
          if (!mail.isEmpty()) email = mail;
          if (!pass.isEmpty()) password = pass;
          if (!key.isEmpty()) apiKey = key;
          cookie = "";
          user = "";
        }
        refreshSession(true);
        loadLibraries(true);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("user", user);
        out.put("libraries", librariesCache.size());
        respond(socket, 200, toJson(out));
      } catch (Exception error) {
        respond(socket, 502, "{\"ok\":false,\"error\":{\"message\":" + quote(message(error)) + "}}");
      } finally {
        baseUrl = keepUrl;
        email = keepMail;
        password = keepPass;
        apiKey = keepKey;
        cookie = keepCookie;
        user = keepUser;
        lastError = keepError;
        lastChecked = keepChecked;
        librariesCacheAt = keepAt;
        ready = keepReady;
        librariesCache = keepLibs;
      }
    }
  }

  private static Map<String, Object> panelStatus() {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("state", baseUrl.isEmpty() ? "empty" : (ready ? "running" : "error"));
    out.put("ready", ready && !baseUrl.isEmpty());
    out.put("version", VERSION);
    out.put("baseUrl", baseUrl);
    out.put("email", email);
    out.put("user", user);
    out.put("apiKeySet", !apiKey.isEmpty());
    out.put("passwordSet", !password.isEmpty());
    out.put("cookieSet", !cookie.isEmpty());
    out.put("libraries", librariesCache.size());
    out.put("lastError", lastError);
    out.put("lastChecked", lastChecked);
    out.put("uptimeSeconds", (System.currentTimeMillis() - STARTED) / 1000);
    out.put("mappedSeries", SERIES_ULID.size());
    out.put("mappedChapters", CHAPTER_ULID.size());
    out.put("dataDir", DATA_DIR.toString());
    out.put("hostKeySet", !HOST_KEY.isEmpty());
    return out;
  }

  private static String manifest() {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("id", PLUGIN_ID);
    out.put("name", "Komga 书库源");
    out.put("version", VERSION);
    out.put("protocol", 2);
    out.put("minHost", "0.6.30");
    out.put("description", "把自部署的 Komga 或 Komga 兼容服务端接成兰台在线源，插件内登录后读取书库、系列、章节与页图");
    out.put("capabilities", new ArrayList<>(List.of("source.provide", "ui.panel")));
    Map<String, Object> panel = new LinkedHashMap<>();
    panel.put("id", "console");
    panel.put("title", "Komga 连接");
    panel.put("icon", "server");
    panel.put("group", "library");
    panel.put("path", "/");
    panel.put("roles", new ArrayList<>(List.of("ADMIN")));
    out.put("panels", new ArrayList<>(List.of(panel)));
    return toJson(out);
  }

  private static String normalizeBase(String raw) {
    String value = raw == null ? "" : raw.trim();
    if (value.isEmpty()) return "";
    if (!value.startsWith("http://") && !value.startsWith("https://")) value = "http://" + value;
    while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
    return value;
  }

  private static void refreshSession(boolean force) {
    if (baseUrl.isEmpty()) throw new IllegalStateException("尚未配置 Komga 地址");
    if (!apiKey.isEmpty()) {
      basicAuth = "";
      return;
    }
    if (!force && (!cookie.isEmpty() || !basicAuth.isEmpty())) return;
    if (email.isEmpty() || password.isEmpty()) throw new IllegalStateException("缺少登录凭据，请在控制台重新登录");
    lastLoginError = "";
    loginTrace = "";
    if (loginWithBasic()) return;
    String viaCookie = loginWithCookie();
    if (!viaCookie.isEmpty()) {
      cookie = viaCookie;
      return;
    }
    String viaJson = loginWithJson();
    if (!viaJson.isEmpty()) {
      cookie = viaJson;
      return;
    }
    if (!lastLoginError.isEmpty()) throw new IllegalStateException("连接失败：" + lastLoginError);
    throw new IllegalStateException("登录失败：邮箱或密码不正确，或该实例关闭了密码登录，请改用 API Key" + (loginTrace.isEmpty() ? "" : "（" + loginTrace + "）"));
  }

  private static void appendTrace(String name, int status, String note) {
    if (loginTrace.length() > 0) loginTrace = loginTrace + " ";
    loginTrace = loginTrace + name + "=" + (status == 0 ? "异常" : String.valueOf(status)) + (note.isEmpty() ? "" : "(" + note + ")");
  }

  private static boolean loginWithBasic() {
    if (email.isEmpty() || password.isEmpty()) return false;
    basicAuth = Base64.getEncoder().encodeToString((email + ":" + password).getBytes(StandardCharsets.UTF_8));
    try {
      Reply reply = request("GET", "/api/v1/users/me", "application/json", null, false);
      if (reply.status >= 200 && reply.status < 300) {
        Object parsed = parseJson(new String(reply.body, StandardCharsets.UTF_8));
        if (parsed instanceof Map) {
          String mail = str(castMap(parsed).get("email")).trim();
          if (!mail.isEmpty()) user = mail;
        }
        if (user.isEmpty()) user = email;
        cookie = "";
        try {
          Reply session = request("GET", "/api/v1/login/set-cookie", "application/json", null, false);
          if (session.status >= 200 && session.status < 300) {
            String found = sessionFromSetCookie(session.setCookie);
            if (!found.isEmpty()) cookie = found;
          }
        } catch (Exception ignored) {
        }
        lastLoginError = "";
        return true;
      }
      if (reply.status == 401 || reply.status == 403) {
        lastLoginError = "Basic 认证被拒（HTTP " + reply.status + "）";
        basicAuth = "";
        return false;
      }
      lastLoginError = "HTTP " + reply.status;
      basicAuth = "";
    } catch (Exception error) {
      lastLoginError = message(error);
      basicAuth = "";
    }
    return false;
  }

  private static String loginWithCookie() {
    try {
      String encodedMail = URLEncoder.encode(email, StandardCharsets.UTF_8);
      String encodedPass = URLEncoder.encode(password, StandardCharsets.UTF_8);
      Reply reply = request("GET", "/api/v1/login/set-cookie?email=" + encodedMail + "&password=" + encodedPass, "application/json", null, false);
      if (reply.status >= 200 && reply.status < 300) {
        String found = sessionFromSetCookie(reply.setCookie);
        if (!found.isEmpty()) return found;
        appendTrace("v1", reply.status, "无session");
        return "";
      }
      appendTrace("v1", reply.status, "");
      if (reply.status == 401 || reply.status == 403 || reply.status == 404) return "";
      lastLoginError = "HTTP " + reply.status;
    } catch (Exception error) {
      appendTrace("v1", 0, "异常");
      lastLoginError = message(error);
      log("cookie login failed: " + message(error));
    }
    return "";
  }

  private static String loginWithJson() {
    try {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("email", email);
      payload.put("password", password);
      Reply reply = request("POST", "/api/v2/users/login", "application/json", toJson(payload).getBytes(StandardCharsets.UTF_8), false);
      if (reply.status >= 200 && reply.status < 300) {
        String found = sessionFromSetCookie(reply.setCookie);
        if (!found.isEmpty()) return found;
      }
      appendTrace("v2", reply.status, "");
      if (reply.status != 401 && reply.status != 403) lastLoginError = "HTTP " + reply.status;
    } catch (Exception error) {
      appendTrace("v2", 0, "异常");
      lastLoginError = message(error);
      log("json login failed: " + message(error));
    }
    return "";
  }

  private static String sessionFromSetCookie(String raw) {
    if (raw == null || raw.isEmpty()) return "";
    String fallback = "";
    for (String chunk : raw.split("[,\\n]")) {
      String line = chunk.trim();
      if (line.isEmpty()) continue;
      int semi = line.indexOf(';');
      String pair = semi >= 0 ? line.substring(0, semi) : line;
      int eq = pair.indexOf('=');
      if (eq <= 0) continue;
      String name = pair.substring(0, eq).trim();
      String value = pair.substring(eq + 1).trim();
      if (value.isEmpty()) continue;
      if (name.equalsIgnoreCase("KOMGA-SESSION")) return name + "=" + value;
      if (fallback.isEmpty() && name.toUpperCase(Locale.ROOT).contains("SESSION")) fallback = name + "=" + value;
    }
    return fallback;
  }

  private static void ensureAuthorized() {
    if (!apiKey.isEmpty()) return;
    if (cookie.isEmpty()) refreshSession(false);
  }

  private static void loadLibraries(boolean force) {
    long now = System.currentTimeMillis();
    if (!force && !librariesCache.isEmpty() && now - librariesCacheAt < 60_000L) return;
    ensureAuthorized();
    Object parsed = getJson("/api/v1/libraries");
    if (!(parsed instanceof List)) throw new IllegalStateException("Komga 未返回书库列表");
    List<Object> rows = new ArrayList<>();
    for (Object item : (List<?>) parsed) {
      if (!(item instanceof Map)) continue;
      Map<String, Object> library = castMap(item);
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", str(library.get("id")));
      row.put("name", str(library.get("name")));
      row.put("type", str(library.get("type")));
      row.put("unavailable", library.get("unavailable"));
      rows.add(row);
    }
    librariesCache = rows;
    librariesCacheAt = now;
    ready = !rows.isEmpty();
  }

  private static List<Object> providedSources() {
    loadLibraries(false);
    List<Object> out = new ArrayList<>();
    for (Object item : librariesCache) {
      Map<String, Object> library = castMap(item);
      if ("ONLINE".equalsIgnoreCase(str(library.get("type")))) continue;
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", str(library.get("id")));
      row.put("name", str(library.get("name")));
      row.put("lang", "");
      row.put("baseUrl", baseUrl);
      row.put("contentType", "image");
      row.put("contentFormat", "html");
      out.add(row);
    }
    return out;
  }

  private static String sourceName(String sourceId) {
    for (Object item : librariesCache) {
      Map<String, Object> library = castMap(item);
      if (str(library.get("id")).equals(sourceId)) return str(library.get("name"));
    }
    return sourceId;
  }

  private static Map<String, Object> search(String sourceId, String query, int page) {
    return queryPage(sourceId, query, page, "lastModified,desc");
  }

  private static Map<String, Object> browse(String sourceId, String type, String query, int page) {
    if ("SEARCH".equals(type) && !query.isEmpty()) return queryPage(sourceId, query, page, "lastModified,desc");
    if ("LATEST".equals(type)) return queryPage(sourceId, "", page, "lastModified,desc");
    return queryPage(sourceId, "", page, "booksCount,desc");
  }

  private static Map<String, Object> queryPage(String sourceId, String query, int page, String sort) {
    ensureAuthorized();
    int zeroBased = Math.max(0, page - 1);
    String path =
        "/api/v1/series?page=" + zeroBased + "&size=" + PAGE_SIZE + "&sort=" + encode(sort) + "&library_id=" + encode(sourceId);
    if (!query.isEmpty()) path = path + "&search=" + encode(query);
    Object parsed = getJson(path);
    Map<String, Object> out = new LinkedHashMap<>();
    List<Object> mangas = new ArrayList<>();
    boolean hasNext = false;
    if (parsed instanceof Map) {
      Map<String, Object> root = castMap(parsed);
      Object content = root.get("content");
      if (content instanceof List) {
        for (Object item : (List<?>) content) {
          if (item instanceof Map) mangas.add(seriesToManga(castMap(item), sourceId));
        }
      }
      double totalPages = num(root.get("totalPages"), 0);
      hasNext = zeroBased + 1 < totalPages;
    }
    out.put("mangas", mangas);
    out.put("hasNextPage", hasNext);
    if (query.isEmpty() && "booksCount,desc".equals(sort)) {
      out.put("mangaList", mangas);
    }
    return out;
  }

  private static Map<String, Object> searchResult(String sourceId, String query, int page) {
    Map<String, Object> browse = queryPage(sourceId, query, page, "lastModified,desc");
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("mangaList", browse.get("mangas"));
    out.put("hasNextPage", browse.get("hasNextPage"));
    return out;
  }

  private static Map<String, Object> seriesToManga(Map<String, Object> series, String sourceId) {
    String ulid = str(series.get("id"));
    Map<String, Object> metadata = series.get("metadata") instanceof Map ? castMap(series.get("metadata")) : new LinkedHashMap<>();
    Map<String, Object> booksMetadata =
        series.get("booksMetadata") instanceof Map ? castMap(series.get("booksMetadata")) : new LinkedHashMap<>();
    String title = str(metadata.get("title"));
    if (title.isEmpty()) title = str(series.get("name"));
    List<Object> genres = new ArrayList<>();
    if (metadata.get("genres") instanceof List) {
      for (Object genre : (List<?>) metadata.get("genres")) {
        if (genre != null && !String.valueOf(genre).isBlank()) genres.add(String.valueOf(genre));
      }
    }
    String author = "";
    if (booksMetadata.get("authors") instanceof List) {
      for (Object entry : (List<?>) booksMetadata.get("authors")) {
        if (!(entry instanceof Map)) continue;
        String name = str(castMap(entry).get("name"));
        if (!name.isBlank()) {
          author = name;
          break;
        }
      }
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("id", seriesId(sourceId, ulid));
    out.put("sourceId", sourceId);
    out.put("title", title);
    out.put("author", author);
    out.put("artist", "");
    out.put("description", str(metadata.get("summary")));
    out.put("genre", genres);
    out.put("status", mapStatus(str(metadata.get("status"))));
    out.put("thumbnailUrl", "");
    out.put("inLibrary", true);
    return out;
  }

  private static String mapStatus(String status) {
    String value = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
    if ("ONGOING".equals(value)) return "ONGOING";
    if ("ENDED".equals(value)) return "COMPLETED";
    if ("ABANDONED".equals(value)) return "CANCELLED";
    if ("HIATUS".equals(value)) return "ON_HIATUS";
    return value;
  }

  private static Map<String, Object> manga(long mangaId) {
    ensureAuthorized();
    String ulid = SERIES_ULID.get(mangaId);
    if (ulid == null || ulid.isEmpty()) throw new IllegalStateException("未知的作品编号：" + mangaId);
    Object parsed = getJson("/api/v1/series/" + encode(ulid));
    if (!(parsed instanceof Map)) throw new IllegalStateException("上游未返回作品详情");
    String sourceId = sourceIdOf(mangaId);
    return seriesToManga(castMap(parsed), sourceId);
  }

  private static String sourceIdOf(long mangaId) {
    String ulid = SERIES_ULID.getOrDefault(mangaId, "");
    for (Map.Entry<String, Long> entry : SERIES_ID.entrySet()) {
      if (entry.getValue() == mangaId) {
        String key = entry.getKey();
        int bar = key.indexOf('|');
        return bar > 0 ? key.substring(0, bar) : "";
      }
    }
    return ulid.isEmpty() ? "" : "";
  }

  private static List<Object> chapters(long mangaId) {
    ensureAuthorized();
    String ulid = SERIES_ULID.get(mangaId);
    if (ulid == null || ulid.isEmpty()) throw new IllegalStateException("未知的作品编号：" + mangaId);
    List<Object> out = new ArrayList<>();
    int page = 0;
    int totalPages = 1;
    while (page < totalPages && page < 200) {
      Object parsed = getJson("/api/v1/series/" + encode(ulid) + "/books?page=" + page + "&size=" + CHAPTER_BATCH);
      if (!(parsed instanceof Map)) break;
      Map<String, Object> root = castMap(parsed);
      totalPages = (int) Math.max(1, num(root.get("totalPages"), 1));
      Object content = root.get("content");
      if (!(content instanceof List)) break;
      List<?> books = (List<?>) content;
      for (int offset = 0; offset < books.size(); offset++) {
        Object item = books.get(offset);
        if (!(item instanceof Map)) continue;
        int index = page * CHAPTER_BATCH + offset;
        out.add(bookToChapter(castMap(item), mangaId, index));
      }
      page++;
    }
    return out;
  }

  private static Map<String, Object> bookToChapter(Map<String, Object> book, long mangaId, int index) {
    String ulid = str(book.get("id"));
    Map<String, Object> media = book.get("media") instanceof Map ? castMap(book.get("media")) : new LinkedHashMap<>();
    Map<String, Object> metadata = book.get("metadata") instanceof Map ? castMap(book.get("metadata")) : new LinkedHashMap<>();
    String name = str(metadata.get("title"));
    if (name.isEmpty()) name = str(book.get("name"));
    if (name.isEmpty()) name = "第 " + (index + 1) + " 话";
    if (name.length() > 300) name = name.substring(0, 300);
    long id = chapterId(mangaId, index, ulid);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("id", id);
    out.put("name", name);
    out.put("index", index);
    out.put("chapterNumber", num(metadata.get("number"), index + 1));
    out.put("pageCount", (int) num(media.get("pagesCount"), -1));
    out.put("contentType", isEpub(media) ? "text" : "image");
    out.put("downloaded", false);
    out.put("uploadDate", 0);
    out.put("scanlator", "");
    out.put("realUrl", "");
    return out;
  }

  private static boolean isEpub(Map<String, Object> media) {
    return str(media.get("mediaType")).toLowerCase(Locale.ROOT).contains("epub");
  }

  private static Object content(long chapterId) {
    String ulid = CHAPTER_ULID.getOrDefault(chapterId, "");
    if (ulid.isEmpty()) return null;
    ensureAuthorized();
    Reply reply = request("GET", "/api/v1/books/" + encode(ulid) + "/file", "application/epub+zip", null, true);
    if (reply.status < 200 || reply.status >= 300 || reply.body == null || reply.body.length == 0) return null;
    String text = epubText(reply.body);
    if (text == null || text.isBlank()) return null;
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("format", "plain");
    out.put("content", text);
    out.put("chars", text.length());
    return out;
  }

  private static String epubText(byte[] bytes) {
    Map<String, byte[]> entries = new LinkedHashMap<>();
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        if (entry.isDirectory()) continue;
        entries.put(entry.getName(), readEntry(zip));
      }
    } catch (IOException error) {
      return null;
    }
    byte[] container = entries.get("META-INF/container.xml");
    if (container == null) return null;
    String opfPath = firstMatch(new String(container, StandardCharsets.UTF_8), "full-path\\s*=\\s*\"([^\"]+)\"", 1);
    if (opfPath.isEmpty()) return null;
    byte[] opfBytes = entries.get(opfPath);
    if (opfBytes == null) return null;
    String opf = new String(opfBytes, StandardCharsets.UTF_8);
    String opfDir = opfPath.contains("/") ? opfPath.substring(0, opfPath.lastIndexOf('/')) : "";
    Map<String, String> manifest = new LinkedHashMap<>();
    for (String item : allMatches(opf, "<item\\s[^>]*>")) {
      String id = firstMatch(item, "\\bid\\s*=\\s*\"([^\"]+)\"", 1);
      String href = firstMatch(item, "\\bhref\\s*=\\s*\"([^\"]+)\"", 1);
      if (!id.isEmpty() && !href.isEmpty()) manifest.put(id, href);
    }
    StringBuilder builder = new StringBuilder();
    for (String itemref : allMatches(opf, "<itemref\\s[^>]*>")) {
      String idref = firstMatch(itemref, "\\bidref\\s*=\\s*\"([^\"]+)\"", 1);
      String href = manifest.get(idref);
      if (href == null) continue;
      byte[] page = entries.get(resolveZipPath(opfDir, href));
      if (page == null) continue;
      String text = htmlToText(new String(page, StandardCharsets.UTF_8));
      if (text.isEmpty()) continue;
      if (builder.length() > 0) builder.append("\n\n");
      builder.append(text);
    }
    String text = builder.toString().trim();
    return text.isEmpty() ? null : text;
  }

  private static byte[] readEntry(InputStream input) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    byte[] chunk = new byte[16 * 1024];
    int read;
    while ((read = input.read(chunk)) > 0) buffer.write(chunk, 0, read);
    return buffer.toByteArray();
  }

  private static String resolveZipPath(String baseDir, String href) {
    String clean = href;
    int hash = clean.indexOf('#');
    if (hash >= 0) clean = clean.substring(0, hash);
    if (clean.startsWith("/")) return clean.substring(1);
    String combined = baseDir.isEmpty() ? clean : baseDir + "/" + clean;
    Deque<String> parts = new ArrayDeque<>();
    for (String part : combined.split("/")) {
      if (part.isEmpty() || ".".equals(part)) continue;
      if ("..".equals(part)) {
        if (!parts.isEmpty()) parts.removeLast();
        continue;
      }
      parts.addLast(part);
    }
    return String.join("/", parts);
  }

  private static String htmlToText(String html) {
    Matcher body = Pattern.compile("(?is)<body[^>]*>(.*)</body>").matcher(html);
    if (body.find()) html = body.group(1);
    String text = html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ");
    text = text.replaceAll("(?i)<br\\s*/?>", "\n");
    text = text.replaceAll("(?i)</(p|div|h[1-6]|li|tr|section|article|blockquote|pre)>", "\n");
    text = text.replaceAll("(?s)<[^>]+>", "");
    text = unescapeHtml(text);
    text = text.replaceAll("[\\t\\x0B\\f\\r]+", " ");
    text = text.replaceAll(" *\\n *", "\n");
    text = text.replaceAll("\\n{3,}", "\n\n");
    return text.trim();
  }

  private static String unescapeHtml(String text) {
    String out =
      text
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&");
    Matcher matcher = Pattern.compile("&#(x?)([0-9a-fA-F]+);").matcher(out);
    StringBuilder builder = new StringBuilder();
    while (matcher.find()) {
      int code = matcher.group(1).isEmpty() ? Integer.parseInt(matcher.group(2)) : Integer.parseInt(matcher.group(2), 16);
      if (code <= 0 || code > 0x10FFFF) continue;
      matcher.appendReplacement(builder, Matcher.quoteReplacement(new String(Character.toChars(code))));
    }
    matcher.appendTail(builder);
    return builder.toString();
  }

  private static List<String> allMatches(String text, String regex) {
    List<String> out = new ArrayList<>();
    Matcher matcher = Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(text);
    while (matcher.find()) out.add(matcher.group());
    return out;
  }

  private static String firstMatch(String text, String regex, int group) {
    Matcher matcher = Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(text);
    return matcher.find() ? matcher.group(group) : "";
  }

  private static int chapterPages(long chapterId) {
    String ulid = CHAPTER_ULID.getOrDefault(chapterId, "");
    if (ulid.isEmpty()) return -1;
    ensureAuthorized();
    Object parsed = getJson("/api/v1/books/" + encode(ulid));
    if (!(parsed instanceof Map)) return -1;
    Map<String, Object> media = castMap(parsed).get("media") instanceof Map ? castMap(castMap(parsed).get("media")) : new LinkedHashMap<>();
    return (int) num(media.get("pagesCount"), -1);
  }

  private static byte[] page(long mangaId, int chapterIndex, int pageIndex) {
    String ulid = chapterUlid(mangaId, chapterIndex);
    if (ulid.isEmpty()) throw new IllegalStateException("未知的章节序号：" + chapterIndex);
    ensureAuthorized();
    Reply reply = request("GET", "/api/v1/books/" + encode(ulid) + "/pages/" + (pageIndex + 1), "image/jpeg", null, true);
    if (reply.status == 404) return null;
    if (reply.status < 200 || reply.status >= 300) throw new IllegalStateException("上游页图读取失败：HTTP " + reply.status);
    String type = reply.contentType == null ? "" : reply.contentType.toLowerCase(Locale.ROOT);
    if (!type.startsWith("image/")) throw new IllegalStateException("上游返回的不是图片：" + reply.contentType);
    return reply.body;
  }

  private static byte[] thumbnail(long mangaId) {
    String ulid = SERIES_ULID.get(mangaId);
    if (ulid == null || ulid.isEmpty()) return null;
    ensureAuthorized();
    Reply reply = request("GET", "/api/v1/series/" + encode(ulid) + "/thumbnail", "image/jpeg", null, true);
    if (reply.status < 200 || reply.status >= 300) return null;
    return reply.body;
  }

  private static byte[] download(long mangaId, int chapterIndex) {
    String ulid = chapterUlid(mangaId, chapterIndex);
    if (ulid.isEmpty()) return null;
    ensureAuthorized();
    Reply reply = request("GET", "/api/v1/books/" + encode(ulid) + "/file", "application/octet-stream", null, true);
    if (reply.status == 404) return null;
    if (reply.status < 200 || reply.status >= 300) throw new IllegalStateException("上游整包下载失败：HTTP " + reply.status);
    return reply.body;
  }

  private static Object getJson(String path) {
    Reply reply = request("GET", path, "application/json", null, false);
    if (reply.status == 401) {
      refreshSession(true);
      reply = request("GET", path, "application/json", null, false);
    }
    if (reply.status < 200 || reply.status >= 300) {
      String detail = reply.body == null ? "" : new String(reply.body, StandardCharsets.UTF_8);
      if (detail.length() > 300) detail = detail.substring(0, 300);
      throw new IllegalStateException("上游返回 HTTP " + reply.status + (detail.isEmpty() ? "" : "：" + detail));
    }
    if (reply.body == null || reply.body.length == 0) return new LinkedHashMap<String, Object>();
    return parseJson(new String(reply.body, StandardCharsets.UTF_8));
  }

  private static Reply request(String method, String path, String accept, byte[] body, boolean raw) {
    HttpURLConnection connection = null;
    try {
      URL url = URI.create(baseUrl + path).toURL();
      connection = (HttpURLConnection) url.openConnection();
      connection.setRequestMethod(method);
      connection.setConnectTimeout(timeoutSeconds() * 1000);
      connection.setReadTimeout(timeoutSeconds() * 1000);
      connection.setInstanceFollowRedirects(false);
      connection.setRequestProperty("Accept", accept);
      connection.setRequestProperty("User-Agent", "LantaiReader-Plugin/" + VERSION);
      if (!apiKey.isEmpty()) connection.setRequestProperty("X-API-Key", apiKey);
      if (!basicAuth.isEmpty()) connection.setRequestProperty("Authorization", "Basic " + basicAuth);
      if (!cookie.isEmpty()) connection.setRequestProperty("Cookie", cookie);
      if (body != null && body.length > 0) {
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        try (OutputStream output = connection.getOutputStream()) {
          output.write(body);
        }
      }
      int status = connection.getResponseCode();
      String contentType = connection.getContentType();
      StringBuilder cookieHeaders = new StringBuilder();
      for (Map.Entry<String, List<String>> entry : connection.getHeaderFields().entrySet()) {
        if (entry.getKey() == null || !entry.getKey().equalsIgnoreCase("Set-Cookie")) continue;
        for (String value : entry.getValue()) {
          if (cookieHeaders.length() > 0) cookieHeaders.append('\n');
          cookieHeaders.append(value);
        }
      }
      InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
      int limit = raw ? (accept.startsWith("image/") ? MAX_IMAGE_BYTES : MAX_FILE_BYTES) : MAX_JSON_BYTES;
      byte[] payload = readAll(stream, limit);
      return new Reply(status, contentType, payload, cookieHeaders.toString());
    } catch (IOException error) {
      throw new IllegalStateException("无法连接 " + baseUrl + "：" + message(error), error);
    } finally {
      if (connection != null) connection.disconnect();
    }
  }

  private static byte[] readAll(InputStream stream, int limit) throws IOException {
    if (stream == null) return new byte[0];
    try (InputStream input = stream) {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      byte[] chunk = new byte[16 * 1024];
      int read;
      while ((read = input.read(chunk)) > 0) {
        buffer.write(chunk, 0, read);
        if (buffer.size() > limit) throw new IOException("响应超过上限 " + limit + " 字节");
      }
      return buffer.toByteArray();
    }
  }

  private static String readLine(InputStream input) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    int current;
    while ((current = input.read()) != -1) {
      if (current == '\n') break;
      if (current == '\r') continue;
      buffer.write(current);
    }
    if (current == -1 && buffer.size() == 0) return null;
    return buffer.toString(StandardCharsets.UTF_8);
  }

  private static byte[] readBody(InputStream input, Map<String, String> headers) throws IOException {
    String raw = headers.get("content-length");
    if (raw == null) return new byte[0];
    int length;
    try {
      length = Integer.parseInt(raw.trim());
    } catch (Exception error) {
      return new byte[0];
    }
    if (length <= 0) return new byte[0];
    if (length > 4 * 1024 * 1024) throw new IOException("请求体过大");
    byte[] data = new byte[length];
    int offset = 0;
    while (offset < length) {
      int read = input.read(data, offset, length - offset);
      if (read <= 0) break;
      offset += read;
    }
    return data;
  }

  private static void respond(Socket socket, int status, String json) throws IOException {
    respondBytes(socket, status, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
  }

  private static void respondBytes(Socket socket, int status, String contentType, byte[] body) throws IOException {
    String head =
        "HTTP/1.1 "
            + status
            + " "
            + reason(status)
            + "\r\nContent-Type: "
            + contentType
            + "\r\nContent-Length: "
            + body.length
            + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
    OutputStream output = socket.getOutputStream();
    output.write(head.getBytes(StandardCharsets.UTF_8));
    output.write(body);
    output.flush();
  }

  private static void respondHtml(Socket socket) throws IOException {
    if (!Files.isRegularFile(WEB_INDEX)) {
      respond(socket, 200, "{\"ok\":true,\"message\":\"控制台文件缺失\"}");
      return;
    }
    respondBytes(socket, 200, "text/html; charset=utf-8", Files.readAllBytes(WEB_INDEX));
  }

  private static String reason(int status) {
    if (status == 200) return "OK";
    if (status == 204) return "No Content";
    if (status == 400) return "Bad Request";
    if (status == 401) return "Unauthorized";
    if (status == 404) return "Not Found";
    if (status == 405) return "Method Not Allowed";
    if (status == 502) return "Bad Gateway";
    return "Internal Server Error";
  }

  private static Map<String, String> parseQuery(String query) {
    Map<String, String> out = new LinkedHashMap<>();
    if (query == null || query.isEmpty()) return out;
    for (String pair : query.split("&")) {
      if (pair.isEmpty()) continue;
      int eq = pair.indexOf('=');
      String key = eq < 0 ? pair : pair.substring(0, eq);
      String value = eq < 0 ? "" : pair.substring(eq + 1);
      out.put(decodePath(key), decodePath(value));
    }
    return out;
  }

  private static String decodePath(String value) {
    try {
      return URLDecoder.decode(value, StandardCharsets.UTF_8);
    } catch (Exception error) {
      return value;
    }
  }

  private static String encode(String value) {
    return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
  }

  private static int intParam(Map<String, String> params, String key, int fallback) {
    try {
      return Integer.parseInt(params.getOrDefault(key, String.valueOf(fallback)).trim());
    } catch (Exception error) {
      return fallback;
    }
  }

  private static long parseLong(String value) {
    try {
      return Long.parseLong(value.trim());
    } catch (Exception error) {
      return 0L;
    }
  }

  private static void log(String line) {
    String stamp = Instant.now().toString();
    String row = stamp + " " + line;
    LOG_BUFFER.addLast(row);
    while (LOG_BUFFER.size() > 400) LOG_BUFFER.pollFirst();
    System.out.println(row);
  }

  private static String message(Throwable error) {
    if (error == null) return "";
    String text = error.getMessage();
    return text == null || text.isBlank() ? error.getClass().getSimpleName() : text;
  }

  private static String env(String key, String fallback) {
    String value = System.getenv(key);
    return value == null || value.isBlank() ? fallback : value;
  }

  private static int intEnv(String key, int fallback) {
    try {
      return Integer.parseInt(env(key, String.valueOf(fallback)).trim());
    } catch (Exception error) {
      return fallback;
    }
  }

  private static String quote(String value) {
    if (value == null) return "null";
    StringBuilder out = new StringBuilder(value.length() + 16);
    out.append('"');
    for (int index = 0; index < value.length(); index++) {
      char ch = value.charAt(index);
      switch (ch) {
        case '"':
          out.append("\\\"");
          break;
        case '\\':
          out.append("\\\\");
          break;
        case '\n':
          out.append("\\n");
          break;
        case '\r':
          out.append("\\r");
          break;
        case '\t':
          out.append("\\t");
          break;
        case '\b':
          out.append("\\b");
          break;
        case '\f':
          out.append("\\f");
          break;
        default:
          if (ch < 0x20) {
            out.append(String.format("\\u%04x", (int) ch));
          } else {
            out.append(ch);
          }
      }
    }
    out.append('"');
    return out.toString();
  }

  private static String toJson(Object value) {
    StringBuilder out = new StringBuilder(256);
    writeJson(value, out);
    return out.toString();
  }

  private static void writeJson(Object value, StringBuilder out) {
    if (value == null) {
      out.append("null");
      return;
    }
    if (value instanceof String) {
      out.append(quote((String) value));
      return;
    }
    if (value instanceof Boolean || value instanceof Integer || value instanceof Long || value instanceof Short) {
      out.append(value);
      return;
    }
    if (value instanceof Float || value instanceof Double) {
      double number = ((Number) value).doubleValue();
      if (number == Math.rint(number) && !Double.isInfinite(number)) {
        out.append((long) number);
      } else {
        out.append(number);
      }
      return;
    }
    if (value instanceof Number) {
      out.append(value);
      return;
    }
    if (value instanceof Map) {
      out.append('{');
      boolean first = true;
      for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
        if (entry.getKey() == null) continue;
        if (!first) out.append(',');
        first = false;
        out.append(quote(String.valueOf(entry.getKey())));
        out.append(':');
        writeJson(entry.getValue(), out);
      }
      out.append('}');
      return;
    }
    if (value instanceof Iterable) {
      out.append('[');
      boolean first = true;
      for (Object item : (Iterable<?>) value) {
        if (!first) out.append(',');
        first = false;
        writeJson(item, out);
      }
      out.append(']');
      return;
    }
    if (value instanceof Object[]) {
      out.append('[');
      Object[] array = (Object[]) value;
      for (int index = 0; index < array.length; index++) {
        if (index > 0) out.append(',');
        writeJson(array[index], out);
      }
      out.append(']');
      return;
    }
    out.append(quote(String.valueOf(value)));
  }

  private static Object parseJson(String raw) {
    if (raw == null) return null;
    Parser parser = new Parser(raw);
    parser.skip();
    if (parser.end()) return null;
    Object value = parser.value();
    parser.skip();
    return value;
  }

  private static final class Parser {
    private final String text;
    private int pos;

    Parser(String text) {
      this.text = text;
    }

    boolean end() {
      return pos >= text.length();
    }

    void skip() {
      while (pos < text.length()) {
        char ch = text.charAt(pos);
        if (ch == ' ' || ch == '\n' || ch == '\r' || ch == '\t') {
          pos++;
        } else {
          break;
        }
      }
    }

    Object value() {
      skip();
      if (end()) return null;
      char ch = text.charAt(pos);
      if (ch == '{') return object();
      if (ch == '[') return array();
      if (ch == '"') return string();
      if (ch == 't' || ch == 'f') return bool();
      if (ch == 'n') {
        pos += 4;
        return null;
      }
      return number();
    }

    Map<String, Object> object() {
      Map<String, Object> out = new LinkedHashMap<>();
      pos++;
      while (true) {
        skip();
        if (end()) break;
        if (text.charAt(pos) == '}') {
          pos++;
          break;
        }
        if (text.charAt(pos) == ',') {
          pos++;
          continue;
        }
        String key = string();
        skip();
        if (!end() && text.charAt(pos) == ':') pos++;
        Object value = value();
        out.put(key, value);
      }
      return out;
    }

    List<Object> array() {
      List<Object> out = new ArrayList<>();
      pos++;
      while (true) {
        skip();
        if (end()) break;
        if (text.charAt(pos) == ']') {
          pos++;
          break;
        }
        if (text.charAt(pos) == ',') {
          pos++;
          continue;
        }
        out.add(value());
      }
      return out;
    }

    String string() {
      StringBuilder out = new StringBuilder();
      pos++;
      while (pos < text.length()) {
        char ch = text.charAt(pos++);
        if (ch == '"') break;
        if (ch != '\\') {
          out.append(ch);
          continue;
        }
        if (pos >= text.length()) break;
        char escaped = text.charAt(pos++);
        switch (escaped) {
          case 'n':
            out.append('\n');
            break;
          case 'r':
            out.append('\r');
            break;
          case 't':
            out.append('\t');
            break;
          case 'b':
            out.append('\b');
            break;
          case 'f':
            out.append('\f');
            break;
          case 'u':
            if (pos + 4 <= text.length()) {
              try {
                out.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
              } catch (Exception ignored) {
              }
              pos += 4;
            }
            break;
          default:
            out.append(escaped);
        }
      }
      return out.toString();
    }

    Boolean bool() {
      if (text.startsWith("true", pos)) {
        pos += 4;
        return Boolean.TRUE;
      }
      pos += 5;
      return Boolean.FALSE;
    }

    Object number() {
      int start = pos;
      while (pos < text.length()) {
        char ch = text.charAt(pos);
        if (ch == '-' || ch == '+' || ch == '.' || ch == 'e' || ch == 'E' || (ch >= '0' && ch <= '9')) {
          pos++;
        } else {
          break;
        }
      }
      String slice = text.substring(start, pos);
      if (slice.isEmpty()) {
        pos++;
        return null;
      }
      try {
        if (slice.indexOf('.') < 0 && slice.indexOf('e') < 0 && slice.indexOf('E') < 0) {
          long parsed = Long.parseLong(slice);
          if (parsed >= Integer.MIN_VALUE && parsed <= Integer.MAX_VALUE) return (int) parsed;
          return parsed;
        }
        return Double.parseDouble(slice);
      } catch (Exception error) {
        return slice;
      }
    }
  }

  private static Map<String, Object> castMap(Object value) {
    Map<String, Object> out = new LinkedHashMap<>();
    if (!(value instanceof Map)) return out;
    for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
      out.put(String.valueOf(entry.getKey()), entry.getValue());
    }
    return out;
  }

  private static String str(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private static double num(Object value, double fallback) {
    if (value instanceof Number) return ((Number) value).doubleValue();
    if (value == null) return fallback;
    try {
      return Double.parseDouble(String.valueOf(value).trim());
    } catch (Exception error) {
      return fallback;
    }
  }

  private static final class Reply {
    final int status;
    final String contentType;
    final byte[] body;
    final String setCookie;

    Reply(int status, String contentType, byte[] body, String setCookie) {
      this.status = status;
      this.contentType = contentType;
      this.body = body;
      this.setCookie = setCookie;
    }
  }
}
