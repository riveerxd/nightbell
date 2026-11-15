#!/usr/bin/env python3
"""
生成中文翻译表 Zh.kt（静态表 + 插值模板规则）。

用法：python tools/gen_zh.py
输入：tools/zh_*.tsv   每行「英文<TAB>中文」，# 开头为注释
      tools/zh_templates_done.tsv  插值模板「英文模板<TAB>中文模板」
输出：app/src/main/kotlin/me/river/nightbell/ui/i18n/Zh.kt

英文列写的是 Kotlin 源码里的字符串字面量形态（可以带 \\n、\\"），
脚本会还原成运行时字符串再写进 Kotlin，保证自定义 Text 查表能命中。

插值模板（如 "Updated to $name"）运行时已被求值，普通查表命中不了。
这里把它编译成正则规则：静态部分转义、占位符换成捕获组，命中后把捕获值
填回中文模板里同名占位符。只保留静态文字足够长、不会误伤其它文案的规则。
"""
import glob
import json
import os
import re

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "app", "src", "main", "kotlin")
OUT = os.path.join(ROOT, "app", "src", "main", "kotlin", "me", "river", "nightbell", "ui", "i18n", "Zh.kt")
TSV = os.path.join(ROOT, "tools", "zh_*.tsv")
TEMPLATE_TSV = os.path.join(ROOT, "tools", "zh_templates_done.tsv")

FIELD = re.compile(
    r'\b(text|contentDescription|label|title|placeholder|supportingText|headline|body|'
    r'message|subtitle|description|blurb|hint|summary|content|stateDescription)\s*=\s*'
    r'((?:"(?:[^"\\]|\\.)*"\s*(?:\+\s*)?)+)', re.M
)

SLOT = re.compile(r'\$\{[^}]*\}|\$[A-Za-z_]\w*')


def runtime(s: str) -> str:
    """源码字面量 -> 运行时字符串。只还原转义序列，保留 … — · → 等非 ASCII 字符。"""
    escapes = {
        "n": "\n", "t": "\t", "r": "\r", "0": "\0",
        "b": "\b", "f": "\f", "\\": "\\", '"': '"', "'": "'",
    }
    out = []
    i = 0
    while i < len(s):
        if s[i] == "\\" and i + 1 < len(s):
            c = s[i + 1]
            if c in escapes:
                out.append(escapes[c])
                i += 2
                continue
            if c == "u" and i + 6 <= len(s):
                try:
                    out.append(chr(int(s[i + 2:i + 6], 16)))
                    i += 6
                    continue
                except ValueError:
                    pass
            out.append(c)
            i += 2
            continue
        out.append(s[i])
        i += 1
    return "".join(out)


def kt(s: str) -> str:
    """Python 字符串 -> Kotlin 字符串字面量（$ 转义，运行时是字面 $）"""
    return json.dumps(s, ensure_ascii=False).replace("$", "\\$")


def read_tsv(path):
    """返回 [(英文运行时串, 中文译文)]，跳过注释/空译文/重复 key。"""
    rows = []
    if not os.path.exists(path):
        return rows
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n").rstrip("\r")
            if not line or line.startswith("#") or "\t" not in line:
                continue
            en, zh = line.split("\t", 1)
            en, zh = en.strip(), zh.strip()
            if not en or not zh:
                continue
            try:
                key = runtime(en)
            except Exception:
                key = en
            rows.append((key, zh.replace("\\n", "\n")))
    return rows


def collect_source_keys():
    keys = set()
    for path in glob.glob(os.path.join(SRC, "**", "*.kt"), recursive=True):
        with open(path, encoding="utf-8", errors="ignore") as f:
            src = f.read()
        for m in FIELD.finditer(src):
            raw = m.group(2)
            if "$" in raw:
                continue
            key = "".join(re.findall(r'"((?:[^"\\]|\\.)*)"', raw))
            if len(key) >= 2 and re.search(r"[A-Za-z]{2}", key) and not re.search(
                r"(https?://|/|\.kt|\.json|androidx|kotlin|me\.river)", key
            ):
                keys.add(key)
    return keys


def build_template_rules(rows):
    """英文模板 -> (正则, 占位符列表, 中文模板)。保守筛选，避免误伤。"""
    rules = []
    seen = set()
    for en, zh in rows:
        slots = SLOT.findall(en)
        if not slots:
            continue
        # 中文模板必须含同名占位符，否则运行时无法回填
        if set(SLOT.findall(zh)) != set(slots):
            continue
        parts = SLOT.split(en)
        static = "".join(parts)
        # 跳过残留 $ / 换行 / 条件表达式：这些模板的正则既难匹配，又容易
        # 生成 Kotlin 无法解析的转义序列，宁可保持英文也不要冒编译风险。
        if "$" in static or "\n" in en or "\r" in en or "(" in static or ")" in static:
            continue
        # 静态英文文字太短的规则（如 "$ms ms"）会误匹配大量无关文案，直接丢弃
        letters = re.findall(r"[A-Za-z]{3,}", static)
        if not letters or len(static.strip()) < 5:
            continue
        if en == zh:
            continue  # 没译文就别生成一条英文到英文的空规则
        pattern = "^"
        for i, part in enumerate(parts):
            pattern += re.escape(part)
            if i < len(slots):
                pattern += "(.+?)"
        pattern += "$"
        if pattern in seen:
            continue
        seen.add(pattern)
        rules.append((pattern, slots, zh))
    # 静态文字多的规则优先，减少短规则抢占
    rules.sort(key=lambda r: -len(r[0]))
    return rules


def main():
    table = {}
    for path in sorted(glob.glob(TSV)):
        if os.path.basename(path).startswith("zh_templates"):
            continue  # 模板表单独处理，避免把含 $ 的模板塞进静态表
        for en, zh in read_tsv(path):
            table[en] = zh

    template_rows = read_tsv(TEMPLATE_TSV)
    rules = build_template_rules(template_rows)

    lines = [
        "// 由 tools/gen_zh.py 从 tools/zh_*.tsv 生成，请勿手改。",
        "// 补翻译：编辑对应 tsv 后重新执行 python tools/gen_zh.py",
        "package me.river.nightbell.ui.i18n",
        "",
        "internal data class ZhTemplate(",
        "    val pattern: String,",
        "    val slots: List<String>,",
        "    val zh: String,",
        ")",
        "",
        "internal val ZH: HashMap<String, String> = hashMapOf<String, String>().apply {",
    ]
    for en in sorted(table):
        lines.append("    put(%s, %s)" % (kt(en), kt(table[en])))
    lines += ["}", "", "internal val ZH_TEMPLATES: List<ZhTemplate> = listOf("]
    for pattern, slots, zh in rules:
        slot_literal = ", ".join(kt(s) for s in slots)
        lines.append("    ZhTemplate(%s, listOf(%s), %s)," % (kt(pattern), slot_literal, kt(zh)))
    lines += [")", ""]
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines))

    src_keys = collect_source_keys()
    covered = sum(1 for k in src_keys if runtime(k) in table)
    print("静态翻译条目: %d" % len(table))
    print("插值模板规则: %d (输入 %d 条)" % (len(rules), len(template_rows)))
    print("源码 UI 文案: %d，已覆盖 %d (%.1f%%)" % (
        len(src_keys), covered, covered * 100.0 / max(1, len(src_keys))))
    print("已写出: %s" % OUT)

    pending = [k for k in sorted(src_keys) if runtime(k) not in table]
    report = os.path.join(ROOT, "tools", "pending.txt")
    with open(report, "w", encoding="utf-8", newline="\n") as f:
        for k in pending:
            f.write(k + "\n")
    print("待译清单: %s (%d 条)" % (report, len(pending)))

    src_runtime = {runtime(k) for k in src_keys}
    orphans = sorted(k for k in table if k not in src_runtime)
    orphan_file = os.path.join(ROOT, "tools", "orphans.txt")
    with open(orphan_file, "w", encoding="utf-8", newline="\n") as f:
        for k in orphans:
            f.write(k + "\n")
    print("非 UI 字段或需核对的条目: %s (%d 条)" % (orphan_file, len(orphans)))


if __name__ == "__main__":
    main()
