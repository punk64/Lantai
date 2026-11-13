CREATE TABLE TEXT_SOURCE_CONFIG
(
    ID                 varchar  NOT NULL PRIMARY KEY,
    CREATED_DATE       datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    LAST_MODIFIED_DATE datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KIND               varchar  NOT NULL,
    ENDPOINT           varchar  NULL,
    USERNAME           varchar  NULL,
    PASSWORD_ENC       varchar  NULL,
    ENABLED            boolean  NOT NULL DEFAULT 1,
    CAPABILITIES       varchar  NULL,
    LAST_PROBE_AT      datetime NULL,
    LAST_PROBE_OK      boolean  NULL
);

CREATE UNIQUE INDEX IDX__TEXT_SOURCE_CONFIG__KIND ON TEXT_SOURCE_CONFIG (KIND);

CREATE TABLE TEXT_BOOK
(
    ID                 varchar  NOT NULL PRIMARY KEY,
    CREATED_DATE       datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    LAST_MODIFIED_DATE datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    SOURCE_KIND        varchar  NOT NULL,
    SOURCE_ID          varchar  NOT NULL,
    REMOTE_URL         varchar  NOT NULL,
    TITLE              varchar  NOT NULL,
    AUTHOR             varchar  NULL,
    LATEST_CHAPTER     varchar  NULL,
    COVER_URL          varchar  NULL,
    ARCHIVED_BOOK_ID   varchar  NULL,
    LAST_FETCH_AT      datetime NULL
);

CREATE UNIQUE INDEX IDX__TEXT_BOOK__REMOTE ON TEXT_BOOK (SOURCE_KIND, SOURCE_ID, REMOTE_URL);

CREATE TABLE TEXT_CHAPTER
(
    ID                 varchar  NOT NULL PRIMARY KEY,
    CREATED_DATE       datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    LAST_MODIFIED_DATE datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    BOOK_ID            varchar  NOT NULL,
    IDX                int      NOT NULL,
    TITLE              varchar  NOT NULL,
    REMOTE_URL         varchar  NOT NULL,
    CACHE_PATH         varchar  NULL,
    CHARS              int      NOT NULL DEFAULT 0,
    STATUS             varchar  NOT NULL DEFAULT 'PENDING',
    FOREIGN KEY (BOOK_ID) REFERENCES TEXT_BOOK (ID)
);

CREATE UNIQUE INDEX IDX__TEXT_CHAPTER__BOOK_IDX ON TEXT_CHAPTER (BOOK_ID, IDX);

CREATE TABLE TEXT_FETCH_JOB
(
    ID                 varchar  NOT NULL PRIMARY KEY,
    CREATED_DATE       datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    LAST_MODIFIED_DATE datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    BOOK_ID            varchar  NOT NULL,
    MODE               varchar  NOT NULL DEFAULT 'CACHE',
    STATUS             varchar  NOT NULL DEFAULT 'PENDING',
    CHAPTER_DONE       int      NOT NULL DEFAULT 0,
    CHAPTER_TOTAL      int      NOT NULL DEFAULT 0,
    ERROR              varchar  NULL,
    STARTED_AT         datetime NULL,
    FINISHED_AT        datetime NULL,
    FOREIGN KEY (BOOK_ID) REFERENCES TEXT_BOOK (ID)
);
