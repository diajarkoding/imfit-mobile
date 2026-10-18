#!/usr/bin/env python3
"""Read-only package check for this skill's deliberately simple YAML subset.

Checks packaging, local Markdown links and repo paths, not full YAML, model behavior,
Kotlin symbol correctness or Android UI. Use skills-ref for official format validation.
No third-party dependencies, network, installs or source writes.
"""
import argparse
import json
import re
from pathlib import Path


def scalar(value):
    if value.startswith('"'):
        parsed = json.loads(value)
        if not isinstance(parsed, str):
            raise ValueError("Metadata values must be strings")
        return parsed
    if not value or any(char in value for char in "{}[]#") or ": " in value:
        raise ValueError("Unsupported YAML syntax; use skills-ref for general YAML")
    return value


def check(skill_dir, repo_root):
    errors = []
    skill_file = skill_dir / "SKILL.md"
    if not skill_file.is_file():
        return ["Missing SKILL.md"]
    text = skill_file.read_text(encoding="utf-8")
    match = re.match(r"\A---\n(.*?)\n---\n", text, re.S)
    if not match:
        return ["Missing YAML frontmatter delimiters"]
    fields = {}
    metadata = {}
    in_metadata = False
    try:
        for line in match[1].splitlines():
            if not line.strip():
                continue
            if line.startswith("  ") and in_metadata:
                key, value = line.strip().split(": ", 1)
                if key in metadata:
                    raise ValueError("Duplicate metadata key: " + key)
                metadata[key] = scalar(value)
                continue
            key, separator, value = line.partition(":")
            if not separator or not re.fullmatch(r"[a-z][a-z-]*", key) or key in fields:
                raise ValueError("Invalid or duplicate frontmatter key: " + key)
            in_metadata = key == "metadata" and not value.strip()
            fields[key] = metadata if in_metadata else scalar(value.strip())
    except (ValueError, json.JSONDecodeError) as exc:
        errors.append(str(exc))
    allowed = {"name", "description", "compatibility", "metadata", "license", "allowed-tools"}
    if set(fields) - allowed:
        errors.append("Unknown frontmatter fields")
    name = fields.get("name", "")
    if not isinstance(name, str) or not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", name) or len(name) > 64:
        errors.append("Invalid skill name")
    elif name != skill_dir.name:
        errors.append("Skill name must match directory name")
    for key, maximum in [("description", 1024), ("compatibility", 500)]:
        value = fields.get(key)
        if (key == "description" or value is not None) and (not isinstance(value, str) or not 1 <= len(value) <= maximum):
            errors.append("Invalid " + key)
    if len(text.splitlines()) >= 500:
        errors.append("SKILL.md exceeds this package's <500-line guideline")
    if not metadata.get("version"):
        errors.append("Missing package metadata.version")
    files = [skill_file] + sorted((skill_dir / "references").glob("*.md"))
    for path in files:
        content = path.read_text(encoding="utf-8")
        for target in re.findall(r"\[[^\]]*\]\(([^)\s]+)\)", content):
            if re.match(r"[a-z]+://", target) or target.startswith("#"):
                continue
            destination = (path.parent / target.split("#", 1)[0]).resolve()
            if not destination.exists():
                errors.append(f"Broken local link in {path.name}: {target}")
        for target in re.findall(r"`((?:app|docs)/[^`\n]+)`", content):
            if not (repo_root / target).exists():
                errors.append(f"Missing repo path in {path.name}: {target}")
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--skill-dir", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[4])
    args = parser.parse_args()
    errors = check(args.skill_dir.resolve(), args.repo_root.resolve())
    if errors:
        for error in errors:
            print("FAIL: " + error)
        return 1
    print("PASS: local package/frontmatter-subset/link/repo-path checks")
    print("NOT_TESTED: official skills-ref validation, agent activation/behavior, Android runtime/visual")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
