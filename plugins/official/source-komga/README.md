# Komga 书库源

兰台官方插件。把一个自部署的 Komga 实例（或任何 Komga 兼容服务端）接成兰台的在线源：在插件面板里填写地址与凭据后，该实例的书库、系列、章节与页图即可以插件源的形式出现在兰台。

插件本身不含任何内容来源，也不预置任何地址、索引或规则；连接目标由使用者自行填写并自行负责。

## 能力

| 能力 | 说明 |
|---|---|
| `source.provide` | 将上游实例的系列作为插件源提供给兰台 |
| `ui.panel` | 登录与连接控制台 |

## 目录

```
plugin.json        清单，唯一版本来源
src/PluginMain.java  插件入口，单文件、无第三方依赖
web/index.html     插件面板（连接配置、登录、状态）
构建.py            编译与打包，产物落 产物/
```

## 构建

需要 JDK 17 及以上，`javac` 与 `jar` 在 `PATH` 中（或设置 `JAVA_HOME`）：

```bash
python 构建.py
```

产物：

```
产物/plugin-lantai-source-komga-<version>.zip
```

包内结构为 `plugin.json` + `bin/plugin.jar` + `web/index.html`，字节码目标 Java 17，可直接在宿主镜像的 JRE 21 上运行。

## 安装

在兰台「插件 → 安装插件」上传上一步生成的 zip，启用后在「Komga 连接」面板填写：

| 字段 | 说明 |
|---|---|
| Komga 地址 | 上游实例地址，如 `http://192.168.1.10:25600` |
| API Key | 上游生成的 API Key，与邮箱密码二选一 |
| 邮箱 / 密码 | 上游账号，用于换取会话 |

API Key 留空保存时沿用已存凭据；要改密码登录则同时填写邮箱与密码。

## 版本

当前 `0.1.8`。版本号只写在 `plugin.json` 与 `src/PluginMain.java` 的 `VERSION` 常量，两处需同步。
