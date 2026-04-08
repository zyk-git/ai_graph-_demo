#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
对 outputs/dish_llm_descriptions.json 做清洗与筛选，供后续 embedding / FAISS 使用。

规则：
- 仅保留 ok 为 True 的记录
- text 去首尾空白后非空
- id 必须存在且为整数
- raw_json 若能解析为 JSON 则保留；无法解析则丢弃该条（避免下游误用）
- 按 id 去重，保留首次出现
- 按 id 升序输出
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any


def _project_root() -> Path:
    return Path(__file__).resolve().parent.parent


def clean_record(row: dict[str, Any]) -> dict[str, Any] | None:
    """返回清洗后的单条，或 None 表示丢弃。"""
    if row.get("ok") is not True:
        return None
    rid = row.get("id")
    if rid is None:
        return None
    try:
        rid_int = int(rid)
    except (TypeError, ValueError):
        return None
    text = row.get("text")
    if text is None:
        return None
    if not isinstance(text, str):
        text = str(text)
    text = text.strip()
    if not text:
        return None
    raw = row.get("raw_json")
    if raw is None:
        return None
    if not isinstance(raw, str):
        raw = json.dumps(raw, ensure_ascii=False)
    raw = raw.strip()
    try:
        json.loads(raw)
    except json.JSONDecodeError:
        return None

    out: dict[str, Any] = {
        "id": rid_int,
        "text": text,
        "raw_json": raw,
        "ok": True,
        "error": None,
    }
    return out


def main() -> None:
    root = _project_root()
    ap = argparse.ArgumentParser(description="清洗 dish_llm_descriptions.json")
    ap.add_argument(
        "-i",
        "--input",
        type=Path,
        default=root / "outputs" / "dish_llm_descriptions.json",
        help="输入 JSON 路径",
    )
    ap.add_argument(
        "-o",
        "--output",
        type=Path,
        default=root / "outputs" / "dish_llm_descriptions.cleaned.json",
        help="清洗后完整记录输出路径",
    )
    ap.add_argument(
        "--minimal-output",
        type=Path,
        default=root / "outputs" / "dish_llm_id_text.json",
        help="仅 id + text 的精简 JSON 路径",
    )
    ap.add_argument(
        "--no-minimal",
        action="store_true",
        help="不写入精简 id+text 文件",
    )
    args = ap.parse_args()

    inp = args.input
    if not inp.is_file():
        print(f"找不到输入文件: {inp}", file=sys.stderr)
        sys.exit(1)

    raw_text = inp.read_text(encoding="utf-8")
    try:
        data = json.loads(raw_text)
    except json.JSONDecodeError as e:
        print(f"JSON 解析失败: {e}", file=sys.stderr)
        sys.exit(1)

    if not isinstance(data, list):
        print("根节点必须是 JSON 数组", file=sys.stderr)
        sys.exit(1)

    stats = {
        "total_rows": len(data),
        "skipped_not_dict": 0,
        "skipped_filter": 0,
        "duplicate_ids": 0,
        "kept": 0,
    }

    seen: set[int] = set()
    cleaned: list[dict[str, Any]] = []

    for item in data:
        if not isinstance(item, dict):
            stats["skipped_not_dict"] += 1
            continue
        rec = clean_record(item)
        if rec is None:
            stats["skipped_filter"] += 1
            continue
        rid = rec["id"]
        if rid in seen:
            stats["duplicate_ids"] += 1
            continue
        seen.add(rid)
        cleaned.append(rec)
        stats["kept"] += 1

    cleaned.sort(key=lambda x: int(x["id"]))

    out_path: Path = args.output
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(
        json.dumps(cleaned, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )

    if not args.no_minimal:
        minimal_path = Path(args.minimal_output)
        minimal_path.parent.mkdir(parents=True, exist_ok=True)
        pairs = [{"id": r["id"], "text": r["text"]} for r in cleaned]
        minimal_path.write_text(
            json.dumps(pairs, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        print(f"已写入精简 id+text -> {minimal_path}")

    print(
        "统计:",
        f"输入 {stats['total_rows']} 条",
        f"保留 {stats['kept']} 条",
        f"跳过(非对象) {stats['skipped_not_dict']}",
        f"跳过(未通过筛选) {stats['skipped_filter']}",
        f"重复 id 丢弃 {stats['duplicate_ids']}",
        sep="\n  ",
    )
    print(f"已写入 -> {out_path}")


if __name__ == "__main__":
    main()
