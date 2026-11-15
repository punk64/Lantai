CREATE INDEX IF NOT EXISTS idx__series__last_modified on SERIES (LAST_MODIFIED_DATE);
CREATE INDEX IF NOT EXISTS idx__series__name on SERIES (NAME);
CREATE INDEX IF NOT EXISTS idx__series_metadata__publisher on SERIES_METADATA (PUBLISHER);
CREATE INDEX IF NOT EXISTS idx__series_metadata__first_letter on SERIES_METADATA (FIRST_LETTER);
