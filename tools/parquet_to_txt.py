#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""يحوّل ملفات مدوّنة Tashkeela بصيغة parquet (من Hugging Face) إلى ملفات .txt عادية.
الاستخدام: python3 tools/parquet_to_txt.py مجلد_parquet مجلد_الإخراج
"""
import sys
from pathlib import Path

import pyarrow as pa
import pyarrow.parquet as pq


def pick_text_column(schema):
    names = [f.name for f in schema if pa.types.is_string(f.type) or pa.types.is_large_string(f.type)]
    if not names:
        raise SystemExit('لا يوجد عمود نصي في الملف: ' + str(schema))
    return 'text' if 'text' in names else names[0]


def main():
    src, dst = Path(sys.argv[1]), Path(sys.argv[2])
    dst.mkdir(parents=True, exist_ok=True)
    n = 0
    for f in sorted(src.glob('*.parquet')):
        pf = pq.ParquetFile(f)
        col = pick_text_column(pf.schema_arrow)
        print(f'{f.name}: العمود={col} الصفوف={pf.metadata.num_rows}', flush=True)
        for batch in pf.iter_batches(batch_size=1, columns=[col]):
            for text in batch.column(0).to_pylist():
                if not text:
                    continue
                n += 1
                (dst / f'book_{n:04d}.txt').write_text(text, encoding='utf-8')
    print(f'تم: {n} كتاب في {dst}')
    if n == 0:
        raise SystemExit('لم يُستخرج أي نص')


if __name__ == '__main__':
    main()
