PRAGMA writable_schema = ON;

UPDATE sqlite_master
SET sql = replace(sql, 'DEFAULT ''s' || 'uwayomi''', 'DEFAULT ''plugin''')
WHERE type = 'table'
  AND name = 'ONLINE_MANGA';

PRAGMA writable_schema = OFF;

PRAGMA schema_version = 1000000;
