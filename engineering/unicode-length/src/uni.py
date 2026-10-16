"""与 Uni.java、uni.js 同一组用例。Python 标准库没有字形（grapheme cluster）分割"""
import sys
import unicodedata


def out(k, v):
    print(f"python\t{k}\t{v}")


out("version", sys.version.split()[0])
cases = [("zhong", "中"), ("e_acute_nfc", "é"), ("e_acute_nfd", "é"), ("grin", "\U0001F600"), ("family_zwj", "\U0001F468‍\U0001F469‍\U0001F467"), ("flag_cn", "\U0001F1E8\U0001F1F3"), ("thumbs_skin", "\U0001F44D\U0001F3FD")]
for name, s in cases:
    out(f"len.{name}", f"len()={len(s)} utf16={len(s.encode('utf-16-le')) // 2} utf8={len(s.encode('utf-8'))} graphemes=标准库不支持")
fam = "\U0001F468‍\U0001F469‍\U0001F467"
out("cut.first_unit", f"s[:1] 得到 U+{ord(fam[:1]):X}：按码点切，一家三口只剩第一个人")
out("reverse.flag", f"s[::-1] 反转国旗：{[hex(ord(c))[2:] for c in '\U0001F1E8\U0001F1F3'[::-1]]}")
out("equals.nfc_nfd", f"é（预组合）== e+重音：{'é' == 'é'}；NFC 规范化后：{unicodedata.normalize('NFC', 'é') == 'é'}")
out("upper.sharp_s", f"'straße'.upper()：{'straße'.upper()}，长度 {len('straße')} → {len('straße'.upper())}")
