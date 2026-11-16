#!/usr/bin/env python3
"""Bounded, read-only project clues; candidate commands are never executed.

Python 3.8+, standard library only. Descriptor-relative traversal intentionally
fails closed on systems without the required no-follow filesystem primitives.
"""

import json
import os
import shutil
import stat
import sys


SKIP_DIRS = frozenset({
    ".git", ".hg", ".svn", "node_modules", "build", "dist", "cache", ".cache",
    "__pycache__", ".venv", "venv", "vendor", "target", ".next", ".gradle",
    ".hvigor", "oh_modules", "coverage",
})
SCRIPTS = ("dev", "start", "build", "test", "lint", "typecheck", "check")
DEPENDENCIES = {
    "react": "react", "next": "nextjs", "vue": "vue", "nuxt": "nuxt",
    "svelte": "svelte", "@sveltejs/kit": "sveltekit", "astro": "astro",
    "@angular/core": "angular", "express": "express", "fastify": "fastify",
    "@nestjs/core": "nestjs", "koa": "koa", "electron": "electron",
    "react-native": "react-native", "expo": "expo", "@tarojs/taro": "taro",
    "@dcloudio/uni-app": "uni-app",
}
MANIFESTS = {
    "package.json": "javascript", "pyproject.toml": "python",
    "setup.py": "python", "setup.cfg": "python", "pytest.ini": "python",
    "Cargo.toml": "rust", "go.mod": "go", "pom.xml": "maven",
    "build.gradle": "gradle", "build.gradle.kts": "gradle",
    "settings.gradle": "gradle", "settings.gradle.kts": "gradle",
    "build-profile.json5": "harmonyos", "oh-package.json5": "harmonyos",
    "project.config.json": "miniprogram", "app.json": "app-config",
}
LOCKS = {
    "package-lock.json": "npm", "npm-shrinkwrap.json": "npm",
    "pnpm-lock.yaml": "pnpm", "yarn.lock": "yarn",
    "bun.lock": "bun", "bun.lockb": "bun",
}
PACKAGE_MANAGERS = frozenset(LOCKS.values())
KNOWN_NAMES = tuple(sorted(set(MANIFESTS) | set(LOCKS) | {"gradlew", "hvigorw"}))
TOOLS = (
    "python3", "node", "npm", "pnpm", "yarn", "bun", "cargo", "go",
    "java", "mvn", "gradle", "hdc", "hvigor",
)


class Limits:
    def __init__(self, max_depth=3, max_directories=96, max_entries=2000,
                 max_entries_per_directory=256, max_manifest_bytes=65536):
        # Callers may lower limits; neither the CLI nor imports can raise them.
        self.depth = max(0, min(3, max_depth))
        self.directories = max(1, min(96, max_directories))
        self.entries = max(1, min(2000, max_entries))
        self.per_directory = max(1, min(256, max_entries_per_directory))
        self.manifest_bytes = max(1, min(65536, max_manifest_bytes))


def _join(parent, child):
    return child if parent == "." else parent + "/" + child


def _issue(target, path, code):
    item = {"path": path, "code": code}
    if item not in target:
        target.append(item)


def _safe_read_json(directory_fd, name, expected, limit):
    """Read only a selected regular manifest, never a symlink or special file."""
    fd = None
    try:
        fd = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK,
                     dir_fd=directory_fd)
        current = os.fstat(fd)
        if (not stat.S_ISREG(current.st_mode)
                or (expected.st_dev, expected.st_ino)
                != (current.st_dev, current.st_ino)):
            return None, "file_changed_or_not_regular"
        if current.st_size > limit:
            return None, "manifest_size_limit"
        data = bytearray()
        while len(data) <= limit:
            block = os.read(fd, min(8192, limit + 1 - len(data)))
            if not block:
                break
            data.extend(block)
        if len(data) > limit:
            return None, "manifest_size_limit"
        value = json.loads(data.decode("utf-8-sig"))
        if not isinstance(value, dict):
            return None, "json_object_required"
        return value, None
    except (UnicodeError, ValueError, RecursionError):
        return None, "invalid_json"
    except OSError:
        return None, "manifest_unreadable"
    finally:
        if fd is not None:
            os.close(fd)


def probe(root, limits=None):
    limits = limits or Limits()
    result = {
        "schema_version": 1,
        "observed": {"manifests": [], "framework_clues": [],
                     "package_scripts": [], "lockfiles": [],
                     "package_manager_declarations": []},
        "projects": [], "candidate_commands": [], "tool_availability": {},
        "ambiguities": [], "truncated": [], "errors": [],
        "skipped_symlinks": [], "checks_executed": [],
    }
    for tool in TOOLS:
        try:
            result["tool_availability"][tool] = shutil.which(tool) is not None
        except (OSError, ValueError):
            result["tool_availability"][tool] = False
            _issue(result["errors"], ".", "tool_lookup_failed")

    required = (hasattr(os, "O_NOFOLLOW") and hasattr(os, "O_DIRECTORY")
                and os.open in os.supports_dir_fd and os.scandir in os.supports_fd
                and os.stat in os.supports_dir_fd
                and os.stat in os.supports_follow_symlinks)
    if not required:
        _issue(result["errors"], ".", "safe_descriptor_scan_unavailable")
        return result

    folders = {}
    counts = {"directories": 0, "entries": 0}
    directory_flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
    pending_fds = set()

    def folder_for(relative):
        return folders.setdefault(relative, {
            "files": set(), "scripts": [], "manager": None,
            "seen": set(), "entry_count": 0, "complete": False,
        })

    def accept_entry(relative, name):
        folder = folder_for(relative)
        if counts["entries"] >= limits.entries:
            _issue(result["truncated"], relative, "total_entry_limit")
            return False
        if folder["entry_count"] >= limits.per_directory:
            _issue(result["truncated"], relative, "directory_entry_limit")
            return False
        folder["seen"].add(name)
        folder["entry_count"] += 1
        counts["entries"] += 1
        return True

    def observe_file(directory_fd, relative, name, info):
        folder = folder_for(relative)
        path = _join(relative, name)
        if stat.S_ISLNK(info.st_mode):
            result["skipped_symlinks"].append(path)
            return
        if not stat.S_ISREG(info.st_mode):
            return
        if name in KNOWN_NAMES:
            folder["files"].add(name)
        if name in LOCKS:
            result["observed"]["lockfiles"].append(
                {"path": path, "manager": LOCKS[name]})
        if name in MANIFESTS:
            result["observed"]["manifests"].append(
                {"path": path, "kind": MANIFESTS[name]})
        if name.endswith(".ets"):
            clue = {"project": relative, "clue": "arkts-file",
                    "source": "file-extension"}
            if clue not in result["observed"]["framework_clues"]:
                result["observed"]["framework_clues"].append(clue)
        if name != "package.json":
            return
        value, error = _safe_read_json(directory_fd, name, info,
                                       limits.manifest_bytes)
        if error:
            bucket = "truncated" if error == "manifest_size_limit" else "errors"
            _issue(result[bucket], path, error)
            return
        if "packageManager" in value:
            declaration = value["packageManager"]
            manager, separator, version = (declaration.partition("@")
                                            if isinstance(declaration, str)
                                            else ("", "", ""))
            if manager in PACKAGE_MANAGERS and separator and version:
                folder["manager"] = manager
                result["observed"]["package_manager_declarations"].append(
                    {"project": relative, "manager": manager, "source": path})
            else:
                _issue(result["ambiguities"], relative,
                       "package_manager_declaration_unrecognized")
        scripts = value.get("scripts", {})
        if isinstance(scripts, dict):
            folder["scripts"] = [key for key in SCRIPTS
                                 if isinstance(scripts.get(key), str)]
        result["observed"]["package_scripts"].append(
            {"project": relative, "names": folder["scripts"]})
        for field in ("dependencies", "devDependencies", "peerDependencies"):
            dependencies = value.get(field, {})
            if not isinstance(dependencies, dict):
                continue
            for dependency, framework in DEPENDENCIES.items():
                if dependency in dependencies:
                    clue = {"project": relative, "clue": framework,
                            "dependency": dependency, "source": path}
                    if clue not in result["observed"]["framework_clues"]:
                        result["observed"]["framework_clues"].append(clue)

    def scan_known(directory_fd, relative):
        # Fixed-name metadata probes are bounded by KNOWN_NAMES * directories.
        # Existing entries still consume the shared and per-directory budgets.
        folder_for(relative)
        for name in KNOWN_NAMES:
            try:
                info = os.stat(name, dir_fd=directory_fd, follow_symlinks=False)
            except FileNotFoundError:
                continue
            except OSError:
                _issue(result["errors"], _join(relative, name), "entry_unreadable")
                continue
            if stat.S_ISDIR(info.st_mode):
                continue
            if not accept_entry(relative, name):
                break
            observe_file(directory_fd, relative, name, info)

    def scan_entries(directory_fd, relative, depth, next_level):
        folder = folder_for(relative)
        try:
            with os.scandir(directory_fd) as iterator:
                for entry in iterator:
                    if entry.name in folder["seen"]:
                        continue
                    if not accept_entry(relative, entry.name):
                        break
                    path = _join(relative, entry.name)
                    try:
                        info = entry.stat(follow_symlinks=False)
                    except OSError:
                        _issue(result["errors"], path, "entry_unreadable")
                        continue
                    if stat.S_ISDIR(info.st_mode):
                        if entry.name in SKIP_DIRS or entry.name.startswith("."):
                            continue
                        if depth >= limits.depth:
                            _issue(result["truncated"], path, "depth_limit")
                            continue
                        if counts["directories"] >= limits.directories:
                            _issue(result["truncated"], path, "directory_count_limit")
                            continue
                        child_fd = None
                        try:
                            child_fd = os.open(entry.name, directory_flags,
                                               dir_fd=directory_fd)
                            actual = os.fstat(child_fd)
                            if (actual.st_dev, actual.st_ino) != (info.st_dev, info.st_ino):
                                _issue(result["errors"], path, "directory_changed")
                                continue
                            counts["directories"] += 1
                            pending_fds.add(child_fd)
                            next_level.append((child_fd, path, depth + 1))
                            child_fd = None
                        except OSError:
                            _issue(result["errors"], path, "directory_unreadable")
                        finally:
                            if child_fd is not None:
                                os.close(child_fd)
                        continue
                    observe_file(directory_fd, relative, entry.name, info)
                else:
                    folder["complete"] = True
        except OSError:
            _issue(result["errors"], relative, "directory_unreadable")

    root_fd = None
    try:
        root_path = os.path.abspath(os.fspath(root))
        original = os.lstat(root_path)
        if stat.S_ISLNK(original.st_mode):
            _issue(result["errors"], ".", "root_symlink_rejected")
            return result
        if not stat.S_ISDIR(original.st_mode):
            _issue(result["errors"], ".", "root_not_directory")
            return result
        root_fd = os.open(root_path, directory_flags)
        actual = os.fstat(root_fd)
        if (actual.st_dev, actual.st_ino) != (original.st_dev, original.st_ino):
            _issue(result["errors"], ".", "root_changed")
            return result
        counts["directories"] = 1
        level = [(root_fd, ".", 0)]
        while level:
            # Inspect every known sibling's manifests before consuming any
            # sibling's source/assets entries, then descend breadth first.
            level.sort(key=lambda item: item[1])
            for directory_fd, relative, _ in level:
                scan_known(directory_fd, relative)
            next_level = []
            for directory_fd, relative, depth in level:
                scan_entries(directory_fd, relative, depth, next_level)
                if directory_fd != root_fd:
                    os.close(directory_fd)
                    pending_fds.remove(directory_fd)
            level = next_level
    except (OSError, ValueError, TypeError):
        _issue(result["errors"], ".", "root_unavailable")
        return result
    finally:
        for directory_fd in pending_fds:
            os.close(directory_fd)
        if root_fd is not None:
            os.close(root_fd)

    def command(project, argv, purpose):
        result["candidate_commands"].append({
            "project": project, "argv": argv, "purpose": purpose,
            "status": "not_executed", "requires_project_review": True,
        })

    def package_managers(project):
        current = project
        declaration = None
        managers = set()
        incomplete = False
        while True:
            folder = folders.get(current, {})
            present = folder.get("files", set())
            if declaration is None:
                declaration = folder.get("manager")
            if not managers:
                managers = {LOCKS[name] for name in present if name in LOCKS}
            if not folder.get("complete", False):
                incomplete = True
                break
            if managers and declaration:
                break
            if current == ".":
                break
            current = current.rsplit("/", 1)[0] if "/" in current else "."
        if len(managers) > 1:
            _issue(result["ambiguities"], project, "multiple_package_managers")
        if declaration is not None:
            if managers and managers != {declaration}:
                _issue(result["ambiguities"], project,
                       "package_manager_declaration_lock_conflict")
            managers.add(declaration)
        if incomplete:
            _issue(result["ambiguities"], project, "package_manager_evidence_incomplete")
        elif not managers:
            _issue(result["ambiguities"], project, "no_lockfile_npm_candidate_only")
            managers.add("npm")
        return sorted(managers)

    for project, folder in sorted(folders.items()):
        files = folder["files"]
        ecosystems = sorted({MANIFESTS[name] for name in files if name in MANIFESTS})
        if not ecosystems:
            continue
        managers = package_managers(project) if "package.json" in files else []
        result["projects"].append({"path": project, "ecosystems": ecosystems,
                                   "package_managers": managers})
        for manager in managers:
            for script in folder["scripts"]:
                command(project, [manager, "run", script], script)
        if "Cargo.toml" in files:
            for task in ("build", "test", "check"):
                command(project, ["cargo", task], task)
        if "go.mod" in files:
            for task in ("build", "test"):
                command(project, ["go", task, "./..."], task)
        if "pom.xml" in files:
            command(project, ["mvn", "test"], "test")
        if "gradle" in ecosystems:
            command(project, ["./gradlew" if "gradlew" in files else "gradle", "tasks"],
                    "discover-tasks")
        if "harmonyos" in ecosystems:
            if "hvigorw" in files:
                command(project, ["./hvigorw", "tasks"], "discover-tasks")
            _issue(result["ambiguities"], project, "harmony_build_target_not_inferred")
        if "miniprogram" in ecosystems:
            _issue(result["ambiguities"], project, "miniprogram_host_not_inferred")
        if "app-config" in ecosystems and "miniprogram" not in ecosystems:
            _issue(result["ambiguities"], project, "app_json_platform_ambiguous")
        if "python" in ecosystems:
            if "pytest.ini" in files:
                command(project, ["python3", "-m", "pytest"], "test")
            else:
                _issue(result["ambiguities"], project, "python_test_runner_not_inferred")
    if not result["projects"]:
        _issue(result["ambiguities"], ".", "project_type_unknown_not_unsupported")
    result["scan_counts"] = counts
    for entries in result["observed"].values():
        entries.sort(key=lambda item: json.dumps(item, sort_keys=True))
    result["skipped_symlinks"].sort()
    return result


def main(argv=None):
    args = sys.argv[1:] if argv is None else argv
    if len(args) != 1:
        print(json.dumps({"errors": [{"path": ".", "code": "expected_one_root_argument"}],
                          "checks_executed": []}))
        return 2
    result = probe(args[0])
    print(json.dumps(result, ensure_ascii=True, indent=2))
    return 2 if result["errors"] else 0


if __name__ == "__main__":
    sys.exit(main())
