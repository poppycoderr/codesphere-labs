// 与 Uni.java、uni.py 同一组用例
const out = (k, v) => console.log(`node\t${k}\t${v}`);
out("version", process.version);
const seg = new Intl.Segmenter("en", { granularity: "grapheme" });
const cases = [["zhong", "中"], ["e_acute_nfc", "é"], ["e_acute_nfd", "é"], ["grin", "\u{1F600}"], ["family_zwj", "\u{1F468}‍\u{1F469}‍\u{1F467}"], ["flag_cn", "\u{1F1E8}\u{1F1F3}"], ["thumbs_skin", "\u{1F44D}\u{1F3FD}"]];
for (const [name, s] of cases) {
  out(`len.${name}`, `length=${s.length} codePoints=${[...s].length} utf8=${new TextEncoder().encode(s).length} graphemes=${[...seg.segment(s)].length}`);
}
const cut = "\u{1F600}".slice(0, 1);
out("cut.first_unit", `slice(0, 1) 得到 U+${cut.charCodeAt(0).toString(16).toUpperCase()}，isWellFormed=${cut.isWellFormed()}；TextEncoder 编码成 ${[...new TextEncoder().encode(cut)].map((b) => b.toString(16)).join(" ")}（U+FFFD）`);
out("reverse.flag", `[...s].reverse() 反转国旗：${JSON.stringify([..."\u{1F1E8}\u{1F1F3}"].reverse().map((c) => c.codePointAt(0).toString(16)))}`);
out("equals.nfc_nfd", `é（预组合）=== e+重音：${"é" === "é"}；NFC 规范化后：${"é".normalize("NFC") === "é"}`);
out("upper.sharp_s", `"straße".toUpperCase()：${"straße".toUpperCase()}，长度 ${"straße".length} → ${"straße".toUpperCase().length}`);
