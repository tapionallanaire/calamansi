# SPDX-License-Identifier: GPL-2.0-or-later
"""Stage the pinned frontend/core without modifying its gitlink checkout."""
from pathlib import Path
import hashlib
import re
import shutil
import os
import subprocess
import sys
import tarfile
import xml.etree.ElementTree as ET

PIN = "8d2ad1a662472955497e5bb79d7b9ec6999e1f0d"
PATCHES = {
    "embed.patch": "a0e071456db65a38554d8496b3d87fcba59fe97eb7a1e03ca00b20bc3c7165e5",
    "lifecycle.patch": "a6f6cdafd1781ebf5989729968c6abffa2fe4939352b52f8396defd04686d5b7",
    "cancel.patch": "7e655a229e84bd4cebe43086f341928d5e4d5fe44f5f3262eefe31712a5f5eac",
    "isolation.patch": "59912130dd576290da3419353caa1fbd3f7b6ee9db35c59499acf6e80f74d28b",
    "prune.patch": "8f0d0b03e2adee7c73510ad75a51793ef4eafe6de7d342949b2172b7c1e086fb",
    "restore.patch": "86eb60d411eef049d78e6b791273c7228c95e834628cd4444dd59e47836a6b85",
    "library-names.patch": "a538e2d89e3feabc4ac6e50c6b48b6f4a4ca780dca49f304ef15fcb8f1e23c62",
    "ownership.patch": "2dbc2cc51d871a94296cd02e3dbd7be494c64f891eddd67963789b5314cb9c4b",
    "controller-shutdown.patch": "0fcebf25030e7945b861c259aa84a32707699ce27e2d47c7997d9d13b30ce01b",
    "settings-ui.patch": "25e12035d8acfe56a76647aa1ebdff727123ddbd9936963537a9bef565dccece",
    "picture-filter.patch": "d264a6f63ceb9248ce8ca450e0ffaf6776c86f12343dc8748a11050d7fa6fb5f",
}
PREFIX = "dolphin_"


def resource_maps(res):
    names = {}
    for file in res.rglob("*"):
        if not file.is_file():
            continue
        kind = file.parent.name.split("-")[0]
        if kind == "values":
            for element in ET.parse(file).getroot():
                name = element.get("name")
                if name:
                    typ = {"string-array": "array", "integer-array": "array", "declare-styleable": "styleable", "item": element.get("type")}.get(element.tag, element.tag)
                    names[(typ, name)] = PREFIX + name
                    if element.tag == "declare-styleable":
                        for attr in element:
                            if attr.get("name") and ":" not in attr.get("name"):
                                names[("attr", attr.get("name"))] = PREFIX + attr.get("name")
        else:
            names[(kind, file.name.split(".")[0])] = PREFIX + file.name.split(".")[0]
    return names


def rewrite_resources(text, names):
    # IDs stay layout-local, keeping actual upstream view-binding field names.
    for (kind, old), new in sorted(names.items(), key=lambda x: -len(x[0][1])):
        text = re.sub(r"([@?](?:\+)?" + re.escape(str(kind)) + r"/)" + re.escape(old) + r"(?=[\s\"'<})/]|$)", lambda m: m[1] + new, text)
        text = re.sub(r"(?<!android\.)\bR\." + re.escape(str(kind)) + r"\." + re.escape(old.replace(".", "_")) + r"\b", "R." + str(kind) + "." + new.replace(".", "_"), text)
        if kind == "style":
            text = text.replace('parent="' + old + '"', 'parent="' + new + '"')
        if kind == "attr":
            text = text.replace('app:' + old + '=', 'app:' + new + '=')
            text = text.replace('<item name="' + old + '"', '<item name="' + new + '"')
    return text


def rewrite_value_definitions(text, names):
    # Make implicit style inheritance explicit after adding the engine prefix.
    def definition(match):
        tag, attrs, old = match.group(1), match.group(2), match.group(3)
        kind = {"string-array": "array", "integer-array": "array", "declare-styleable": "styleable"}.get(tag, tag)
        if tag == "item":
            typ = re.search(r'type="([^"]+)"', attrs)
            if not typ:
                return match[0]
            kind = typ[1]
        new = names.get((kind, old))
        if new is None:
            return match[0]
        result = match[0].replace('name="' + old + '"', 'name="' + new + '"')
        if tag == "style" and "parent=" not in attrs and "." in old:
            parent = old.rsplit('.', 1)[0]
            if ("style", parent) in names:
                result += ' parent="' + names[("style", parent)] + '"'
        return result
    return re.sub(r'<([\w-]+)([^>/]*?\bname="([^"]+)"[^>/]*)(?=/?>)', definition, text)


def rewrite_visible_wording(text):
    """Rename display prose without changing resource IDs, links, or error details."""
    def string(match):
        opening, name, body, closing = match.groups()
        if name == "about_dolphin":
            return opening + "GameCube/Wii" + closing
        if name == "about_dolphin_image":
            return opening + "Engine logo" + closing
        # About resources are upstream attribution; format help identifies actual
        # upstream versions. Keep diagnostic details and hardware product names.
        if name.startswith("about_") or name == "convert_format_info":
            return match.group(0)
        parts = re.split(r"(<[^>]*>)", body)
        for index in range(0, len(parts), 2):
            parts[index] = re.sub(r"\bDolphin Emulator\b", "GameCube/Wii Emulation", parts[index])
            parts[index] = re.sub(r"\bDolphin\b", "GameCube/Wii emulation", parts[index])
        return opening + "".join(parts) + closing

    return re.sub(
        r'(<string\b[^>]*\bname="([^"]+)"[^>]*>)(.*?)(</string>)',
        string, text, flags=re.S,
    )


def filesystem_path(path):
    """Use extended paths for Python filesystem operations on Windows."""
    path = str(path.resolve())
    if os.name == "nt" and not path.startswith("\\\\?\\"):
        path = "\\\\?\\UNC\\" + path[2:] if path.startswith("\\\\") else "\\\\?\\" + path
    return Path(path)


def export_pinned_tree(checkout, git_dir, revision, output, windows_links):
    """Export Git objects, recursively selecting gitlink commits rather than working trees."""
    output.mkdir(parents=True, exist_ok=True)
    command = ["git", "--git-dir", str(git_dir)]
    with subprocess.Popen(command + ["archive", "--format=tar", revision],
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE) as process:
        with process.stdout:
            with tarfile.open(fileobj=process.stdout, mode="r|") as archive:
                # Only the fixed revision and its pinned gitlink objects supply these archives.
                for member in archive:
                    if os.name == "nt" and member.issym():
                        windows_links.append((output / member.name, member.linkname))
                    else:
                        archive.extract(member, output, filter="fully_trusted")
            # TAR ends before Git finishes writing record padding; do not close its pipe early.
            while process.stdout.read(65536):
                pass
        error = process.stderr.read().decode("utf-8", errors="replace")
        if process.wait() != 0:
            raise RuntimeError(f"Cannot export pinned Dolphin dependency {revision}: {error}")
    entries = subprocess.check_output(command + ["ls-tree", "-r", "-z", revision]).split(b"\0")
    links = []
    for entry in entries:
        if not entry:
            continue
        metadata, path = entry.split(b"\t", 1)
        mode, kind, commit = metadata.split()
        if kind == b"commit":
            links.append((path.decode("utf-8"), commit.decode("ascii")))
    if not links:
        return
    settings = subprocess.check_output(command + [
        "config", "--null", "--blob", revision + ":.gitmodules", "--get-regexp", r"^submodule\..*\.path$"
    ]).decode("utf-8").split("\0")
    module_names = {}
    for setting in settings:
        if setting:
            key, path = setting.split("\n", 1)
            module_names[path] = key[len("submodule."):-len(".path")]
    for path, commit in links:
        child_checkout = checkout / path
        child_git = git_dir / "modules" / module_names[path]
        if not filesystem_path(child_git).is_dir():
            child_git = Path(subprocess.check_output([
                "git", "-C", str(child_checkout), "rev-parse", "--absolute-git-dir"
            ], text=True).strip())
        export_pinned_tree(child_checkout, child_git, commit, output / path, windows_links)


def prepare(source, out):
    module = Path(__file__).resolve().parent
    pin = subprocess.check_output(["git", "-C", str(source), "rev-parse", "HEAD"], text=True).strip()
    if pin != PIN:
        raise RuntimeError("Dolphin revision mismatch: " + pin)
    canonical_patches = []
    for name, checksum in PATCHES.items():
        content = (module / "patches" / name).read_text(encoding="utf-8").encode("utf-8")
        if hashlib.sha256(content).hexdigest() != checksum:
            raise RuntimeError("Dolphin patch checksum mismatch: " + name)
        canonical_patches.append(content)
    git_dir = Path(subprocess.check_output([
        "git", "-C", str(source), "rev-parse", "--absolute-git-dir"
    ], text=True).strip())
    native_directory = out / "native"
    out = filesystem_path(out)
    if out.exists():
        shutil.rmtree(out)
    native = out / "native"
    windows_links = []
    export_pinned_tree(source, git_dir, PIN, native, windows_links)
    # Windows build hosts need not have symlink privileges. Materialize pinned targets only
    # after all gitlink trees exist, including chained links and directory-link contents.
    while windows_links:
        remaining = []
        for destination, relative in windows_links:
            target = filesystem_path(destination.parent / relative)
            if target.is_dir() and not any(
                other != destination and other.is_relative_to(target) for other, _ in windows_links
            ):
                shutil.copytree(target, destination)
            elif target.is_file():
                shutil.copy2(target, destination)
            else:
                remaining.append((destination, relative))
        if len(remaining) == len(windows_links):
            raise RuntimeError("Unresolved pinned Dolphin symlinks: " + repr(remaining))
        windows_links = remaining
    environment = dict(os.environ, GIT_CEILING_DIRECTORIES=str(native_directory.parent))
    for patch in canonical_patches:
        subprocess.run(["git", "apply", "--check", "--unsafe-paths", "-"], input=patch,
                       cwd=native_directory, env=environment, check=True)
        subprocess.run(["git", "apply", "--unsafe-paths", "-"], input=patch,
                       cwd=native_directory, env=environment, check=True)
    frontend = native / "Source/Android/app/src/main"
    names = resource_maps(frontend / "res")
    bindings = {''.join(part.capitalize() for part in name.split('_')) + 'Binding': 'Dolphin' + ''.join(part.capitalize() for part in name.split('_')) + 'Binding' for kind, name in names if kind == 'layout'}
    excluded = {"DolphinApplication.kt", "MainActivity.kt", "TvMainActivity.kt", "MainPresenter.kt", "AppLinkActivity.kt", "GamePropertiesDialog.kt", "GameAdapter.kt", "GameRowPresenter.kt", "CoilUtils.kt", "PlatformPagerAdapter.kt", "PlatformGamesFragment.kt", "UserDataActivity.kt", "UserDataImportWarningDialog.kt", "DocumentProvider.kt"}
    for file in (frontend / "java").rglob("*"):
        if not file.is_file() or file.name in excluded or "features/netplay/ui" in file.as_posix() or "ui/theme" in file.as_posix():
            continue
        text = file.read_text(encoding="utf-8")
        text = text.replace("org.dolphinemu.dolphinemu.R", "org.calamansi.dolphin.R").replace("org.dolphinemu.dolphinemu.BuildConfig", "org.calamansi.dolphin.BuildConfig").replace("org.dolphinemu.dolphinemu.databinding", "org.calamansi.dolphin.databinding")
        if re.search(r"\bR\.", text) and "import org.calamansi.dolphin.R" not in text:
            text = re.sub(r"(^package [^\n]+\n)", r"\1\nimport org.calamansi.dolphin.R\n", text, count=1, flags=re.M)
        text = text.replace("PreferenceManager.getDefaultSharedPreferences(", "org.calamansi.dolphin.DolphinRuntime.preferences(")
        text = rewrite_resources(text, names)
        for old, new in bindings.items():
            text = re.sub(r"\b" + old + r"\b", new, text)
        dest = out / "java" / file.relative_to(frontend / "java")
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_text(text, encoding="utf-8")
    for file in (frontend / "res").rglob("*"):
        if not file.is_file():
            continue
        dest = out / "res" / file.parent.name / (PREFIX + file.name)
        dest.parent.mkdir(parents=True, exist_ok=True)
        if file.suffix != ".xml":
            shutil.copy2(file, dest)
            continue
        text = file.read_text(encoding="utf-8")
        if file.parent.name.startswith("values"):
            text = rewrite_visible_wording(text)
            text = rewrite_value_definitions(text, names)
        text = rewrite_resources(text, names)
        dest.write_text(text, encoding="utf-8")
    assets = out / "assets/dolphin"
    assets.mkdir(parents=True)
    shutil.copytree(native / "Data/Sys", assets / "Sys", ignore=shutil.ignore_patterns("Themes"))
    for file in native.rglob("*"):
        if file.is_file() and re.match(r"(?i)^(licen[sc]e|copying|notice|authors)(\..*)?$", file.name) and ".git" not in file.parts:
            dest = assets / "licenses" / file.relative_to(native)
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(file, dest)


if __name__ == "__main__":
    prepare(Path(sys.argv[1]).resolve(), Path(sys.argv[2]).resolve())
