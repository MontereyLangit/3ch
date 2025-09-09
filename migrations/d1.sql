CREATE TABLE "main"."subject"(
  "id" INTEGER PRIMARY KEY AUTOINCREMENT,
  "bbs" TEXT NOT NULL,
  "key" INTEGER NOT NULL,
  "title" TEXT,
  "resnum" INTEGER,
  "lastmodified" INTEGER,
  "uid" TEXT
);