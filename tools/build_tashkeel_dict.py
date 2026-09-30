#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
يبني قاموس التشكيل الخاص بالتطبيق من مدوّنة عربية مشكولة (مثل Tashkeela).

الاستخدام (مرة واحدة على الكمبيوتر):
    python3 tools/build_tashkeel_dict.py  <مجلد-المدوّنة-أو-ملفات-txt>  \
        -o app/src/main/assets/tashkeel_dict.txt

الفكرة:
  1) نقرأ كل الكلمات المشكولة تشكيلًا كاملًا تقريبًا.
  2) لكل كلمة (بدون تشكيل) نعدّ الأشكال المشكولة الممكنة ونختار الأكثر شيوعًا.
  3) الكلمات المتضاربة (عِلْم / عَلَم) تُتجاهل، فلا نفرض تشكيلًا خاطئًا.
  4) حركة الإعراب في آخر الكلمة تُحذف (لأنها تتغير حسب الجملة وقد تُنطق خطأً)،
     إلا لو كانت ثابتة في المدوّنة (ضمائر، أفعال ماضية، أدوات).
الناتج: ملف نصي، كل سطر:  كلمة_بلا_تشكيل <TAB> كلمة_مشكولة
"""
import argparse
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

SHADDA = '\u0651'
SUKUN = '\u0652'
DAGGER = '\u0670'
FINAL_STRIP = set('\u064B\u064C\u064D\u064E\u064F\u0650')   # تنوين + فتحة/ضمة/كسرة
KEEP_MARKS = set('\u064B\u064C\u064D\u064E\u064F\u0650\u0651\u0652\u0670')

TOKEN_RE = re.compile('[\u0621-\u064A\u064B-\u0652\u0670]+')
PLAIN_RE = re.compile('^[\u0621-\u064A]{3,16}$')

# نفس توحيد الحروف الذي يفعله التطبيق (ArabicPhonetics.normalize) + حذف علامات نادرة
TRANS = {
    0x0671: '\u0627',   # ٱ -> ا
    0x06A9: '\u0643',   # ک -> ك
    0x06CC: '\u064A',   # ی -> ي
    0x06E1: SUKUN,      # ۡ -> ْ
    0x0640: None,       # تطويل
}
for c in range(0x0653, 0x0660):
    TRANS[c] = None


def parse(tok):
    """كلمة -> قائمة (حرف، علامات) بترتيب موحّد: شدّة ثم حركة ثم ألف خنجرية. None لو غير صالحة."""
    out = []
    for ch in tok:
        if '\u0621' <= ch <= '\u064A':
            out.append((ch, []))
        elif ch in KEEP_MARKS:
            if not out:
                return None
            out[-1][1].append(ch)
        else:
            return None
    res = []
    for letter, marks in out:
        vowels = [m for m in marks if m not in (SHADDA, DAGGER)]
        if len(vowels) > 1 or marks.count(SHADDA) > 1:
            return None
        ordered = []
        if SHADDA in marks:
            ordered.append(SHADDA)
        ordered += vowels
        if DAGGER in marks:
            ordered.append(DAGGER)
        res.append((letter, ordered))
    return res


def render(cl):
    return ''.join(l + ''.join(m) for l, m in cl)


def plain_of(cl):
    return ''.join(l for l, _ in cl)


def vowel_count(cl):
    return sum(1 for _, m in cl for x in m if x not in (SHADDA, DAGGER))


def split_final(cl):
    """يفصل حركة الإعراب الأخيرة: (الكلمة بدونها، الحركة المحذوفة)."""
    cl = [(l, list(m)) for l, m in cl]
    last_l, last_m = cl[-1]
    removed = [x for x in last_m if x in FINAL_STRIP]
    if removed:
        cl[-1] = (last_l, [x for x in last_m if x not in FINAL_STRIP])
        return cl, ''.join(removed)
    # كتابًا: التنوين على الحرف قبل ألف النهاية
    if len(cl) >= 2 and last_l == '\u0627' and not last_m and '\u064B' in cl[-2][1]:
        cl[-2] = (cl[-2][0], [x for x in cl[-2][1] if x != '\u064B'])
        return cl, '\u064B'
    return cl, ''


def iter_files(paths, ext):
    for p in paths:
        p = Path(p)
        if p.is_dir():
            for f in sorted(p.rglob('*')):
                if f.is_file() and (not ext or f.suffix.lower() in ext):
                    yield f
        elif p.is_file():
            yield p


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('inputs', nargs='+', help='مجلد المدوّنة أو ملفات نصية')
    ap.add_argument('-o', '--output', default='tashkeel_dict.txt')
    ap.add_argument('--ext', default='.txt', help='امتدادات الملفات المقروءة، مفصولة بفاصلة (افتراضي .txt)')
    ap.add_argument('--min-count', type=int, default=3, help='أقل تكرار لقبول الكلمة')
    ap.add_argument('--ratio', type=float, default=0.65, help='أقل نسبة اتفاق على شكل واحد (وإلا تُهمل الكلمة الملتبسة)')
    ap.add_argument('--keep-final-min', type=int, default=8, help='أقل تكرار للإبقاء على حركة آخر الكلمة الثابتة')
    ap.add_argument('--max-words', type=int, default=60000, help='أقصى عدد كلمات في القاموس (الأكثر شيوعًا)')
    args = ap.parse_args()

    ext = {e.strip().lower() for e in args.ext.split(',') if e.strip()}
    # المرحلة 1: عدّ الرموز الفريدة فقط (أسرع بكثير ويوفّر الذاكرة من تحليل كل كلمة على حدة)
    raw = Counter()
    files = 0
    for f in iter_files(args.inputs, ext):
        files += 1
        try:
            with open(f, 'r', encoding='utf-8', errors='ignore') as fh:
                for line in fh:
                    toks = TOKEN_RE.findall(line.translate(TRANS))
                    if toks:
                        raw.update(toks)
        except OSError as e:
            print('تعذّرت قراءة', f, e, file=sys.stderr)
        if files % 20 == 0:
            print(f'... {files} ملف، {len(raw):,} رمز فريد', file=sys.stderr)

    # المرحلة 2: تحليل الرموز الفريدة وتجميعها حسب الكلمة المجرّدة
    forms = defaultdict(Counter)
    tokens = 0
    for tok, c in raw.items():
        cl = parse(tok)
        if cl is None:
            continue
        plain = plain_of(cl)
        if not PLAIN_RE.match(plain):
            continue
        # مشكولة تشكيلًا شبه كامل فقط (وإلا نتجاهلها كي لا نتعلّم من نص ناقص)
        if vowel_count(cl) < len(plain) // 2:
            continue
        tokens += c
        forms[plain][render(cl)] += c
    del raw

    if not forms:
        print('لم يُعثر على كلمات مشكولة. تأكد من مسار المدوّنة والامتداد (--ext).', file=sys.stderr)
        sys.exit(1)

    out = []
    dropped_ambiguous = 0
    for plain, cnt in forms.items():
        n = sum(cnt.values())
        if n < args.min_count:
            continue
        stems = Counter()
        by_stem = defaultdict(Counter)
        for full, c in cnt.items():
            cl = parse(full)
            scl, _sig = split_final(cl)
            stem = render(scl)
            stems[stem] += c
            by_stem[stem][full] += c
        stem, sc = stems.most_common(1)[0]
        if sc / n < args.ratio:
            dropped_ambiguous += 1
            continue
        chosen = stem
        top_full, fc = by_stem[stem].most_common(1)[0]
        # حركة الآخر ثابتة دائمًا (مبني: ضمير/فعل ماضٍ/أداة) فنبقيها
        if top_full != stem and sc >= args.keep_final_min and fc / sc >= 0.9:
            chosen = top_full
        # حماية: بعد حذف التشكيل يجب أن نعود لنفس الكلمة تمامًا
        if ''.join(ch for ch in chosen if ch not in KEEP_MARKS) != plain:
            continue
        if chosen == plain:
            continue
        out.append((n, plain, chosen))

    out.sort(key=lambda x: (-x[0], x[1]))
    out = out[:args.max_words]
    Path(args.output).parent.mkdir(parents=True, exist_ok=True)
    with open(args.output, 'w', encoding='utf-8', newline='\n') as fh:
        for _n, plain, chosen in sorted(out, key=lambda x: x[1]):
            fh.write(f'{plain}\t{chosen}\n')

    size = Path(args.output).stat().st_size
    print(f'تم: {len(out):,} كلمة في {args.output} ({size/1024/1024:.2f} MB)')
    print(f'ملفات: {files}، كلمات مشكولة: {tokens:,}، كلمات ملتبسة أُهملت: {dropped_ambiguous:,}')


if __name__ == '__main__':
    main()
