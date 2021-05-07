-- initial mysql schema

CREATE TABLE flow_events_history
(
    id           integer primary key auto_increment,
    flow_id      text     not null,
    task_id      text     not null,
    action       text     not null,
    unstructured json     not null,
    time_create  datetime not null default now(),
    time_modify  datetime,
    index        `ix_event_time` (event_time),
    index        `ix_flow_id` (flow_id(64)),
    index        `ix_task_id` (task_id(64))
);

CREATE TABLE flow_event_actions
(
    id            integer primary key auto_increment,
    flow_event_id integer  not null,
    action        text     not null,
    details       text,
    time_create   datetime not null default now(),
    time_modify   datetime,
    index         `ix_flow_event_id` (flow_event_id),
    foreign key `fk_flow_event_actions_2_flow_event_history` (flow_event_id)
        REFERENCES flow_events_history(id)
        ON UPDATE CASCADE ON DELETE RESTRICT
);

CREATE TABLE flow_event_dumps
(
    id            integer primary key auto_increment,
    flow_event_id integer  not null,
    kind          varchar(64) not null,
    unstructured  json     not null,
    index         `ix_flow_event_id` (flow_event_id),
    foreign key `fk_flow_event_actions_2_flow_event_history` (flow_event_id)
        REFERENCES flow_events_history (id)
        ON UPDATE CASCADE ON DELETE RESTRICT
);

CREATE TABLE port_events_history
(
    id           char(36) primary key, -- uuid
    switch_id    char(23)    not null,
    port_number  integer     not null,
    event        varchar(64) not null,
    unstructured json        not null,
    time_create datetime not null default now(),
    time_modify datetime,
    index        `ix_event_time` (event_time)
);
