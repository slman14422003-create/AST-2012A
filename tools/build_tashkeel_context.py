#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
يبني "جدول السياق" الذي يجعل القارئ يفهم الجملة قبل أن يشكّل الكلمة (يكمّل tools/build_tashkeel_dict.py).

المشكلة: القاموس العادي يحفظ لكل كلمة شكلًا واحدًا (الأشيع). فكلمة مثل "كتب" تكون كَتَبَ بعد "ثم" وكُتُب بعد "في"،
والقاموس إما يفرض أحدهما دائمًا أو يُهمل الكلمة. هذه الأداة تقرأ نفس المدوّنة المشكولة وتحفظ، للكلمات الملتبسة فقط،
أي شكل يغلب مع الكلمة السابقة ومع الكلمة التالية.

الاستخدام (مرة واحدة على الكمبيوتر، بعد parquet_to_txt.py إن لزم):
    # 1) قياس الفائدة أولًا: يحجب 5% من الأسطر ويقيس الدقة بالقاموس وحده مقابل القاموس + السياق
    python3 tools/build_tashkeel_context.py مجلد_المدوّنة --holdout 20 -o /tmp/ctx_test.txt
    # 2) البناء النهائي من كل المدوّنة
    python3 tools/build_tashkeel_context.py مجلد_المدوّنة -o app/src/main/assets/tashkeel_ctx.txt

الناتج: ملف نصي، كل سطر:   P <TAB> السابقة <TAB> الكلمة <TAB> الشكل <TAB> التكرار
                          N <TAB> الكلمة <TAB> التالية <TAB> الشكل <TAB> التكرار
(السابقة "^" أول الجملة، والتالية "$" آخرها.) الملف اختياري؛ لو غاب يعمل التطبيق كما كان.
"""
import argparse
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_tashkeel_dict as B  # نفس التحليل والتطبيع المستعمل في القاموس

SENT_BREAK = re.compile(r'[.!?؟،؛:\n\r«»()\[\]{}"“”…—–-]')
TOK = re.compile('[\u0621-\u064A\u064B-\u0652\u0670]+')
AL_MARKS = set('\u064B\u064C\u064D\u064E\u064F\u0650\u0652')


def clean_article(cl):
    """ٱلْعِلْم / اَلْعِلْم -> الْعِلْم: همزة الوصل بلا علامة، كما يكتبها قاموس التطبيق."""
    if len(cl) >= 3 and cl[0][0] == '\u0627' and cl[1][0] == '\u0644':
        cl = [('\u0627', [])] + cl[1:]
    return cl


def stem_of(tok):
    """(الكلمة المجرّدة، الشكل بلا حركة الآخر، الشكل الكامل) أو None لو الكلمة غير صالحة للتعلّم."""
    cl = B.parse(tok)
    if cl is None:
        return None
    plain = B.plain_of(cl)
    if not B.PLAIN_RE.match(plain):
        return None
    if B.vowel_count(cl) < len(plain) // 2:   # لا نتعلّم من نص ناقص التشكيل
        return None
    cl = clean_article(cl)
    scl, _sig = B.split_final(cl)
    return plain, B.render(scl), B.render(cl)


def sentences(path, keep_line):
    """يولّد قوائم رموز كل جملة: [(مجرّدة، شكل أو None), ...] مع حدود الجمل عند الترقيم."""
    with open(path, 'r', encoding='utf-8', errors='ignore') as fh:
        for ln, line in enumerate(fh):
            if not keep_line(ln):
                continue
            line = line.translate(B.TRANS)
            sent = []
            last = 0
            for m in TOK.finditer(line):
                if SENT_BREAK.search(line[last:m.start()]) and sent:
                    yield sent
                    sent = []
                last = m.end()
                st = stem_of(m.group())
                if st is None:
                    # كلمة غير مشكولة أو فاسدة: نحفظ مجرّدها فقط ليبقى السياق صحيحًا
                    cl = B.parse(m.group())
                    plain = B.plain_of(cl) if cl else ''.join(c for c in m.group() if '\u0621' <= c <= '\u064A')
                    sent.append((plain, None, None))
                else:
                    sent.append(st)
            if sent:
                yield sent


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('inputs', nargs='+')
    ap.add_argument('-o', '--output', default='tashkeel_ctx.txt')
    ap.add_argument('--ext', default='.txt')
    ap.add_argument('--min-count', type=int, default=3, help='أقل تكرار لقبول الكلمة في القاموس (كما في الأداة الأولى)')
    ap.add_argument('--ratio', type=float, default=0.65, help='نسبة اتفاق القاموس (كما في الأداة الأولى)')
    ap.add_argument('--amb-min', type=int, default=30, help='أقل تكرار لاعتبار الكلمة ملتبسة تستحق سياقًا')
    ap.add_argument('--amb-share', type=float, default=0.15, help='أقل حصة للشكل الثاني كي تُعدّ الكلمة ملتبسة')
    ap.add_argument('--min-ctx', type=int, default=4, help='أقل تكرار لسياق كي يُحفظ')
    ap.add_argument('--ctx-ratio', type=float, default=0.8, help='أقل نسبة اتفاق على شكل واحد داخل السياق')
    ap.add_argument('--max-entries', type=int, default=250000)
    ap.add_argument('--holdout', type=int, default=0,
                    help='N>0: يحجب سطرًا من كل N للاختبار ويطبع الدقة (والناتج يُبنى من الباقي فقط)')
    args = ap.parse_args()

    ext = {e.strip().lower() for e in args.ext.split(',') if e.strip()}
    files = list(B.iter_files(args.inputs, ext))
    if not files:
        sys.exit('لا ملفات. تأكد من المسار و--ext')

    def is_test(ln):
        return args.holdout > 0 and ln % args.holdout == 0

    def train_line(ln):
        return not is_test(ln)

    # المرحلة 1: أشكال كل كلمة (تحديد الكلمات الملتبسة + شكل القاموس العام)
    word_forms = defaultdict(Counter)
    for f in files:
        for sent in sentences(f, train_line):
            for plain, stem, _full in sent:
                if stem is not None:
                    word_forms[plain][stem] += 1
    dict_form = {}
    ambiguous = set()
    for plain, cnt in word_forms.items():
        n = sum(cnt.values())
        top, tc = cnt.most_common(1)[0]
        if n >= args.min_count and tc / n >= args.ratio:
            dict_form[plain] = top
        if n >= args.amb_min and len(cnt) >= 2 and (n - tc) / n >= args.amb_share:
            ambiguous.add(plain)
    print(f'كلمات: {len(word_forms):,}، في القاموس: {len(dict_form):,}، ملتبسة تستحق سياقًا: {len(ambiguous):,}',
          file=sys.stderr)
    if not ambiguous:
        sys.exit('لا كلمات ملتبسة بما يكفي. جرّب خفض --amb-min أو --amb-share')

    # المرحلة 2: إحصاء السياق للكلمات الملتبسة فقط (يوفّر الذاكرة)
    prev_c = defaultdict(Counter)
    next_c = defaultdict(Counter)
    prev_f = defaultdict(Counter)   # (سابقة، كلمة، شكل بلا آخر) -> الأشكال الكاملة
    next_f = defaultdict(Counter)
    tests = []
    for f in files:
        with open(f, 'r', encoding='utf-8', errors='ignore') as fh:
            lines = fh.readlines()
        for sent_ln, sent in _sentences_with_line(lines):
            test_side = is_test(sent_ln)
            for i, (plain, stem, full) in enumerate(sent):
                if plain not in ambiguous or stem is None:
                    continue
                prv = sent[i - 1][0] if i > 0 else '^'
                nxt = sent[i + 1][0] if i + 1 < len(sent) else '$'
                if test_side:
                    if len(tests) < 400000:
                        tests.append((prv, plain, nxt, stem))
                else:
                    prev_c[(prv, plain)][stem] += 1
                    next_c[(plain, nxt)][stem] += 1
                    prev_f[(prv, plain, stem)][full] += 1
                    next_f[(plain, nxt, stem)][full] += 1

    def pick(cnt, fulls):
        n = sum(cnt.values())
        stem, c = cnt.most_common(1)[0]
        if c < args.min_ctx or c / n < args.ctx_ratio:
            return None
        # حركة الآخر ثابتة في هذا السياق (فعل ماضٍ مثلًا) فنبقيها، وإلا تُحذف ويبقى الوقف
        form = stem
        top_full, fc = fulls[stem].most_common(1)[0] if fulls.get(stem) else (stem, 0)
        if top_full != stem and fc / c >= 0.9:
            form = top_full
        return stem, form, c

    entries = []
    pmap = {}
    nmap = {}
    for (prv, plain), cnt in prev_c.items():
        r = pick(cnt, {st: prev_f[(prv, plain, st)] for st in cnt})
        if r is None:
            continue
        stem, form, c = r
        pmap[(prv, plain)] = (stem, c)
        if dict_form.get(plain) != stem:
            entries.append((c, 'P', prv, plain, form))
    for (plain, nxt), cnt in next_c.items():
        r = pick(cnt, {st: next_f[(plain, nxt, st)] for st in cnt})
        if r is None:
            continue
        stem, form, c = r
        nmap[(plain, nxt)] = (stem, c)
        if dict_form.get(plain) != stem:
            entries.append((c, 'N', plain, nxt, form))

    # قياس الفائدة على الأسطر المحجوبة: نفس منطق التطبيق (الأعلى تكرارًا بين السابقة والتالية، وإلا شكل القاموس)
    if tests:
        base_ok = ctx_ok = used = used_ok = used_base_ok = 0
        for prv, plain, nxt, gold in tests:
            g = dict_form.get(plain)
            base_ok += (g == gold)
            cands = [x for x in (pmap.get((prv, plain)), nmap.get((plain, nxt))) if x]
            if cands:
                guess = max(cands, key=lambda x: x[1])[0]
                used += 1
                used_ok += (guess == gold)
                used_base_ok += (g == gold)
            else:
                guess = g
            ctx_ok += (guess == gold)
        n = len(tests)
        print(f'\nاختبار على {n:,} كلمة ملتبسة من الأسطر المحجوبة:', file=sys.stderr)
        print(f'  القاموس وحده:        {100 * base_ok / n:.1f}%', file=sys.stderr)
        print(f'  القاموس + السياق:   {100 * ctx_ok / n:.1f}%', file=sys.stderr)
        if used:
            print(f'  حيث وُجد سياق ({100 * used / n:.1f}% من الكلمات): السياق {100 * used_ok / used:.1f}% '
                  f'مقابل القاموس {100 * used_base_ok / used:.1f}%', file=sys.stderr)

    entries.sort(key=lambda e: (-e[0], e[1], e[2], e[3]))
    entries = entries[:args.max_entries]
    Path(args.output).parent.mkdir(parents=True, exist_ok=True)
    with open(args.output, 'w', encoding='utf-8', newline='\n') as fh:
        for c, kind, a, b, stem in sorted(entries, key=lambda e: (e[1], e[2], e[3])):
            fh.write(f'{kind}\t{a}\t{b}\t{stem}\t{c}\n')
    size = Path(args.output).stat().st_size
    print(f'\nتم: {len(entries):,} سياق في {args.output} ({size / 1024 / 1024:.2f} MB)')


def _sentences_with_line(lines):
    """مثل sentences لكن من أسطر محمّلة، مع رقم السطر لتقسيم الاختبار."""
    for ln, line in enumerate(lines):
        line = line.translate(B.TRANS)
        sent = []
        last = 0
        for m in TOK.finditer(line):
            if SENT_BREAK.search(line[last:m.start()]) and sent:
                yield ln, sent
                sent = []
            last = m.end()
            st = stem_of(m.group())
            if st is None:
                cl = B.parse(m.group())
                plain = B.plain_of(cl) if cl else ''.join(c for c in m.group() if '\u0621' <= c <= '\u064A')
                sent.append((plain, None, None))
            else:
                sent.append(st)
        if sent:
            yield ln, sent


if __name__ == '__main__':
    main()
