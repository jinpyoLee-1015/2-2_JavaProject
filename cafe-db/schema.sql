-- 교내 카페 사전 주문 시스템 - MySQL 스키마
-- 실행 방법 (둘 중 하나)
--   1) MySQL Workbench에서 이 파일을 열고 번개(Execute) 버튼
--   2) 명령창:  mysql -u root -p --default-character-set=utf8mb4 < schema.sql
-- 여러 번 실행해도 기존 데이터는 지워지지 않는다. (처음부터 다시 만들려면 맨 아래 주석 참고)

CREATE DATABASE IF NOT EXISTS cafe
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE cafe;

-- 회원
CREATE TABLE IF NOT EXISTS members (
    id        VARCHAR(15)  NOT NULL COMMENT '학번',
    name      VARCHAR(20)  NOT NULL,
    email     VARCHAR(100) NOT NULL,
    pw_hash   VARCHAR(64)  NOT NULL COMMENT 'SHA-256 해시',
    joined_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_members_email (email)
);

-- 메뉴 / 재고
CREATE TABLE IF NOT EXISTS menu (
    id              INT         NOT NULL AUTO_INCREMENT,
    category        VARCHAR(30) NOT NULL,
    name            VARCHAR(50) NOT NULL,
    price           INT         NOT NULL,
    stock           INT         NOT NULL DEFAULT 0,
    manual_sold_out BOOLEAN     NOT NULL DEFAULT FALSE,
    PRIMARY KEY (id)
);

-- 주문 (대기번호 = order_no)
-- member_id 에는 외래키를 걸지 않았다: 회원이 탈퇴해도 지난 주문 기록(통계)을 남기기 위해.
-- 그래서 member_name 도 주문 시점의 이름을 함께 저장한다.
CREATE TABLE IF NOT EXISTS orders (
    order_no    INT         NOT NULL AUTO_INCREMENT,
    member_id   VARCHAR(15) NOT NULL,
    member_name VARCHAR(20) NOT NULL,
    status      VARCHAR(10) NOT NULL DEFAULT 'WAITING'
                COMMENT 'WAITING / MAKING / READY / DONE / CANCELED',
    order_time  DATETIME(3) NOT NULL,
    PRIMARY KEY (order_no),
    KEY idx_orders_member (member_id),
    KEY idx_orders_status (status),
    KEY idx_orders_time (order_time)
);

-- 주문 상세 (주문 1건 : 메뉴 여러 줄)
-- menu_id 에도 외래키를 걸지 않았다: 메뉴를 삭제해도 지난 주문 기록은 유지. menu_name 은 주문 당시 이름.
CREATE TABLE IF NOT EXISTS order_items (
    id         INT         NOT NULL AUTO_INCREMENT,
    order_no   INT         NOT NULL,
    menu_id    INT         NOT NULL,
    menu_name  VARCHAR(50) NOT NULL,
    opt_size   VARCHAR(10) NOT NULL COMMENT '기본 / 라지',
    shots      INT         NOT NULL DEFAULT 0,
    qty        INT         NOT NULL,
    unit_price INT         NOT NULL COMMENT '주문 당시 단가(옵션 포함)',
    PRIMARY KEY (id),
    KEY idx_items_menu (menu_id),
    CONSTRAINT fk_items_order FOREIGN KEY (order_no) REFERENCES orders (order_no) ON DELETE CASCADE
);

-- 초기 메뉴 9개는 관리자 프로그램이 처음 실행될 때 menu 테이블이 비어 있으면 자동으로 넣는다.

-- [처음부터 다시 만들고 싶을 때만] 아래 줄의 주석을 풀고 실행 (모든 데이터 삭제됨!)
-- DROP DATABASE cafe;
