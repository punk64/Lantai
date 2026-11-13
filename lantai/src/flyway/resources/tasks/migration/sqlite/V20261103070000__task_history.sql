CREATE TABLE TASK_HISTORY
(
    ID            varchar  NOT NULL PRIMARY KEY,
    SIMPLE_TYPE   varchar  NOT NULL,
    PRIORITY      int      NOT NULL,
    GROUP_ID      varchar  NULL,
    STATUS        varchar  NOT NULL,
    CREATED_DATE  datetime NOT NULL,
    FINISHED_DATE datetime NOT NULL,
    DURATION_MS   bigint   NOT NULL,
    ERROR         varchar  NULL
);

CREATE INDEX IDX__TASK_HISTORY__FINISHED_DATE ON TASK_HISTORY (FINISHED_DATE);
