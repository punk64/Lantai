import json
import os
import shutil
import subprocess
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
STAGE = ROOT / ".tmp" / "komga"
OUT_DIR = ROOT / "产物"
RELEASE = "17"


def find_tool(name):
    found = shutil.which(name)
    if found:
        return found
    env_home = os.environ.get("JAVA_HOME", "").strip()
    if env_home:
        for candidate in (Path(env_home) / "bin" / (name + ".exe"), Path(env_home) / "bin" / name):
            if candidate.is_file():
                return str(candidate)
    raise SystemExit("找不到 %s，请确认 JDK 已安装并在 PATH 中" % name)


def main():
    manifest = json.loads((ROOT / "plugin.json").read_text(encoding="utf-8"))
    version = manifest["version"]
    plugin_id = manifest["id"]

    javac = find_tool("javac")
    jartool = find_tool("jar")

    if STAGE.exists():
        shutil.rmtree(STAGE)
    classes = STAGE / "classes"
    package = STAGE / "package"
    (package / "bin").mkdir(parents=True)
    (package / "web").mkdir(parents=True)

    subprocess.run(
        [javac, "--release", RELEASE, "-encoding", "UTF-8", "-d", str(classes), str(ROOT / "src" / "PluginMain.java")],
        check=True,
    )
    subprocess.run(
        [jartool, "--create", "--file", str(package / "bin" / "plugin.jar"), "--main-class", "PluginMain", "-C", str(classes), "."],
        check=True,
    )
    shutil.copy2(ROOT / "plugin.json", package / "plugin.json")
    shutil.copy2(ROOT / "web" / "index.html", package / "web" / "index.html")

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    target = OUT_DIR / ("plugin-%s-%s.zip" % (plugin_id.replace(".", "-"), version))
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED, allowZip64=True) as archive:
        for path in sorted(package.rglob("*")):
            if path.is_file():
                archive.write(path, path.relative_to(package).as_posix())
    print("已生成 %s（%d 字节）" % (target, target.stat().st_size))


if __name__ == "__main__":
    main()
