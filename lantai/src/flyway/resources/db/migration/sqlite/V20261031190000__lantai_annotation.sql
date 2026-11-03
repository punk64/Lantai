CREATE TABLE "BOOK_ANNOTATION"
(
  "ID"                 VARCHAR(32)  NOT NULL,
  "BOOK_ID"            VARCHAR(32)  NOT NULL,
  "USER_ID"            VARCHAR(32)  NOT NULL,
  "HREF"               VARCHAR(1024) NOT NULL,
  "FRAGMENT"           VARCHAR(256),
  "PROGRESSION"        REAL,
  "TEXT_BEFORE"        VARCHAR(512),
  "TEXT_HIGHLIGHT"     VARCHAR(2048),
  "TEXT_AFTER"         VARCHAR(512),
  "COLOR"              VARCHAR(32)  NOT NULL,
  "MARKER"             VARCHAR(32)  NOT NULL,
  "NOTE"               VARCHAR(4096),
  "SELECTION"          TEXT,
  "CREATED_DATE"       DATETIME     NOT NULL,
  "LAST_MODIFIED_DATE" DATETIME     NOT NULL,
  PRIMARY KEY ("ID")
);

CREATE INDEX "idx__book_annotation_book_user" ON "BOOK_ANNOTATION" ("BOOK_ID", "USER_ID");

CREATE INDEX "idx__book_annotation_user" ON "BOOK_ANNOTATION" ("USER_ID");
