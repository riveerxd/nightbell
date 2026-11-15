#!/usr/bin/env python3
"""
把通知栏 / 告警页 RemoteViews 里的硬编码英文模板改成中文。

这些字符串带 ${...} 插值，运行时值不固定，没法靠 L10n 的精确查表命中，
所以直接改 Kotlin 字符串字面量的固定部分，变量表达式原样保留。
纯文本替换，不触碰任何调用结构，编译风险最低。上游更新后可重跑本脚本。
"""
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ALERTS = os.path.join(ROOT, "app", "src", "main", "kotlin", "me", "river", "nightbell", "data", "alerts")

PAIRS = [
    # 通知栏
    ("and $hidden more", "另有 $hidden 条"),
    ("${monitor.displayName} is back", "${monitor.displayName} 已恢复"),
    ("URGENT · ${monitor.displayName} is down", "紧急 · ${monitor.displayName} 已故障"),
    ("${result.latencyMs} ms — budget is $sloMs ms", "${result.latencyMs} ms — 预算 $sloMs ms"),
    ("${state.consecutiveErrors} checks in a row failed inside Nightbell itself",
     "Nightbell 内部连续 ${state.consecutiveErrors} 次检查失败"),
    ("${monitor.displayName} is not checking", "${monitor.displayName} 未在进行检查"),
    ("${problem.headline}. Open the monitor to sign in again.", "${problem.headline}。请打开监控重新登录。"),
    ("Version ${release.version} is ready", "版本 ${release.version} 已就绪"),
    ("$monitorName is down", "$monitorName 已故障"),
    ("Nightbell can't complete its checks", "Nightbell 无法完成检查"),
    ("Nightbell update available", "Nightbell 有新版本"),
    ("This is what a real alert looks like.", "这是一条真实告警的预览。"),
    # 全屏告警页 / RemoteViews
    ("${content.monitorName} is down", "${content.monitorName} 已故障"),
    ("${content.headline} · down for ${content.downFor}", "${content.headline} · 已故障 ${content.downFor}"),
    ("Down for ${content.downFor} · ${content.headline}", "已故障 ${content.downFor} · ${content.headline}"),
    ("${content.monitorName} is DOWN", "${content.monitorName} 已故障"),
    ("🔴 ${content.monitorName} is down", "🔴 ${content.monitorName} 已故障"),
    ("${content.headline} · ${content.failedChecks} failed checks",
     "${content.headline} · ${content.failedChecks} 次检查失败"),
    ("URGENT · repeats every ${content.repeatMinutes.coerceAtLeast(1)} min",
     "紧急 · 每 ${content.repeatMinutes.coerceAtLeast(1)} 分钟重复一次"),
    ("${content.failedChecks} failed checks · repeats every ",
     "${content.failedChecks} 次检查失败 · 每隔 "),
    # 注意："URGENT" 本身是逻辑里用到的常量，不能全局替换，只改 setSubText 这一处调用。
]


def main():
    total = 0
    for name in sorted(os.listdir(ALERTS)):
        if not name.endswith(".kt"):
            continue
        path = os.path.join(ALERTS, name)
        with open(path, encoding="utf-8") as f:
            src = f.read()
        before = src
        hit = 0
        for en, zh in PAIRS:
            old, new = '"' + en + '"', '"' + zh + '"'
            n = src.count(old)
            if n:
                src = src.replace(old, new)
                hit += n
        if '.setSubText("URGENT")' in src:
            src = src.replace('.setSubText("URGENT")', '.setSubText("紧急")')
            hit += 1
        if src != before:
            with open(path, "w", encoding="utf-8", newline="") as f:
                f.write(src)
            print("%-28s %d 处" % (name, hit))
            total += hit
    print("合计替换 %d 处" % total)


if __name__ == "__main__":
    main()
