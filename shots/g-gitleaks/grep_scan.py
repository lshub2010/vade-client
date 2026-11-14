#!/usr/bin/env python3
"""A6 第二道门：私钥 / 云厂商 key 特征串 grep。

设计要点：
  * 只输出「文件:行 + 命中长度 + 前 3 位」，绝不打印完整值（报告与日志可安全入库）。
  * 工作区用 rg（--no-ignore 覆盖 .venv/build/__pycache__ 等被忽略目录，-I 跳过二进制）。
  * 历史用单次 `git grep <pat> $(git rev-list --all)`，覆盖所有提交的所有树。
  * 每条都带一条 positive control（已知必出现的字符串），证明扫描确实走了文件。
"""
import json
import subprocess
import sys
import collections

PATS = {
    'PRIVATE_KEY_HEADER': r'-----BEGIN (RSA |OPENSSH |EC |DSA |PGP |ENCRYPTED )?PRIVATE KEY',
    'AWS_AKIA': r'AKIA[0-9A-Z]{16}',
    'AWS_ASIA': r'ASIA[0-9A-Z]{16}',
    'SK_DASH': r'sk-[A-Za-z0-9]{20,}',
    'SK_LIVE': r'sk_live_[A-Za-z0-9]{10,}',
    'GHP': r'ghp_[A-Za-z0-9]{36}',
    'GHO': r'gho_[A-Za-z0-9]{36}',
    'GH_PAT': r'github_pat_[A-Za-z0-9_]{50,}',
    'SLACK': r'xox[baprs]-[A-Za-z0-9-]{10,}',
    'GOOGLE_API': r'AIza[0-9A-Za-z_-]{35}',
}


def worktree(repo):
    print(f'### WORKTREE {repo}')
    total = 0
    for name, pat in PATS.items():
        p = subprocess.run(
            ['rg', '--json', '--no-ignore', '--hidden', '--no-messages', '-I',
             '--max-filesize', '8M', '-g', '!.git/', '-e', pat, '.'],
            cwd=repo, capture_output=True, text=True)
        for ln in p.stdout.splitlines():
            m = json.loads(ln)
            if m['type'] != 'match':
                continue
            d = m['data']
            subs = d.get('submatches') or []
            v = subs[0]['match']['text'] if subs else ''
            total += 1
            print(f'   {name:20s} {d["path"]["text"]}:{d["line_number"]}  len={len(v)} p3={v[:3]!r}')
    ctl = subprocess.run(
        ['rg', '-c', '--no-ignore', '--hidden', '--no-messages', '-I',
         '-g', '!.git/', '-e', 'Vade', '.'], cwd=repo, capture_output=True, text=True)
    print(f'   TOTAL_WORKTREE_MATCHES={total}')
    print(f'   POSITIVE_CONTROL files_containing_Vade={len(ctl.stdout.splitlines())}')
    return total


def history(repo):
    print(f'### HISTORY {repo}')
    commits = subprocess.run(['git', 'rev-list', '--all'], cwd=repo,
                             capture_output=True, text=True).stdout.split()
    agg = collections.Counter()
    values = collections.defaultdict(set)
    for name, pat in PATS.items():
        p = subprocess.run(['git', 'grep', '-I', '-o', '-n', '-E', '--', pat] + commits,
                           cwd=repo, capture_output=True, text=True)
        for line in p.stdout.splitlines():
            parts = line.split(':', 3)          # <commit>:<path>:<line>:<match>
            if len(parts) < 4:
                continue
            agg[(name, parts[1], len(parts[3]))] += 1
            values[name].add(parts[3])
    for (name, path, length), occ in sorted(agg.items()):
        print(f'   {name:20s} {path}  len={length} occurrences={occ}')
    print(f'   TOTAL_HISTORY_OCCURRENCES={sum(agg.values())}  commits={len(commits)}')
    for name, vs in sorted(values.items()):
        print(f'   distinct_values[{name}]={len(vs)} lens={sorted({len(x) for x in vs})}')
    return sum(agg.values())


if __name__ == '__main__':
    repo, tag = sys.argv[1], sys.argv[2]
    wt = worktree(repo)
    hist = history(repo)
    print(f'### SUMMARY {tag}: worktree={wt} history={hist}')
