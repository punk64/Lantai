alter table LIBRARY
    add column TRASH_MAX_BYTES bigint NULL;

alter table SERIES
    add column EXCLUDED_DATE datetime NULL;

alter table BOOK
    add column EXCLUDED_DATE datetime NULL;

CREATE TABLE TRASH_ENTRY
(
    ID            varchar  NOT NULL PRIMARY KEY,
    ITEM_TYPE     varchar  NOT NULL,
    LIBRARY_ID    varchar  NOT NULL,
    SERIES_ID     varchar  NULL,
    BOOK_ID       varchar  NULL,
    ORIGINAL_PATH varchar  NOT NULL,
    TRASHED_PATH  varchar  NOT NULL,
    SIZE_BYTES    bigint   NOT NULL,
    DELETED_DATE  datetime NOT NULL
);

CREATE INDEX IDX__TRASH_ENTRY__DELETED_DATE ON TRASH_ENTRY (DELETED_DATE);

CREATE INDEX IDX__TRASH_ENTRY__LIBRARY_ID ON TRASH_ENTRY (LIBRARY_ID);
