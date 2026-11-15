#!/usr/bin/env python3
"""给 zh_templates.tsv 生成中文模板（离线词典兜底版）。

若 tools/zh_templates_done.tsv 已由人工/译者产出且行数正确，则原样保留。
中文模板必须保留英文列里的每一个占位符，运行时 L10n 才能把捕获值填回去。
"""
import os, re

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "tools", "zh_templates.tsv")
DONE = os.path.join(ROOT, "tools", "zh_templates_done.tsv")
SLOT = re.compile(r'\$\{[^}]*\}|\$[A-Za-z_]\w*')

# 长短语优先；键按长度降序应用，避免 "minutes" 被 "min" 抢先替换。
PHRASES = {
    "Updated to": "已更新为", "Sent via": "通过", "Delivered.": "已送达。",
    "Not delivered.": "未送达。", "Couldn't reach": "无法连接", "Could not save widget": "小组件保存失败",
    "Configure widget": "配置小组件", "Add these": "将这些", "Move these": "移动这些",
    "Edit group": "编辑分组", "Re-pick element": "重新选择元素", "Move element": "移动元素",
    "Hold to delete": "长按删除", "Show all": "显示全部", "Show fewer": "显示更少",
    "Show more": "显示更多", "more": "更多", "hidden": "隐藏",
    "Re-alerts every": "每隔", "while down": "（故障期间）重复告警",
    "Re-checks every": "每隔", "after a failure": "在失败后重新检查",
    "once it answers again": "恢复响应后重新检查",
    "Checked": "已检查", "Check": "检查", "Check now": "立即检查",
    "Decrease": "减少", "Increase": "增加", "Set": "设置", "Add": "添加",
    "Edit": "编辑", "Open": "打开", "Pause": "暂停", "Paused for": "已暂停",
    "Resume": "恢复", "Until": "直到", "Via": "经由", "through": "经由",
    "over": "超过", "later": "后", "earlier": "前", "since": "自", "was": "曾为",
    "value": "值", "selected": "已选择", "days left": "天后到期", "days": "天",
    "minutes": "分钟", "minute": "分钟", "min": "分钟", "m ago": "分钟前",
    "seconds": "秒", "hour": "小时", "hours": "小时", "bytes": "字节",
    "forks": "个 fork", "stars": "个 star", "tab": "标签页",
    "monitors": "个监控", "monitor": "个监控", "checks": "次检查", "check": "次检查",
    "failed": "失败", "failure": "失败", "failures": "次失败", "outage": "故障",
    "Healthy": "正常", "Degraded": "性能下降", "Degraded above": "超过",
    "is down": "已故障", "down for": "已故障", "recovers": "恢复", "recovered": "已恢复",
    "Expired": "已过期", "Expires": "到期", "certificate expires in": "证书在",
    "Alerts muted for": "告警静音", "· muted, no alerts": "· 已静音，不告警",
    "Answering again": "恢复响应", "no checks yet": "尚无检查",
    "ready": "就绪", "slowest": "最慢", "Highest is": "最高为", "Lowest is": "最低为",
    "All": "全部", "parts report up": "项均正常", "speak": "正常",
    "Last check:": "上次检查：", "Last response": "上次响应", "Last internal error:": "上次内部错误：",
    "Newest from": "最新来自", "Also watching": "同时监控", "Available at": "可用于",
    "Found": "找到", "Added": "已添加", "Grouped": "已分组", "Group these": "分组这些",
    "Imported": "已导入", "Exported": "已导出", "Log written": "日志已写入",
    "Not showing": "不显示", "Nothing else watches": "没有其他监控在监视",
    "Blank uses the shared": "留空则使用共享的", "Fixed at": "固定为",
    "At most": "最多", "Every": "每隔", "Across": "在", "Anything from": "从",
    "Budget": "预算", "over budget": "超出预算", "expected": "预期",
    "latest": "最新", "Comment on #": "评论 #", "PAGE #": "第 # 页", "Element": "元素",
    "LINE": "行", "LEFT": "剩余", "MONITORS": "监控", "HTTP": "HTTP",
}
ORDER = sorted(PHRASES, key=len, reverse=True)
PATTERNS = [(re.compile(r'(?<!\w)' + re.escape(k) + r'(?!\w)'), PHRASES[k]) for k in ORDER if k.strip()]
PATTERNS += [(re.compile(r'(?<!\w)' + re.escape(k.strip()) + r'(?!\w)'), v)
             for k in ORDER if k != k.strip() and k.strip()]


def translate_template(en: str) -> str:
    slots = SLOT.findall(en)
    parts = SLOT.split(en)
    # 用不可能出现在文案里的 token 保护占位符，词典只作用于静态文字
    protected = "".join(
        p + ("\x01%d\x02" % i if i < len(slots) else "")
        for i, p in enumerate(parts)
    )
    out = protected
    for pat, rep in PATTERNS:
        out = pat.sub(lambda m: rep, out)
    for i, s in enumerate(slots):
        out = out.replace("\x01%d\x02" % i, s)
    # 清理 token 残留 + 多余空白
    out = re.sub(r'\x01\d+\x02', '', out)
    out = re.sub(r'[ \t]{2,}', ' ', out).strip() or en
    # 质检：只保留能整体译干净的短模板；半中半英的长句宁可保持英文
    plain = SLOT.sub('', out)
    tech = {'HTTP','HTTPS','TLS','DNS','TCP','UDP','JSON','URL','API','GitHub','Webhook',
            'PromQL','Tor','I2P','SOCKS5','XPath','Bearer','OAuth','WebView','Doze',
            'WorkManager','APK','ms','IPv6','GrapheneOS','KB','IP','LEFT','MONITORS'}
    leftover = [w for w in re.findall(r'[A-Za-z]{2,}', plain) if w not in tech]
    if leftover:
        return en
    return out


def main():
    rows = [l.rstrip('\r\n') for l in open(SRC, encoding='utf-8')][1:]
    if os.path.exists(DONE):
        done = [l.rstrip('\r\n') for l in open(DONE, encoding='utf-8')]
        if len(done) == len(rows) and all(l.count('\t') == 1 for l in done):
            print('已有译文 %d 行，原样保留' % len(done))
            return
    out = []
    for line in rows:
        en = line.split('\t', 1)[0]
        zh = translate_template(en)
        if set(SLOT.findall(zh)) != set(SLOT.findall(en)):
            zh = en  # 占位符对不上就保持英文，绝不生成坏规则
        zh = zh.replace('\r', '').replace('\n', '\\n').replace('\t', ' ')
        out.append(en + '\t' + zh)
    with open(DONE, 'w', encoding='utf-8', newline='\n') as f:
        f.write('\n'.join(out) + '\n')
    changed = sum(1 for l in out if l.split('\t')[0] != l.split('\t')[1])
    print('生成 %d 行，其中 %d 行有中文译文' % (len(out), changed))


if __name__ == '__main__':
    main()
