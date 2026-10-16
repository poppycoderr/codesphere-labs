"""与 Regex.java、regex.js 同一组用例"""
import re
import sys


def out(k, v):
    print(f"python\t{k}\t{v}")


def try_it(f):
    try:
        return f()
    except Exception as e:
        return f"异常 {type(e).__name__}"


out("version", sys.version.split()[0])
out("digit.arabic_indic", bool(re.search(r"^\d+$", "١٢٣")))
out("digit.fullwidth", bool(re.search(r"^\d+$", "１２３")))
out("word.chinese", bool(re.search(r"^\w+$", "中文")))
out("dollar.trailing_newline", bool(re.search(r"abc$", "abc\n")))
out("dot.newline", bool(re.search(r"a.b", "a\nb")))
out("whole.match_api", f"{bool(re.match(r'\d{3}', '1234'))}（match）/ {bool(re.fullmatch(r'\d{3}', '1234'))}（fullmatch）")
out("named.syntax_java_style", try_it(lambda: re.search(r"(?<y>\d{4})-(?<m>\d{2})", "2026-10").group("y")))
out("named.syntax_python_style", try_it(lambda: re.search(r"(?P<y>\d{4})-(?P<m>\d{2})", "2026-10").group("y")))
out("replace.numbered", re.sub(r"(\d{4})-(\d{2})", "$2/$1", "2026-10"))
out("replace.backslash_template", re.sub(r"(\d{4})-(\d{2})", r"\2/\1", "2026-10"))
out("replace.named", re.sub(r"(?P<y>\d{4})-(?P<m>\d{2})", r"\g<m>/\g<y>", "2026-10"))
out("split.trailing_empty", "a,b,,".split(",") and re.split(",", "a,b,,"))
out("split.dot", re.split(".", "a.b.c"))
out("case.insensitive_unicode", f"{bool(re.search('straße', 'STRASSE', re.I))} / {bool(re.search('é', 'É', re.I))}")
