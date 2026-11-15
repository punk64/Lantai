CREATE TABLE "SCRAPE_QUEUE"
(
  "ID"                 VARCHAR(32)  NOT NULL,
  "SERIES_ID"          VARCHAR(32)  NOT NULL,
  "SERIES_TITLE"       VARCHAR(512) NOT NULL,
  "STATUS"             VARCHAR(16)  NOT NULL,
  "MATCH_ID"           VARCHAR(32),
  "SCORE"              REAL,
  "SOURCE"             VARCHAR(64),
  "MESSAGE"            VARCHAR(512),
  "CREATED_DATE"       DATETIME     NOT NULL,
  "LAST_MODIFIED_DATE" DATETIME     NOT NULL,
  PRIMARY KEY ("ID")
);

CREATE UNIQUE INDEX "idx__scrape_queue_series_id" ON "SCRAPE_QUEUE" ("SERIES_ID");

CREATE INDEX "idx__scrape_queue_status" ON "SCRAPE_QUEUE" ("STATUS");
