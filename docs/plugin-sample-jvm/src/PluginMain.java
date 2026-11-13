import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PluginMain {
  private static final String VERSION = "1.1.0";
  private static final String PLUGIN_ID = env("LANTAI_PLUGIN_ID", "sample.notify-digest");
  private static final int PORT = Integer.parseInt(env("LANTAI_PLUGIN_PORT", "26000"));
  private static final String HOST_KEY = env("LANTAI_PLUGIN_HOST_KEY", "");
  private static final String TOKEN = env("LANTAI_PLUGIN_TOKEN", "");
  private static final String BASE = env("LANTAI_PLUGIN_BASE", "http://127.0.0.1:25600").replaceAll("/+$", "");
  private static final Path DATA_DIR = Paths.get(env("LANTAI_PLUGIN_DATA", "data"));
  private static final long STARTED = System.currentTimeMillis();

  public static void main(String[] args) throws Exception {
    Files.createDirectories(DATA_DIR);
    if (!TOKEN.isEmpty()) {
      Thread watcher = new Thread(PluginMain::watchEvents, "lantai-events");
      watcher.setDaemon(true);
      watcher.start();
    }
    try (ServerSocket server = new ServerSocket(PORT, 64, InetAddress.getByName("127.0.0.1"))) {
      appendLog("plugin.log", "{\"event\":\"listening\",\"port\":" + PORT + ",\"plugin\":" + quote(PLUGIN_ID) + "}");
      ExecutorService pool = Executors.newFixedThreadPool(8);
      while (true) {
        Socket socket = server.accept();
        pool.submit(
            () -> {
              try {
                handle(socket);
              } catch (Throwable ignored) {
              } finally {
                try {
                  socket.close();
                } catch (IOException ignored) {
                }
              }
            });
      }
    }
  }

  private static void handle(Socket socket) throws IOException {
    socket.setSoTimeout(15000);
    InputStream input = socket.getInputStream();
    String requestLine = readLine(input);
    if (requestLine == null || requestLine.isEmpty()) return;
    String[] parts = requestLine.split(" ");
    if (parts.length < 2) {
      respond(socket, 400, "{\"message\":" + quote("请求行不合法") + "}");
      return;
    }
    String method = parts[0].toUpperCase(Locale.ROOT);
    String path = parts[1];
    int query = path.indexOf('?');
    if (query >= 0) path = path.substring(0, query);

    Map<String, String> headers = new HashMap<>();
    while (true) {
      String line = readLine(input);
      if (line == null || line.isEmpty()) break;
      int colon = line.indexOf(':');
      if (colon > 0) {
        headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
      }
    }

    if (!HOST_KEY.isEmpty() && !HOST_KEY.equals(headers.get("x-lantai-host-key"))) {
      respond(socket, 401, "{\"message\":" + quote("hostKey 校验失败") + "}");
      return;
    }

    String payload = readBody(input, headers);
    if (payload == null) {
      respond(socket, 400, "{\"message\":" + quote("请求体不是合法 JSON") + "}");
      return;
    }

    if ("GET".equals(method) && "/v1/manifest".equals(path)) {
      respond(socket, 200, manifest());
      return;
    }
    if ("GET".equals(method) && "/v1/health".equals(path)) {
      respond(socket, 200, "{\"status\":\"ok\",\"uptime\":" + uptimeSeconds() + "}");
      return;
    }
    if ("POST".equals(method) && "/v1/capabilities/notify.send".equals(path)) {
      notify(socket, parseFlat(payload));
      return;
    }
    if ("POST".equals(method) && "/v1/tasks/digest".equals(path)) {
      digest(socket);
      return;
    }
    respond(socket, 404, "{\"message\":" + quote("未知路径") + "}");
  }

  private static void notify(Socket socket, Map<String, String> payload) throws IOException {
    String title = text(payload.get("title"));
    String body = text(payload.get("body"));
    String level = text(payload.get("level"));
    if (level.isEmpty()) level = "info";
    if (title.isEmpty() || body.isEmpty()) {
      respond(socket, 400, "{\"message\":" + quote("title 与 body 必填") + "}");
      return;
    }
    appendLog(
        "notify.log",
        "{\"title\":"
            + quote(title)
            + ",\"body\":"
            + quote(body)
            + ",\"level\":"
            + quote(level)
            + ",\"at\":"
            + (System.currentTimeMillis() / 1000)
            + "}");
    long count = bumpCounter("notify.count");
    respond(socket, 200, "{\"accepted\":true,\"count\":" + count + "}");
  }

  private static void digest(Socket socket) throws IOException {
    long notifyCount = readCounter("notify.count");
    long bookCount = readCounter("book.count");
    appendLog(
        "digest.log",
        "{\"notify\":"
            + notifyCount
            + ",\"book\":"
            + bookCount
            + ",\"at\":"
            + (System.currentTimeMillis() / 1000)
            + "}");
    respond(socket, 200, "{\"notify\":" + notifyCount + ",\"book\":" + bookCount + "}");
  }

  private static void watchEvents() {
    String lastId = null;
    while (true) {
      HttpURLConnection conn = null;
      try {
        conn = (HttpURLConnection) URI.create(BASE + "/api/lantai/v1/plugin/events").toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(0);
        conn.setRequestProperty("X-API-Token", TOKEN);
        conn.setRequestProperty("Accept", "text/event-stream");
        if (lastId != null && !lastId.isEmpty()) conn.setRequestProperty("Last-Event-ID", lastId);
        int code = conn.getResponseCode();
        if (code >= 400) throw new IOException("HTTP " + code);
        try (InputStream input = conn.getInputStream()) {
          String event = null;
          String data = null;
          String line;
          while ((line = readLine(input)) != null) {
            if (line.startsWith("id:")) lastId = line.substring(3).trim();
            else if (line.startsWith("event:")) event = line.substring(6).trim();
            else if (line.startsWith("data:")) data = line.substring(5).trim();
            else if (line.isEmpty()) {
              if (data != null) handleEvent(event, data);
              event = null;
              data = null;
            }
          }
        }
      } catch (Exception error) {
        appendLog("error.log", "{\"stream\":\"events\",\"reason\":" + quote(String.valueOf(error.getMessage())) + "}");
      } finally {
        if (conn != null) conn.disconnect();
      }
      try {
        Thread.sleep(5000L);
      } catch (InterruptedException interrupted) {
        return;
      }
    }
  }

  private static void handleEvent(String event, String data) {
    if ("reset".equals(event)) {
      appendLog("event.log", "{\"reset\":" + quote(data) + "}");
      return;
    }
    if (!"domain".equals(event)) return;
    Map<String, String> payload = parseFlat(data);
    String type = text(payload.get("type"));
    appendLog("event.log", "{\"type\":" + quote(type) + ",\"id\":" + quote(text(payload.get("id"))) + "}");
    if ("book.added".equals(type)) bumpCounter("book.count");
  }

  private static long readCounter(String key) {
    String raw = hostCall("GET", "/api/lantai/v1/plugin/kv/" + key, null);
    if (raw == null) return 0;
    String value = text(parseFlat(raw).get("value"));
    try {
      return value.isEmpty() ? 0 : Long.parseLong(value);
    } catch (NumberFormatException error) {
      return 0;
    }
  }

  private static long bumpCounter(String key) {
    long next = readCounter(key) + 1;
    hostCall("PUT", "/api/lantai/v1/plugin/kv/" + key, "{\"value\":\"" + next + "\"}");
    return next;
  }

  private static String hostCall(String method, String path, String payload) {
    if (TOKEN.isEmpty()) return null;
    HttpURLConnection conn = null;
    try {
      conn = (HttpURLConnection) URI.create(BASE + path).toURL().openConnection();
      conn.setRequestMethod(method);
      conn.setConnectTimeout(5000);
      conn.setReadTimeout(10000);
      conn.setRequestProperty("X-API-Token", TOKEN);
      conn.setRequestProperty("Accept", "application/json");
      if (payload != null) {
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        try (OutputStream output = conn.getOutputStream()) {
          output.write(payload.getBytes(StandardCharsets.UTF_8));
        }
      }
      int code = conn.getResponseCode();
      InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
      if (stream == null) return null;
      String raw;
      try (InputStream input = stream) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = input.read(chunk)) != -1) buffer.write(chunk, 0, read);
        raw = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
      }
      if (code >= 400) {
        appendLog("error.log", "{\"path\":" + quote(path) + ",\"status\":" + code + "}");
        return null;
      }
      return raw.isEmpty() ? null : raw;
    } catch (IOException error) {
      appendLog("error.log", "{\"path\":" + quote(path) + ",\"reason\":" + quote(String.valueOf(error.getMessage())) + "}");
      return null;
    } finally {
      if (conn != null) conn.disconnect();
    }
  }

  private static String readBody(InputStream input, Map<String, String> headers) throws IOException {
    int length;
    try {
      length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
    } catch (NumberFormatException error) {
      length = 0;
    }
    if (length <= 0) return "{}";
    byte[] buffer = new byte[length];
    int read = 0;
    while (read < length) {
      int value = input.read(buffer, read, length - read);
      if (value < 0) break;
      read += value;
    }
    String raw = new String(buffer, 0, read, StandardCharsets.UTF_8).trim();
    if (raw.isEmpty()) return "{}";
    if (!raw.startsWith("{")) return null;
    return raw;
  }

  private static String readLine(InputStream input) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    int value;
    boolean any = false;
    while ((value = input.read()) != -1) {
      any = true;
      if (value == '\n') break;
      if (value == '\r') continue;
      buffer.write(value);
    }
    if (!any && buffer.size() == 0) return null;
    return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
  }

  private static Map<String, String> parseFlat(String json) {
    Map<String, String> result = new LinkedHashMap<>();
    if (json == null) return result;
    int index = 0;
    int length = json.length();
    while (index < length) {
      while (index < length && json.charAt(index) != '"') index++;
      if (index >= length) break;
      int[] cursor = new int[1];
      String key = readString(json, index + 1, cursor);
      index = cursor[0];
      while (index < length && (json.charAt(index) == ':' || Character.isWhitespace(json.charAt(index)))) index++;
      if (index < length && json.charAt(index) == '"') {
        String value = readString(json, index + 1, cursor);
        index = cursor[0];
        result.put(key, value);
      } else {
        int start = index;
        while (index < length && json.charAt(index) != ',' && json.charAt(index) != '}') index++;
        result.put(key, json.substring(start, index).trim());
      }
    }
    return result;
  }

  private static String readString(String json, int start, int[] cursor) {
    StringBuilder builder = new StringBuilder();
    int index = start;
    while (index < json.length()) {
      char current = json.charAt(index);
      if (current == '"') {
        cursor[0] = index + 1;
        return builder.toString();
      }
      if (current == '\\' && index + 1 < json.length()) {
        char next = json.charAt(index + 1);
        switch (next) {
          case 'n':
            builder.append('\n');
            break;
          case 'r':
            builder.append('\r');
            break;
          case 't':
            builder.append('\t');
            break;
          case 'b':
            builder.append('\b');
            break;
          case 'f':
            builder.append('\f');
            break;
          case 'u':
            if (index + 5 < json.length()) {
              try {
                builder.append((char) Integer.parseInt(json.substring(index + 2, index + 6), 16));
              } catch (NumberFormatException ignored) {
              }
              index += 6;
              continue;
            }
            break;
          default:
            builder.append(next);
        }
        index += 2;
        continue;
      }
      builder.append(current);
      index++;
    }
    cursor[0] = index;
    return builder.toString();
  }

  private static String manifest() {
    StringBuilder builder = new StringBuilder();
    builder.append("{\"id\":").append(quote(PLUGIN_ID));
    builder.append(",\"name\":").append(quote("通知与摘要示例"));
    builder.append(",\"version\":").append(quote(VERSION));
    builder.append(",\"protocol\":2");
    builder.append(",\"minHost\":\"0.6.23\"");
    builder.append(",\"runtime\":\"jvm\"");
    builder.append(",\"entry\":\"bin/plugin.jar\"");
    builder.append(",\"description\":").append(quote("把宿主发来的通知写入本地日志，累加计数，并按 cron 每日汇总一次。只依赖 JRE，不需要额外的解释器。"));
    builder.append(",\"capabilities\":[");
    builder.append("{\"name\":\"notify.send\",\"note\":\"把通知写入本地日志并累加计数\"},");
    builder.append("{\"name\":\"storage.blob\",\"note\":\"保存通知与新增书籍计数\"},");
    builder.append("{\"name\":\"task.schedule\",\"note\":\"每日汇总一次\"},");
    builder.append("{\"name\":\"event.observe\",\"note\":\"统计新增书籍\"}]");
    builder.append(",\"scopes\":[\"notify.send\",\"storage.blob\",\"task.schedule\",\"event.observe\"]");
    builder.append(",\"tasks\":[{\"name\":\"dailyDigest\",\"cron\":\"0 9 * * *\",\"endpoint\":\"/v1/tasks/digest\",\"timeoutSeconds\":120}]");
    builder.append(",\"config\":{\"type\":\"object\",\"properties\":{");
    builder.append("\"digestHour\":{\"type\":\"integer\",\"title\":\"汇总小时\",\"description\":\"仅作记录，真实调度以 cron 为准\",\"minimum\":0,\"maximum\":23},");
    builder.append("\"channel\":{\"type\":\"string\",\"title\":\"通知渠道\",\"enum\":[\"log\",\"kv\"]},");
    builder.append("\"loud\":{\"type\":\"boolean\",\"title\":\"重要通知\"}");
    builder.append("}}");
    builder.append("}");
    return builder.toString();
  }

  private static void respond(Socket socket, int status, String json) throws IOException {
    byte[] body = json.getBytes(StandardCharsets.UTF_8);
    String head =
        "HTTP/1.1 "
            + status
            + " "
            + reason(status)
            + "\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: "
            + body.length
            + "\r\nConnection: close\r\n\r\n";
    OutputStream output = socket.getOutputStream();
    output.write(head.getBytes(StandardCharsets.US_ASCII));
    output.write(body);
    output.flush();
  }

  private static String reason(int status) {
    switch (status) {
      case 200:
        return "OK";
      case 400:
        return "Bad Request";
      case 401:
        return "Unauthorized";
      case 404:
        return "Not Found";
      default:
        return "Error";
    }
  }

  private static void appendLog(String name, String line) {
    try {
      Files.createDirectories(DATA_DIR);
      Files.write(
          DATA_DIR.resolve(name),
          (line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND,
          StandardOpenOption.WRITE);
    } catch (IOException ignored) {
    }
  }

  private static String quote(String value) {
    if (value == null) return "null";
    StringBuilder builder = new StringBuilder(value.length() + 2);
    builder.append('"');
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      switch (current) {
        case '"':
          builder.append("\\\"");
          break;
        case '\\':
          builder.append("\\\\");
          break;
        case '\n':
          builder.append("\\n");
          break;
        case '\r':
          builder.append("\\r");
          break;
        case '\t':
          builder.append("\\t");
          break;
        default:
          if (current < 0x20) builder.append(String.format("\\u%04x", (int) current));
          else builder.append(current);
      }
    }
    builder.append('"');
    return builder.toString();
  }

  private static String text(String value) {
    return value == null ? "" : value.trim();
  }

  private static long uptimeSeconds() {
    return (System.currentTimeMillis() - STARTED) / 1000L;
  }

  private static String env(String key, String fallback) {
    String value = System.getenv(key);
    return value == null || value.isEmpty() ? fallback : value;
  }
}
