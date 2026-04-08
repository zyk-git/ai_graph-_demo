from __future__ import annotations

import argparse
import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, List

EMBEDDING_BATCH_MAX = 10


def _project_root() -> Path:
    return Path(__file__).resolve().parent.parent


def _scripts_dir() -> Path:
    return Path(__file__).resolve().parent


def _merge_env_file(path: Path, override: bool = True) -> None:
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


def load_env() -> None:
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


def _env_int(name: str, default: int) -> int:
    raw = os.environ.get(name)
    if raw is None or str(raw).strip() == "":
        return default
    try:
        return int(raw)
    except ValueError:
        return default


def _env_bool(name: str, default: bool) -> bool:
    raw = os.environ.get(name)
    if raw is None:
        return default
    val = str(raw).strip().lower()
    if val in {"1", "true", "yes", "y", "on"}:
        return True
    if val in {"0", "false", "no", "n", "off"}:
        return False
    return default


def _env_choice(name: str, choices: set[str], default: str) -> str:
    raw = os.environ.get(name)
    if raw is None:
        return default
    val = str(raw).strip().lower()
    if val in choices:
        return val
    return default


def read_id_text_rows(path: Path, max_rows: int = 0) -> list[dict[str, Any]]:
    if not path.is_file():
        raise FileNotFoundError(f"找不到输入文件: {path}")
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, list):
        raise ValueError("输入 JSON 根节点必须是数组")

    rows: list[dict[str, Any]] = []
    for item in data:
        if not isinstance(item, dict):
            continue
        rid = item.get("id")
        text = item.get("text")
        if rid is None or text is None:
            continue
        try:
            rid_int = int(rid)
        except (TypeError, ValueError):
            continue
        text_str = str(text).strip()
        if not text_str:
            continue
        rows.append({"id": rid_int, "text": text_str})

    rows.sort(key=lambda x: x["id"])
    if max_rows > 0:
        rows = rows[:max_rows]
    if not rows:
        raise ValueError("没有可用数据（请先检查清洗结果）")
    return rows


def make_openai_client(api_base: str | None = None) -> Any:
    from openai import OpenAI

    api_key = os.environ.get("OPENAI_API_KEY")
    if not api_key:
        raise RuntimeError("未找到 OPENAI_API_KEY")

    base = (api_base or os.environ.get("OPENAI_BASE_URL") or "").strip() or None
    kwargs: dict[str, Any] = {"api_key": api_key}
    if base:
        kwargs["base_url"] = base.rstrip("/")
    return OpenAI(**kwargs)


def batched(items: List[Any], batch_size: int) -> list[list[Any]]:
    return [items[i : i + batch_size] for i in range(0, len(items), batch_size)]


def embed_texts(
    client: Any,
    texts: list[str],
    model: str,
    batch_size: int,
) -> list[list[float]]:
    if batch_size > EMBEDDING_BATCH_MAX:
        print(
            f"警告: batch_size={batch_size} 超过服务上限，自动降为 {EMBEDDING_BATCH_MAX}",
            file=sys.stderr,
        )
        batch_size = EMBEDDING_BATCH_MAX

    embeddings: list[list[float]] = []
    total = len(texts)
    done = 0
    for chunk in batched(texts, batch_size):
        resp = client.embeddings.create(model=model, input=chunk)
        # OpenAI 规范：data 顺序与 input 顺序一致
        for row in resp.data:
            embeddings.append(row.embedding)
        done += len(chunk)
        print(f"  embedding 进度: {done}/{total}")

    if len(embeddings) != total:
        raise RuntimeError(f"embedding 数量不匹配: {len(embeddings)} != {total}")
    return embeddings


def main() -> None:
    load_env()

    ap = argparse.ArgumentParser(description="构建菜品 FAISS 索引（DashScope/OpenAI Embedding）")
    root = _project_root()
    ap.add_argument(
        "-i",
        "--input",
        type=Path,
        default=root / "outputs" / "dish_llm_id_text.json",
        help="输入 id+text JSON 路径",
    )
    ap.add_argument(
        "-o",
        "--output-dir",
        type=Path,
        default=root / "outputs" / "faiss_dish_index",
        help="输出目录",
    )
    ap.add_argument(
        "--overwrite",
        action="store_true",
        help="允许覆盖 output-dir 中已存在的索引文件",
    )
    ap.add_argument(
        "--model",
        default=os.environ.get("EMBED_MODEL", "text-embedding-v3"),
        help="Embedding 模型名（默认 text-embedding-v3）",
    )
    ap.add_argument(
        "--api-base",
        default=None,
        help="覆盖 API 根 URL（默认读 OPENAI_BASE_URL）",
    )
    ap.add_argument(
        "--batch-size",
        type=int,
        default=_env_int("FAISS_BATCH_SIZE", 16),
        help="批量 embedding 大小（默认读 FAISS_BATCH_SIZE，缺省 16）",
    )
    ap.add_argument("--max", type=int, default=0, help="仅处理前 N 条，0 表示全部")
    ap.add_argument(
        "--metric",
        choices=["ip", "l2"],
        default=_env_choice("FAISS_METRIC", {"ip", "l2"}, "ip"),
        help="FAISS 距离度量：ip（推荐配合归一化）/ l2（默认读 FAISS_METRIC）",
    )
    ap.add_argument(
        "--normalize",
        action=argparse.BooleanOptionalAction,
        default=_env_bool("FAISS_NORMALIZE", False),
        help="是否对向量做 L2 归一化（默认读 FAISS_NORMALIZE）",
    )
    args = ap.parse_args()

    if args.batch_size <= 0:
        print("--batch-size 必须 > 0", file=sys.stderr)
        sys.exit(1)

    rows = read_id_text_rows(args.input, max_rows=args.max)
    ids = [int(r["id"]) for r in rows]
    texts = [str(r["text"]) for r in rows]

    client = make_openai_client(api_base=args.api_base)
    print(f"开始向量化: rows={len(rows)}, model={args.model}, batch={args.batch_size}")

    emb = embed_texts(client=client, texts=texts, model=args.model, batch_size=args.batch_size)

    try:
        import faiss  # type: ignore
    except ImportError as e:
        raise RuntimeError("未安装 faiss。请安装 CPU 版 faiss（faiss-cpu）。") from e

    try:
        import numpy as np
    except ImportError as e:
        raise RuntimeError("未安装 numpy。请执行: uv add numpy") from e

    vecs = np.asarray(emb, dtype=np.float32)
    if vecs.ndim != 2 or vecs.shape[0] == 0:
        raise RuntimeError(f"向量形状异常: {vecs.shape}")
    dim = int(vecs.shape[1])

    if args.normalize:
        faiss.normalize_L2(vecs)

    if args.metric == "ip":
        index = faiss.IndexFlatIP(dim)
    else:
        index = faiss.IndexFlatL2(dim)
    index.add(vecs)
    built_on = "cpu"

    out_dir: Path = args.output_dir
    index_path = out_dir / "dish.index"
    ids_path = out_dir / "dish_ids.npy"
    meta_path = out_dir / "dish_meta.json"

    # 默认避免覆盖：若目标文件已存在，则自动改写到带时间戳的新目录。
    if not args.overwrite and any(p.exists() for p in (index_path, ids_path, meta_path)):
        ts = datetime.now().strftime("%Y%m%d_%H%M%S")
        out_dir = out_dir.parent / f"{out_dir.name}_{ts}"
        index_path = out_dir / "dish.index"
        ids_path = out_dir / "dish_ids.npy"
        meta_path = out_dir / "dish_meta.json"
        print(
            f"检测到已存在输出，自动切换到新目录避免覆盖: {out_dir}",
            file=sys.stderr,
        )

    out_dir.mkdir(parents=True, exist_ok=True)

    faiss.write_index(index, str(index_path))
    np.save(str(ids_path), np.asarray(ids, dtype=np.int64))

    meta = {
        "created_at": datetime.now(timezone.utc).isoformat(),
        "input_file": str(args.input),
        "count": len(ids),
        "dim": dim,
        "embedding_model": args.model,
        "api_base": (args.api_base or os.environ.get("OPENAI_BASE_URL") or "").strip(),
        "metric": args.metric,
        "normalized": bool(args.normalize),
        "built_on": built_on,
        "use_gpu": False,
        "faiss_index_file": str(index_path),
        "id_mapping_file": str(ids_path),
    }
    meta_path.write_text(json.dumps(meta, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    print("构建完成:")
    print(f"  index -> {index_path}")
    print(f"  ids   -> {ids_path}")
    print(f"  meta  -> {meta_path}")
    print(f"  统计: N={len(ids)}, dim={dim}, metric={args.metric}, normalize={args.normalize}")


if __name__ == "__main__":
    main()
