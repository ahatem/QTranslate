---
name: qtranslate-settings-migration
description: High-safety workflow for QTranslate settings/config persistence changes covering versions, defaults, corruption recovery, portable/installed modes, and rollback. Use for any settings change.
---

# QTranslate Settings Migration

R3 by default. Silent migration regressions affect existing users.

## Checklist

- State old version → new version; bump `configVersion` for any schema change.
- Every field: missing-field default, corrupt/partial-state behavior,
  idempotent migration (re-running is a no-op), rollback/recovery path
  (timestamped `config-backup-*`).
- Preserve existing users: defaults must reproduce prior behavior; unknown
  future versions pass through unchanged (loop-guarded).
- Portable vs installed vs `-DappData` custom: verify storage resolution in
  `AppDataLayout` for each mode; `portable.flag` inclusion rules still hold.
- Serialization expectations: DataStore keys (`configuration_json`,
  `disabled_plugin_ids`), single-instance-per-file discipline.

## Tests (mandatory)

Round-trip tests over previous serialized representations: old JSON in →
migrated config out; corrupt input → backup + defaults + surfaced recovery.
Run the focused settings tests plus the migration suite; never ship a
migration with "logic looks right" as the only evidence.
