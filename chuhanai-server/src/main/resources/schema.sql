create table if not exists match_queue (
  id bigint primary key auto_increment,
  session_id varchar(64) not null,
  enqueue_msg_id varchar(64) not null,
  status tinyint not null comment '0-QUEUED,1-MATCHED,2-CANCELLED,3-TIMEOUT',
  enqueue_at datetime(3) not null,
  dequeue_at datetime(3) null,
  matched_room_id varchar(32) null,
  unique key uk_match_enqueue_msg (enqueue_msg_id),
  index idx_match_session_status (session_id, status),
  index idx_match_status_enqueue (status, enqueue_at)
) engine=InnoDB default charset=utf8mb4;

create table if not exists game_room (
  id bigint primary key auto_increment,
  room_id varchar(32) not null unique,
  status varchar(32) not null,
  red_session_id varchar(64) not null,
  black_session_id varchar(64) not null,
  current_turn varchar(8) not null,
  board_fen varchar(255) not null,
  move_no int not null default 0,
  red_time_left_ms int not null,
  black_time_left_ms int not null,
  base_time_ms int not null default 600000,
  increment_ms int not null default 15000,
  last_move_at datetime(3) null,
  version int not null default 0,
  created_at datetime(3) not null,
  updated_at datetime(3) not null,
  index idx_room_status (status),
  index idx_room_red_session (red_session_id),
  index idx_room_black_session (black_session_id)
) engine=InnoDB default charset=utf8mb4;

create table if not exists game_move (
  id bigint primary key auto_increment,
  room_id varchar(32) not null,
  move_no int not null,
  side varchar(8) not null,
  piece varchar(16) not null,
  from_pos char(2) not null,
  to_pos char(2) not null,
  think_time_ms int not null,
  board_fen_before varchar(255) not null,
  board_fen_after varchar(255) not null,
  captured_piece varchar(16) null,
  request_msg_id varchar(64) not null,
  created_at datetime(3) not null,
  unique key uk_room_move_no (room_id, move_no),
  unique key uk_room_msg_id (room_id, request_msg_id),
  index idx_move_room_created (room_id, created_at)
) engine=InnoDB default charset=utf8mb4;

create table if not exists game_control_event (
  id bigint primary key auto_increment,
  room_id varchar(32) not null,
  event_type varchar(24) not null,
  initiator_session_id varchar(64) not null,
  target_session_id varchar(64) null,
  decision varchar(16) null,
  request_msg_id varchar(64) not null,
  related_move_no int null,
  extra_json json null,
  created_at datetime(3) not null,
  unique key uk_ctrl_room_msg_id (room_id, request_msg_id),
  index idx_ctrl_room_created (room_id, created_at)
) engine=InnoDB default charset=utf8mb4;

create table if not exists game_result (
  id bigint primary key auto_increment,
  room_id varchar(32) not null unique,
  winner_side varchar(16) not null,
  finish_reason varchar(24) not null,
  total_move_count int not null,
  duration_ms int not null,
  final_fen varchar(255) not null,
  finished_at datetime(3) not null,
  created_at datetime(3) not null,
  index idx_result_finished_at (finished_at)
) engine=InnoDB default charset=utf8mb4;

create table if not exists chat_message (
  id bigint primary key auto_increment,
  room_id varchar(32) not null,
  sender_session_id varchar(64) not null,
  content varchar(300) not null,
  content_safe tinyint not null default 1,
  request_msg_id varchar(64) not null,
  created_at datetime(3) not null,
  unique key uk_chat_room_msg_id (room_id, request_msg_id),
  index idx_chat_room_created (room_id, created_at)
) engine=InnoDB default charset=utf8mb4;

create table if not exists idempotent_message (
  id bigint primary key auto_increment,
  biz_scope varchar(32) not null,
  room_id varchar(32) null,
  session_id varchar(64) not null,
  msg_id varchar(64) not null,
  msg_type varchar(32) not null,
  process_status tinyint not null comment '0-PENDING,1-SUCCESS,2-FAILED',
  response_json json null,
  created_at datetime(3) not null,
  updated_at datetime(3) not null,
  unique key uk_idem_scope_msg (biz_scope, msg_id),
  unique key uk_idem_session_msg (session_id, msg_id),
  index idx_idem_session_created (session_id, created_at)
) engine=InnoDB default charset=utf8mb4;
