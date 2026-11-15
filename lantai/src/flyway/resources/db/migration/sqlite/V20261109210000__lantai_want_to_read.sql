CREATE TABLE "WANT_TO_READ"
(
  "ID"                 VARCHAR(32) NOT NULL,
  "BOOK_ID"            VARCHAR(32) NOT NULL,
  "USER_ID"            VARCHAR(32) NOT NULL,
  "CREATED_DATE"       DATETIME    NOT NULL,
  "LAST_MODIFIED_DATE" DATETIME    NOT NULL,
  PRIMARY KEY ("ID")
);

CREATE UNIQUE INDEX "idx__want_to_read_book_user" ON "WANT_TO_READ" ("BOOK_ID", "USER_ID");

CREATE INDEX "idx__want_to_read_user_created" ON "WANT_TO_READ" ("USER_ID", "CREATED_DATE");
