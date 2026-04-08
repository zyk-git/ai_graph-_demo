#!/usr/bin/env python3
# -*- coding: utf-8 -*-
from __future__ import annotations

import argparse
import json
import os
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from typing import Any


def _project_root() -> Path:
    """scripts/dish_corpus_llm_chroma.py -> 上级目录 ai_graph-_demo。"""
    return Path(__file__).resolve().parent.parent


def _scripts_dir() -> Path:
    return Path(__file__).resolve().parent


def _merge_env_file(path: Path, override: bool = True) -> None:
    """
    逐行解析 .env（兼容 export、引号、UTF-8 BOM）。
    作为 python-dotenv 的补充：当 dotenv 报「could not parse」时仍能读到 KEY。
    """
    try:
        text = path.read_text(encoding="utf-8-sig")
    except OSError:
        return
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            line = line[7:].strip()
        if "=" not in line:
            continue
        key, _, val = line.partition("=")
        key = key.strip()
        if not key:
            continue
        val = val.strip()
        if len(val) >= 2 and val[0] == val[-1] and val[0] in "\"'":
            val = val[1:-1]
        if override:
            os.environ[key] = val
        else:
            os.environ.setdefault(key, val)


def load_env(verbose: bool = False) -> list[Path]:
    """
    按顺序加载（后者覆盖前者），保证「项目根 .env」与「scripts/.env」均可生效。
    你在 scripts/.env 里写 OPENAI_API_KEY 时，从项目根执行 uv run 也能读到。
    """
    loaded: list[Path] = []
    try:
        from dotenv import load_dotenv
    except ImportError:
        load_dotenv = None  # type: ignore

    root = _project_root()
    scripts = _scripts_dir()
    candidates = [
        root / ".env",
        root / ".env.local",
        scripts / ".env",
        scripts / ".env.local",
        Path.cwd() / ".env",
        Path.cwd() / ".env.local",
    ]
    seen: set[Path] = set()
    for p in candidates:
        try:
            rp = p.resolve()
        except OSError:
            continue
        if rp in seen:
            continue
        seen.add(rp)
        if not p.is_file():
            continue
        if load_dotenv is not None:
            try:
                load_dotenv(p, override=True)
            except OSError:
                pass
        _merge_env_file(p, override=True)
        loaded.append(p)

    if verbose and loaded:
        print("已加载 .env 文件:", *[str(x) for x in loaded], sep="\n  ")
    return loaded

# -----------------------------------------------------------------------------
# 1) 语料解析：每行包一层 {} 即为合法 JSON
# -----------------------------------------------------------------------------


def line_to_obj(line: str) -> dict[str, Any] | None:
    line = line.strip()
    if not line:
        return None
    try:
        return json.loads("{" + line + "}")
    except json.JSONDecodeError:
        return None


# -----------------------------------------------------------------------------
# 2) Prompt（须在正文中体现 id，便于对齐 SQL；示例风格见 SYSTEM）
# -----------------------------------------------------------------------------

SYSTEM_PROMPT = """你是美食文案助手。用户会提供一道菜的 JSON（字段含 name、category、cuisine、main_ingredient、taste、allergen、seasonal、custom_options、calories、id 等）。

请把 JSON 改写成一段连贯、生动的中文短文（3～5 句），用于向量检索。要求：
1. 必须包含原始 id，且使用方括号格式：[ID: 数字]（与 JSON 中的 id 完全一致）。
2. 自然融入菜系、类别、主料、口味、季节、热量、过敏原、可定制选项等关键信息，不要简单罗列字段名。
3. 不要输出 Markdown、不要 JSON、不要项目符号列表。
4. 风格参考（仅参考语气，勿照抄）：「这道 [ID: 1] 的红烧鸡是一道经典的苏菜热菜。它以鸡肉为主料，口感咸鲜适中，非常适合在春夏时节享用。每份菜品的热量大约为 557 卡路里，且不含常见过敏原。」"""


USER_TEMPLATE = """请将下列 JSON 转为一段美食描述（正文中必须出现 [ID: {id}]）：\n\n{payload}"""


# -----------------------------------------------------------------------------
# 3) OpenAI Chat Completions（官方接口）
# -----------------------------------------------------------------------------


def get_openai_client(api_base: str | None = None) -> Any:
    """构造 OpenAI 同步客户端；未指定 base 时使用官方 api.openai.com。"""
    from openai import OpenAI

    api_key = os.environ.get("OPENAI_API_KEY")
    if not api_key:
        root_env = _project_root() / ".env"
        scripts_env = _scripts_dir() / ".env"
        print(
            "未找到 OPENAI_API_KEY。请任选其一：\n"
            "  1) export OPENAI_API_KEY=sk-...\n"
            f"  2) 推荐在本脚本同目录创建 .env：\n"
            f"     {scripts_env}\n"
            f"     写入一行：OPENAI_API_KEY=sk-...（可复制同目录 .env.example）\n"
            f"     也可使用项目根：{root_env}",
            file=sys.stderr,
        )
        sys.exit(1)
    base = (api_base or os.environ.get("OPENAI_BASE_URL") or "").strip() or None
    org = os.environ.get("OPENAI_ORG_ID") or None
    kwargs: dict[str, Any] = {"api_key": api_key}
    if base:
        kwargs["base_url"] = base.rstrip("/")
    if org:
        kwargs["organization"] = org
    return OpenAI(**kwargs)


def generate_one(
    client: Any,
    model: str,
    row: dict[str, Any],
    temperature: float = 0.6,
) -> str:
    """单次 chat.completions 调用（OpenAI 接口）。"""
    rid = row.get("id")
    payload = json.dumps(row, ensure_ascii=False, indent=2)
    user = USER_TEMPLATE.format(id=rid, payload=payload)
    # https://platform.openai.com/docs/api-reference/chat/create
    resp = client.chat.completions.create(
        model=model,
        messages=[
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": user},
        ],
        temperature=temperature,
    )
    text = (resp.choices[0].message.content or "").strip()
    if not text:
        raise RuntimeError("模型返回空文本")
    # 兜底：若模型漏写 [ID: x]，在段首补上
    tag = f"[ID: {rid}]"
    if tag not in text and f"[ID:{rid}]" not in text:
        text = f"{tag} {text}"
    return text


# -----------------------------------------------------------------------------
# 4) 并发 worker
# -----------------------------------------------------------------------------


def _worker(args: tuple[Any, str, dict[str, Any]]) -> dict[str, Any]:
    client, model, row = args
    rid = int(row["id"])
    try:
        text = generate_one(client, model, row)
        raw_json = json.dumps(row, ensure_ascii=False)
        return {
            "id": rid,
            "text": text,
            "raw_json": raw_json,
            "ok": True,
            "error": None,
        }
    except Exception as e:
        return {
            "id": rid,
            "text": "",
            "raw_json": json.dumps(row, ensure_ascii=False),
            "ok": False,
            "error": str(e),
        }


# -----------------------------------------------------------------------------
# 5) ChromaDB 演示
# -----------------------------------------------------------------------------


def demo_chroma_store(
    results: list[dict[str, Any]],
    chroma_path: Path,
    collection_name: str = "dishes",
) -> None:
    import chromadb

    ok_rows = [r for r in results if r.get("ok")]
    if not ok_rows:
        print("无成功记录，跳过 Chroma 写入。")
        return

    client = chromadb.PersistentClient(path=str(chroma_path))
    col = client.get_or_create_collection(
        name=collection_name,
        metadata={"description": "菜品自然语言描述"},
    )

    ids = [str(r["id"]) for r in ok_rows]
    documents = [r["text"] for r in ok_rows]
    metadatas = [
        {"dish_id": r["id"], "source": "dish_corpus.txt"} for r in ok_rows
    ]

    col.upsert(ids=ids, documents=documents, metadatas=metadatas)
    print(
        f"ChromaDB：已写入 {len(ids)} 条到 path={chroma_path} collection={collection_name}"
    )


def chroma_query_demo(chroma_path: Path, collection_name: str = "dishes") -> None:
    import chromadb

    client = chromadb.PersistentClient(path=str(chroma_path))
    col = client.get_collection(collection_name)
    n = col.count()
    print(f"Chroma 集合 {collection_name} 当前文档数: {n}")
    if n == 0:
        return
    # 内置 embedding 查询示例（需已写入）
    out = col.query(query_texts=["苏菜 鸡肉 咸鲜"], n_results=min(3, n))
    print("query 示例（前 3 条 id）:", out.get("ids"))


# -----------------------------------------------------------------------------
# main
# -----------------------------------------------------------------------------


def main() -> None:
    # 必须先加载 .env，再解析参数：否则 --model 的 default 读不到 LLM_MODEL。
    load_env(verbose=False)

    ap = argparse.ArgumentParser(description="语料 -> LLM 描述 -> 列表 + Chroma 演示")
    ap.add_argument(
        "--corpus",
        type=Path,
        default=None,
        help="dish_corpus.txt 路径",
    )
    ap.add_argument("--max", type=int, default=0, help="只处理前 N 行，0 表示全部")
    ap.add_argument("--workers", type=int, default=6, help="并发线程数")
    ap.add_argument(
        "--model",
        default=os.environ.get("LLM_MODEL", "qwen-max"),
        help="模型 id：默认通义千问 Max（qwen-max）；可通过 LLM_MODEL 或 --model 指定其他模型",
    )
    ap.add_argument(
        "--api-base",
        default=None,
        help="覆盖 API 根 URL；默认使用官方，或读环境变量 OPENAI_BASE_URL",
    )
    ap.add_argument(
        "--chroma-path",
        type=Path,
        default=None,
        help="Chroma 持久化目录，默认项目下 chroma_dish_db",
    )
    ap.add_argument("--no-chroma", action="store_true", help="不写入 Chroma，仅打印/保存 JSON")
    ap.add_argument(
        "-o",
        "--output-json",
        type=Path,
        default=None,
        help="结果 JSON 路径；默认写入项目根下 outputs/dish_llm_descriptions.json",
    )
    ap.add_argument(
        "--no-json",
        action="store_true",
        help="不写入结果 JSON（仅内存/Chroma）",
    )
    ap.add_argument("--sleep", type=float, default=0.0, help="每条成功后额外休眠秒数（限流）")
    ap.add_argument(
        "-v",
        "--verbose",
        action="store_true",
        help="打印已加载的 .env 路径（不含密钥）",
    )
    args = ap.parse_args()

    if args.verbose:
        load_env(verbose=True)

    root = Path(__file__).resolve().parents[1]
    corpus = args.corpus or (root / "dish_corpus.txt")
    if not corpus.is_file():
        print(f"找不到语料: {corpus}", file=sys.stderr)
        sys.exit(1)

    chroma_path = args.chroma_path or (root / "chroma_dish_db")

    lines = corpus.read_text(encoding="utf-8").splitlines()
    rows: list[dict[str, Any]] = []
    for line in lines:
        obj = line_to_obj(line)
        if obj and "id" in obj and "name" in obj:
            rows.append(obj)
    rows.sort(key=lambda x: int(x["id"]))
    if args.max > 0:
        rows = rows[: args.max]

    if not rows:
        print("没有可处理的语料行（检查 dish_corpus.txt）", file=sys.stderr)
        sys.exit(1)

    client = get_openai_client(api_base=args.api_base)
    model = args.model
    base = (args.api_base or os.environ.get("OPENAI_BASE_URL") or "").strip()
    api_host = "api.openai.com（默认）" if not base else base[:48] + ("…" if len(base) > 48 else "")

    work_items = [(client, model, r) for r in rows]
    results: list[dict[str, Any]] = []

    print(
        f"共 {len(rows)} 条，并发 {args.workers}，模型 {model}，API {api_host}"
    )
    t0 = time.perf_counter()
    with ThreadPoolExecutor(max_workers=args.workers) as ex:
        futs = [ex.submit(_worker, item) for item in work_items]
        for fut in as_completed(futs):
            r = fut.result()
            results.append(r)
            if r["ok"]:
                print(f"  OK id={r['id']}")
            else:
                print(f"  FAIL id={r['id']}: {r['error']}", file=sys.stderr)
            if args.sleep > 0:
                time.sleep(args.sleep)
    results.sort(key=lambda x: x["id"])
    elapsed = time.perf_counter() - t0
    print(f"完成，耗时 {elapsed:.1f}s，成功 {sum(1 for x in results if x['ok'])}/{len(results)}")

    if not args.no_json:
        out_json = args.output_json or (root / "outputs" / "dish_llm_descriptions.json")
        out_json.parent.mkdir(parents=True, exist_ok=True)
        out_json.write_text(
            json.dumps(results, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        print(f"已保存列表 -> {out_json}")

    if not args.no_chroma:
        demo_chroma_store(results, chroma_path)
        chroma_query_demo(chroma_path)

    # 演示：内存中的列表结构
    demo_list = [
        {"id": r["id"], "text": r["text"], "raw_json": r["raw_json"]}
        for r in results
        if r["ok"]
    ]
    print(f"\n内存列表 demo_list 长度: {len(demo_list)}，首条 text 前 120 字：")
    if demo_list:
        print(demo_list[0]["text"][:120] + "…")


if __name__ == "__main__":
    main()
