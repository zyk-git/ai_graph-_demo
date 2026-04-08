#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Convert dish_corpus.txt lines to dish INSERT SQL matching schema in dish_schema.sql."""
import hashlib
import re
import sys
from pathlib import Path

# Map corpus cuisine to schema: 川/粤/鲁/苏/浙/闽/湘/徽
CUISINE_MAP = {
    "川": "川",
    "粤": "粤",
    "鲁": "鲁",
    "苏": "苏",
    "浙": "浙",
    "闽": "闽",
    "湘": "湘",
    "徽": "徽",
    "京": "鲁",
    "东北": "鲁",
    "家常": "川",
    "西北": "鲁",
    "滇": "川",
    "黔": "川",
    "晋": "鲁",
    "豫": "鲁",
    "津": "鲁",
    "本帮": "浙",
}

# Map category to schema (凉菜/热菜/主食/汤品)
def map_category(cat: str) -> str:
    if cat in ("凉菜", "热菜", "主食", "汤品"):
        return cat
    if cat in ("甜品", "小吃"):
        return "热菜"
    return "热菜"

# 表字段 allergen：蛋奶/海鲜/坚果/无 —— 语料里可能写「无」但主料含鱼虾，需结合菜名/主料/custom 推断

def map_allergen_from_corpus(a: str) -> str | None:
    """仅根据语料 allergen 字段映射；无法识别则返回 None（交给推断）。"""
    if not a or a == "无":
        return None
    if "海鲜" in a or "鱼" in a or "虾" in a or "蟹" in a or "贝" in a or "蚝" in a or "蛏" in a or "螺" in a or "鱿" in a or "参" in a or "鲍" in a:
        return "海鲜"
    if "坚果" in a or "花生" in a or "腰果" in a or "杏仁" in a or "核桃" in a:
        return "坚果"
    if "蛋" in a or "奶" in a or "乳制品" in a or "芝士" in a:
        return "蛋奶"
    if "麸质" in a or "大豆" in a or "芝麻" in a or "菌菇" in a:
        return "无"
    return None


# 主料中的海鲜关键词（语料标「无」时仍应标海鲜）；不用单字「鱼」避免误判「鱼香肉丝」等
_SEAFOOD_IN_MAIN = (
    "虾", "虾仁", "蟹", "鲈", "带", "鱿", "鲍", "参", "蚝", "蛏", "蛤", "螺", "鳝", "贝", "蚌",
    "三文鱼", "鳕鱼", "黄鱼", "鲫鱼", "鲤鱼", "草鱼", "青鱼", "黑鱼", "鱼片", "鱼块", "鱼头",
    "墨鱼", "海参", "海螺", "扇贝", "蛤蜊", "生蚝", "鱿鱼", "章鱼", "泥鳅", "鲷", "金枪",
)
# 菜名中的海鲜词（避免「鱼香」类）
_SEAFOOD_IN_NAME = (
    "鲈鱼", "草鱼", "鲤鱼", "鲫鱼", "带鱼", "黄鱼", "鳕鱼", "三文鱼", "虾仁", "基围虾", "小龙虾",
    "螃蟹", "扇贝", "生蚝", "蛤蜊", "海螺", "蛏子", "鱿鱼", "墨鱼", "海参", "鲍鱼", "鳝鱼", "鱼片",
)

# 蛋奶类菜名/搭配（避免误判「皮蛋」「蛋糕」）
_EGG_DAIRY_PHRASES = (
    "鸡蛋", "蛋挞", "蛋卷", "蛋花", "蛋羹", "蛋炒", "炒蛋", "蛋包", "蛋饺", "蛋面", "蛋炒饭",
    "双皮奶", "奶黄", "芝士", "奶油", "牛奶", "酸奶", "奶酪", "姜撞奶", "布丁",
)


def infer_allergen(
    name: str,
    main: str,
    raw_allergen: str,
    custom_options: str,
    taste: str = "",
) -> str:
    """综合语料字段 + 菜名/主料/custom_options 推断，输出 蛋奶/海鲜/坚果/无。"""
    from_corpus = map_allergen_from_corpus(raw_allergen)
    if from_corpus:
        return from_corpus

    text = f"{name}{main}{custom_options or ''}"

    # 1) 海鲜：主料优先；菜名仅匹配明确海产词
    if "鱼香" not in name:
        for kw in _SEAFOOD_IN_MAIN:
            if kw in main:
                return "海鲜"
        for kw in _SEAFOOD_IN_NAME:
            if kw in name:
                return "海鲜"

    # 2) 坚果：宫保系、果仁、定制里「免花生」说明含花生
    if any(x in text for x in ("宫保", "花生", "腰果", "杏仁", "核桃", "松仁", "胡桃仁", "坚果")):
        return "坚果"
    if custom_options and ("免花生" in custom_options or "免坚果" in custom_options):
        return "坚果"

    # 3) 蛋奶：菜名、主料（如鸡蛋）、定制「免蛋」
    if custom_options and "免蛋" in custom_options:
        return "蛋奶"
    for phrase in _EGG_DAIRY_PHRASES:
        if phrase in name or phrase in main:
            return "蛋奶"
    if "蛋" in main and not any(bad in main for bad in ("皮蛋", "蛋糕")):
        return "蛋奶"
    if "蛋" in name:
        if any(bad in name for bad in ("皮蛋", "蛋糕", "坏蛋")):
            pass
        else:
            return "蛋奶"
    if any(x in name for x in ("牛奶", "酸奶", "芝士", "奶油", "双皮奶", "姜撞奶")):
        return "蛋奶"

    # 口味「奶香」在出品中常使用奶粉/黄油/炼乳等，标为蛋奶便于过敏提示（非海鲜/坚果已排除）
    if taste and "奶香" in taste:
        return "蛋奶"

    return "无"

METHOD_PREFIXES = [
    ("红烧", "炖、烧"),
    ("清蒸", "蒸"),
    ("干煸", "炒"),
    ("爆炒", "炒"),
    ("油焖", "焖"),
    ("酱爆", "炒"),
    ("滑炒", "炒"),
    ("炖", "炖"),
    ("焖", "焖"),
    ("炸", "炸"),
    ("烤", "烤"),
    ("煎", "煎"),
    ("白灼", "煮"),
    ("卤", "卤"),
    ("凉拌", "拌"),
    ("糖醋", "糖醋"),
    ("蒜蓉", "蒸、炒"),
]


def infer_cooking_method(name: str) -> str:
    for prefix, method in METHOD_PREFIXES:
        if name.startswith(prefix):
            return method
    return "炒"


def sql_escape(s: str) -> str:
    return s.replace("\\", "\\\\").replace("'", "''")


def stable_int(seed: str, lo: int, hi: int) -> int:
    h = int(hashlib.md5(seed.encode("utf-8")).hexdigest()[:8], 16)
    return lo + (h % (hi - lo + 1))


def extras_for_row(name: str, main_ing: str, taste: str, calories: int, rid: int) -> dict:
    seed = f"{rid}:{name}:{main_ing}"
    prep = stable_int(seed + "prep", 12, 95)
    aux_pool = ["姜片、葱段", "蒜末、青椒", "洋葱、干辣椒", "香菜、芝麻", "胡萝卜、木耳", "香菇、笋片"]
    seasoning_pool = ["盐、生抽、料酒", "酱油、糖、醋", "豆瓣酱、花椒", "蚝油、白胡椒粉", "八角、桂皮", "蒜蓉、辣椒油"]
    texture_pool = ["滑嫩", "软糯", "酥脆", "劲道", "鲜嫩", "爽口"]
    color_pool = ["红亮", "金黄", "翠绿", "酱色", "白亮", "油润"]
    aroma_pool = ["酱香浓郁", "蒜香扑鼻", "麻辣鲜香", "清淡鲜香", "酸甜开胃", "葱香四溢"]
    idx = stable_int(seed + "i", 0, len(aux_pool) - 1)
    portion_g = stable_int(seed + "pg", 280, 520)
    cost = round(5 + (calories % 200) / 10 + stable_int(seed + "c", 0, 15), 2)
    price = round(cost * (2.2 + (stable_int(seed + "p", 0, 8) / 10)), 2)
    gross = round(price - cost, 2)
    protein = stable_int(seed + "pr", 8, 35)
    fat = max(5, min(45, int(calories / 40)))
    return {
        "auxiliary_ingredient": aux_pool[idx],
        "seasoning": seasoning_pool[idx],
        "cooking_method": infer_cooking_method(name),
        "prep_time_minutes": prep,
        "temperature_requirement": "中火" if prep < 40 else "中小火",
        "texture": texture_pool[stable_int(seed + "t", 0, len(texture_pool) - 1)],
        "color": color_pool[stable_int(seed + "co", 0, len(color_pool) - 1)],
        "aroma": aroma_pool[stable_int(seed + "a", 0, len(aroma_pool) - 1)],
        "portion_size": f"{portion_g}g",
        "nutrition_info": f"约{protein}g蛋白质，脂肪约{fat}g，口味{taste}",
        "shelf_life": "冷藏2天" if "汤" not in name else "当天食用",
        "storage_condition": "冷藏",
        "ingredient_cost": cost,
        "selling_price": price,
        "gross_profit": gross,
    }


def parse_line(line: str) -> dict | None:
    line = line.strip()
    if not line:
        return None
    d: dict = {}
    for m in re.finditer(r'"([a-zA-Z_]+)":"([^"]*)"', line):
        d[m.group(1)] = m.group(2)
    m_id = re.search(r'"id":(\d+)', line)
    m_cal = re.search(r'"calories":(\d+)', line)
    if m_id:
        d["id"] = int(m_id.group(1))
    if m_cal:
        d["calories"] = int(m_cal.group(1))
    return d if d.get("name") else None


def main():
    root = Path(__file__).resolve().parents[1]
    corpus = root / "dish_corpus.txt"
    out_sql = root / "dish_data.sql"
    if len(sys.argv) > 1:
        corpus = Path(sys.argv[1])

    lines = corpus.read_text(encoding="utf-8").strip().splitlines()
    rows = []
    for line in lines:
        p = parse_line(line)
        if p and "name" in p:
            rows.append(p)

    insert_cols = """id, name, category, cuisine, main_ingredient, auxiliary_ingredient, seasoning, allergen,
                  cooking_method, prep_time_minutes, temperature_requirement,
                  taste, texture, color, aroma,
                  portion_size, calories, nutrition_info, shelf_life, storage_condition,
                  ingredient_cost, selling_price, gross_profit,
                  custom_options, seasonal"""

    buf = []
    buf.append(
        "-- Generated from dish_corpus.txt — allergen: 语料+主料/菜名/custom 推断（蛋奶/海鲜/坚果/无）\n"
    )
    buf.append("SET NAMES utf8mb4;\n")
    buf.append("SET FOREIGN_KEY_CHECKS = 0;\n")
    buf.append("TRUNCATE TABLE dish;\n")
    buf.append("INSERT INTO dish (")
    buf.append(insert_cols)
    buf.append(") VALUES\n")

    value_lines = []
    for p in rows:
        name = p.get("name", "")
        cat = map_category(p.get("category", "热菜"))
        raw_cuisine = p.get("cuisine", "川")
        cuisine = CUISINE_MAP.get(raw_cuisine, "川")
        main_ing = p.get("main_ingredient", "")
        allergen = infer_allergen(
            name,
            main_ing,
            p.get("allergen", "无"),
            p.get("custom_options", ""),
            p.get("taste", ""),
        )
        taste = p.get("taste", "咸鲜")
        calories = int(p.get("calories", 400))
        custom = p.get("custom_options", "无定制")
        seasonal = p.get("seasonal", "全年")
        rid = int(p.get("id", 0))
        ex = extras_for_row(name, main_ing, taste, calories, rid)

        v = f"""({rid}, '{sql_escape(name)}', '{sql_escape(cat)}', '{sql_escape(cuisine)}', '{sql_escape(main_ing)}', '{sql_escape(ex['auxiliary_ingredient'])}', '{sql_escape(ex['seasoning'])}', '{sql_escape(allergen)}',
 '{sql_escape(ex['cooking_method'])}', {ex['prep_time_minutes']}, '{sql_escape(ex['temperature_requirement'])}',
 '{sql_escape(taste)}', '{sql_escape(ex['texture'])}', '{sql_escape(ex['color'])}', '{sql_escape(ex['aroma'])}',
 '{sql_escape(ex['portion_size'])}', {calories}, '{sql_escape(ex['nutrition_info'])}', '{sql_escape(ex['shelf_life'])}', '{sql_escape(ex['storage_condition'])}',
 {ex['ingredient_cost']}, {ex['selling_price']}, {ex['gross_profit']},
 '{sql_escape(custom)}', '{sql_escape(seasonal)}')"""
        value_lines.append(v)

    buf.append(",\n".join(value_lines))
    buf.append(";\n")
    buf.append("SET FOREIGN_KEY_CHECKS = 1;\n")

    out_sql.write_text("".join(buf), encoding="utf-8")
    print(f"Wrote {len(rows)} rows to {out_sql}")


if __name__ == "__main__":
    main()
