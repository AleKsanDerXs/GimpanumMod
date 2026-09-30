"""Собирает рекомендованные настройки под наш сервер из встроенных образцов мода.

Берёт образцы из src/main/resources/data/gimpanum/..., накладывает поверх
правки из OVERRIDES и пишет готовые файлы в recommended_config/.
Файл в config/gimpanum/ можно взять целиком: мод всё равно сливает его с
образцом по полю, так что и частичный файл не сломает игру.

Запуск (из корня репозитория): python tools/make_recommended_config.py
"""
import copy
import json
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "src", "main", "resources", "data", "gimpanum")
OUT = os.path.join(ROOT, "recommended_config")

SOURCES = {
    "performance.json": "performance/default_performance.json",
    "mobs.json": "entity/default_mobs.json",
    "emissions.json": "emission/default_emissions.json",
}

# Что меняем относительно встроенных значений и почему — см. REPORT.html.
OVERRIDES = {
    "performance.json": {
        "chunk_loading": {"max_in_flight": 8},
        "load_guard": {"skip_above_mspt": 40.0},
        "portal": {
            "explore_chance": 0.1,
            "max_attempts": 6,
            "region_range": 6,
            "validate_attempts": 4,
            "max_known": 256,
            "wait_seconds": 30,
        },
        "devourer": {
            "dig_enabled": True,
            "respect_mob_griefing": True,
            "dimensions": ["gimpanum:gimpanum"],
            "bite_radius": 3,
            "max_blocks_per_tick": 80,
            "scan_interval_ticks": 2,
            "hunt_timeout_seconds": 900,
        },
        "volatile_gas": {"max_per_tick": 6},
        "vacuum": {"interval_ticks": 20, "radius_chunks": 6},
        "converter": {
            "max_items_per_pulse": 256,
            "pause_when_items_nearby": 300,
            "marker_broadcast_ticks": 40,
        },
        "core": {
            "arbiter_window_ticks": 10,
            "spawn_min_interval_seconds": 5,
            "spawn_max_count": 64,
            "spawn_pause_items_nearby": 200,
        },
        "necrophage": {"target_check_interval_ticks": 5},
    },
    "mobs.json": {
        "spawner": {
            "radius_chunks": 3,
            "period_ticks": 60,
            "max_per_pass": 6,
            "min_distance_blocks": 32,
            "max_per_player": 60,
        },
        "dune_captain": {
            "spawn_per_chunk": 0.08,
            "squad_size": 6,
            "detection_blocks": 64.0,
            "memory_ticks": 3600,
            "reinforce_fastest_ticks": 20,
            "reinforce_slowest_ticks": 200,
        },
        "comet_wraith": {"spawn_per_chunk": 0.12, "detection_blocks": 48.0},
        "plasma_bolt": {"spawn_per_chunk": 0.02, "detection_blocks": 48.0},
        "space_devourer": {"roar_interval_ticks": 60, "detection_blocks": 64.0},
    },
    "emissions.json": {
        "schedule": {"interval_minutes_min": 20, "interval_minutes_max": 60},
        "frenzied_raid": {"weight": 0.5, "captains_min": 1, "captains_max": 2, "reinforce_speedup": 3.0},
        "sleeping_god": {"weight": 0.5},
        "accretion": {
            "power": 2.0,
            "interval_ticks_min": 80,
            "interval_ticks_max": 200,
            "break_blocks": False,
            "place_debris": False,
        },
    },
}


def merge(base, extra, path=""):
    for key, value in extra.items():
        if key not in base:
            raise SystemExit("нет такого поля в образце: %s%s" % (path, key))
        if isinstance(value, dict):
            merge(base[key], value, path + key + ".")
        else:
            base[key] = value


def main():
    os.makedirs(OUT, exist_ok=True)
    for name, source in SOURCES.items():
        with open(os.path.join(RES, source), encoding="utf8") as f:
            data = json.load(f)
        result = copy.deepcopy(data)
        merge(result, OVERRIDES[name])
        with open(os.path.join(OUT, name), "w", encoding="utf8", newline="\n") as f:
            json.dump(result, f, ensure_ascii=False, indent=4)
            f.write("\n")
        print("записан", os.path.join(OUT, name))


if __name__ == "__main__":
    main()
