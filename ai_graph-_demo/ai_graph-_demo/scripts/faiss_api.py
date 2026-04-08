#!/usr/bin/env python3
from __future__ import annotations

"""FAISS 向量检索 HTTP 封装。

启动时一次性加载索引与元数据，对外提供健康检查与检索接口：
- /health: 返回索引状态
- /search: 返回 id + score + text
- /search_ids: 只返回 id 列表
"""

import json
import logging
import os
from pathlib import Path
from typing import Any

import faiss  # type: ignore
import numpy as np
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

try:
    # 作为模块导入时（uvicorn scripts.faiss_api:app）优先使用包内导入。
    from .build_faiss_dish_index import load_env, make_openai_client
except ImportError:
    # 直接运行脚本时（python scripts/faiss_api.py）回退为同目录导入。
    from build_faiss_dish_index import load_env, make_openai_client


def _project_root() -> Path:
    return Path(__file__).resolve().parent.parent


def _default_index_dir() -> Path:
    return _project_root() / "outputs" / "faiss_dish_index"


class SearchRequest(BaseModel):
    """统一检索入参，两个检索接口共用。"""

    query: str = Field(..., min_length=1, description="用户查询文本")
    top_k: int = Field(5, ge=1, le=100, description="返回结果数量")
    model: str | None = Field(
        default=None,
        description="Embedding 模型名；为空时优先取 meta 中模型，再取 EMBED_MODEL",
    )


class SearchItem(BaseModel):
    id: int
    score: float
    text: str | None = None


class SearchResponse(BaseModel):
    query: str
    top_k: int
    metric: str
    normalized: bool
    hits: list[SearchItem]


class SearchIdsResponse(BaseModel):
    query: str
    top_k: int
    metric: str
    normalized: bool
    ids: list[int]


class FaissSearchService:
    def __init__(self, index_dir: Path) -> None:
        # 索引三件套：faiss 索引、id 映射、构建元信息。
        self.index_dir = index_dir
        self.meta_path = index_dir / "dish_meta.json"
        self.index_path = index_dir / "dish.index"
        self.ids_path = index_dir / "dish_ids.npy"

        if not self.meta_path.is_file():
            raise FileNotFoundError(f"找不到 meta 文件: {self.meta_path}")
        if not self.index_path.is_file():
            raise FileNotFoundError(f"找不到索引文件: {self.index_path}")
        if not self.ids_path.is_file():
            raise FileNotFoundError(f"找不到 id 映射文件: {self.ids_path}")

        self.meta: dict[str, Any] = json.loads(self.meta_path.read_text(encoding="utf-8"))
        self.metric = str(self.meta.get("metric", "ip")).lower()
        self.normalized = bool(self.meta.get("normalized", False))
        self.embedding_model = str(
            self.meta.get("embedding_model", os.environ.get("EMBED_MODEL", "text-embedding-v3"))
        )

        # 启动时完成加载，后续请求直接复用内存对象。
        self.index = faiss.read_index(str(self.index_path))
        self.ids = np.load(str(self.ids_path))
        if len(self.ids) != self.index.ntotal:
            raise RuntimeError(
                f"id 数量与索引向量数量不一致: ids={len(self.ids)} vs ntotal={self.index.ntotal}"
            )

        self.id_to_text = self._load_id_to_text()
        # embedding 客户端改为懒加载：避免服务启动时因 API Key 缺失直接失败。
        self._client: Any | None = None

    def _load_id_to_text(self) -> dict[int, str]:
        # input_file 指向构建索引时使用的 id+text 语料文件。
        input_file = str(self.meta.get("input_file", "")).strip()
        if not input_file:
            return {}

        path = Path(input_file)
        if not path.is_file():
            return {}

        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            return {}

        out: dict[int, str] = {}
        if isinstance(data, list):
            for row in data:
                if not isinstance(row, dict):
                    continue
                rid = row.get("id")
                text = row.get("text")
                try:
                    rid_int = int(rid)
                except (TypeError, ValueError):
                    continue
                if text is None:
                    continue
                out[rid_int] = str(text)
        return out

    def embed_query(self, query: str, model: str | None) -> np.ndarray:
        # 查询向量化逻辑保持与建库侧一致，避免召回漂移。
        if self._client is None:
            self._client = make_openai_client(api_base=self.meta.get("api_base") or None)
        use_model = (model or "").strip() or self.embedding_model
        resp = self._client.embeddings.create(model=use_model, input=[query])
        if not resp.data:
            raise RuntimeError("embedding 接口返回空结果")
        vec = np.asarray(resp.data[0].embedding, dtype=np.float32).reshape(1, -1)
        if self.normalized:
            faiss.normalize_L2(vec)
        return vec

    def search(self, query: str, top_k: int, model: str | None) -> list[SearchItem]:
        q = query.strip()
        if not q:
            raise ValueError("query 不能为空")

        vec = self.embed_query(q, model=model)
        # 防御 top_k 大于索引总量的情况。
        k = min(top_k, int(self.index.ntotal))
        if k <= 0:
            return []
        scores, idxs = self.index.search(vec, k)

        items: list[SearchItem] = []
        for score, idx in zip(scores[0], idxs[0]):
            if idx < 0:
                continue
            rid = int(self.ids[idx])
            items.append(
                SearchItem(
                    id=rid,
                    score=float(score),
                    text=self.id_to_text.get(rid),
                )
            )
        return items

    def search_ids(self, query: str, top_k: int, model: str | None) -> list[int]:
        q = query.strip()
        if not q:
            raise ValueError("query 不能为空")

        vec = self.embed_query(q, model=model)
        k = min(top_k, int(self.index.ntotal))
        if k <= 0:
            return []
        # 轻量查询：只取索引命中的 id，便于下游二次查库。
        _, idxs = self.index.search(vec, k)
        out: list[int] = []
        for idx in idxs[0]:
            if idx < 0:
                continue
            out.append(int(self.ids[idx]))
        return out


app = FastAPI(title="Dish FAISS Search API", version="0.1.0")
logger = logging.getLogger(__name__)

# 进程启动时只加载环境，service 本身采用可恢复的延迟初始化。
load_env()
_service: FaissSearchService | None = None
_service_init_error: str | None = None


def _build_service() -> FaissSearchService:
    return FaissSearchService(
        index_dir=Path(os.environ.get("FAISS_INDEX_DIR", str(_default_index_dir())))
    )


def _get_service() -> FaissSearchService:
    global _service, _service_init_error
    if _service is not None:
        return _service
    try:
        _service = _build_service()
        _service_init_error = None
        return _service
    except Exception as e:
        _service_init_error = str(e)
        raise


@app.on_event("startup")
def warmup_service() -> None:
    """尽量在启动阶段预热 service；失败时不中断进程，便于 /health 可见。"""
    try:
        _get_service()
    except Exception:
        logger.exception("FAISS service warmup failed")


@app.get("/health")
def health() -> dict[str, Any]:
    """健康检查与运行参数透出。"""
    global _service_init_error
    try:
        s = _get_service()
    except Exception:
        return {
            "ok": False,
            "ready": False,
            "error": _service_init_error or "service init failed",
            "index_dir": os.environ.get("FAISS_INDEX_DIR", str(_default_index_dir())),
        }

    return {
        "ok": True,
        "ready": True,
        "index_dir": str(s.index_dir),
        "index_total": int(s.index.ntotal),
        "metric": s.metric,
        "normalized": s.normalized,
        "embedding_model": s.embedding_model,
    }


@app.post("/search", response_model=SearchResponse)
def search(req: SearchRequest) -> SearchResponse:
    """完整检索：返回相似度分数与文本内容。"""
    try:
        s = _get_service()
        hits = s.search(query=req.query, top_k=req.top_k, model=req.model)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e)) from e
    except (FileNotFoundError, RuntimeError):
        raise HTTPException(status_code=503, detail="检索服务未就绪") from None
    except Exception as e:
        logger.exception("search endpoint failed")
        raise HTTPException(status_code=500, detail="检索服务内部错误") from e
    return SearchResponse(
        query=req.query,
        top_k=req.top_k,
        metric=s.metric,
        normalized=s.normalized,
        hits=hits,
    )


@app.post("/search_ids", response_model=SearchIdsResponse)
def search_ids(req: SearchRequest) -> SearchIdsResponse:
    """轻量检索：只返回 id 列表，适合服务间调用。"""
    try:
        s = _get_service()
        ids = s.search_ids(query=req.query, top_k=req.top_k, model=req.model)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e)) from e
    except (FileNotFoundError, RuntimeError):
        raise HTTPException(status_code=503, detail="检索服务未就绪") from None
    except Exception as e:
        logger.exception("search_ids endpoint failed")
        raise HTTPException(status_code=500, detail="检索服务内部错误") from e
    return SearchIdsResponse(
        query=req.query,
        top_k=req.top_k,
        metric=s.metric,
        normalized=s.normalized,
        ids=ids,
    )


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(
        app,
        host=os.environ.get("FAISS_API_HOST", "0.0.0.0"),
        port=int(os.environ.get("FAISS_API_PORT", "8000")),
        reload=False,
    )
