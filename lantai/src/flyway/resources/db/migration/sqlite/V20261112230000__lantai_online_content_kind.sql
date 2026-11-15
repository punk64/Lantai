alter table ONLINE_MANGA
    add column CONTENT_KIND varchar NOT NULL DEFAULT 'image';

alter table ONLINE_MANGA
    add column CONTENT_FORMAT varchar NOT NULL DEFAULT 'html';
