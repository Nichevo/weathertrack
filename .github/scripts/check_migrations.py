#!/usr/bin/env python3
"""Checks that every CREATE statement Room generates for a migrated table appears verbatim in
Migrations.kt, so upgrades never fail Room's schema validation (which would crash on launch)."""
import pathlib
import re
import sys

MIGRATED_TABLES = ["observed_day", "climate_day", "energy_day", "observed_hour", "previous_run_hour", "user_report", "source_hour"]

root = pathlib.Path(__file__).resolve().parents[2]
impls = list((root / "app/build/generated/ksp").rglob("AppDatabase_Impl.*"))
if not impls:
    sys.exit("AppDatabase_Impl not found; build the app first")
generated = impls[0].read_text()
migrations = (root / "app/src/main/kotlin/app/weathertrack/data/db/Migrations.kt").read_text()

statements = re.findall(r'"(CREATE (?:TABLE|INDEX|UNIQUE INDEX) IF NOT EXISTS [^"]+)"', generated)
failed = False
for table in MIGRATED_TABLES:
    wanted = [s for s in statements if f"`{table}`" in s]
    if not wanted:
        print(f"No generated statements found for {table}")
        failed = True
    for s in wanted:
        if s not in migrations:
            print(f"Missing or different in Migrations.kt:\n  {s}")
            failed = True
# Columns added with ALTER TABLE must match the type and nullability Room generates.
for table, column, decl in re.findall(r"ALTER TABLE `(\w+)` ADD COLUMN `(\w+)` ([A-Z]+(?: NOT NULL)?)", migrations):
    create = next((s for s in statements if s.startswith(f"CREATE TABLE IF NOT EXISTS `{table}`")), "")
    m = re.search(rf"`{column}` ([A-Z]+(?: NOT NULL)?)", create)
    generated_decl = m.group(1) if m else None
    if generated_decl != decl:
        print(f"{table}.{column}: migration adds '{decl}', Room expects '{generated_decl}'")
        failed = True

sys.exit(1 if failed else 0)
