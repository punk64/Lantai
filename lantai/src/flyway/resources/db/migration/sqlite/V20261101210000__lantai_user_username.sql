PRAGMA foreign_keys= OFF;

create table USER_dg_tmp
(
    ID                         varchar                            not null
        primary key,
    CREATED_DATE               datetime default CURRENT_TIMESTAMP not null,
    LAST_MODIFIED_DATE         datetime default CURRENT_TIMESTAMP not null,
    USERNAME                   varchar                            not null,
    EMAIL                      varchar,
    PASSWORD                   varchar                            not null,
    SHARED_ALL_LIBRARIES       boolean  default 1                 not null,
    AGE_RESTRICTION            integer,
    AGE_RESTRICTION_ALLOW_ONLY boolean,
    ENABLED                    boolean  default 1                 not null
);

insert into USER_dg_tmp(ID, CREATED_DATE, LAST_MODIFIED_DATE, USERNAME, EMAIL, PASSWORD, SHARED_ALL_LIBRARIES,
                        AGE_RESTRICTION, AGE_RESTRICTION_ALLOW_ONLY, ENABLED)
select ID,
       CREATED_DATE,
       LAST_MODIFIED_DATE,
       case
           when instr(EMAIL, '@') > 1 then substr(EMAIL, 1, instr(EMAIL, '@') - 1)
           else EMAIL
           end,
       EMAIL,
       PASSWORD,
       SHARED_ALL_LIBRARIES,
       AGE_RESTRICTION,
       AGE_RESTRICTION_ALLOW_ONLY,
       ENABLED
from USER;

drop table USER;

alter table USER_dg_tmp
    rename to USER;

create unique index IDX_USER_USERNAME on USER (USERNAME);
create unique index IDX_USER_EMAIL on USER (EMAIL) where EMAIL is not null;

PRAGMA foreign_keys= ON;
