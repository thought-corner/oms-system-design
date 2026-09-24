CREATE DATABASE IF NOT EXISTS `order`   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS `product` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS `point`   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS `payment` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS `operation` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE USER IF NOT EXISTS 'order_user'@'%'   IDENTIFIED BY '1234';
CREATE USER IF NOT EXISTS 'product_user'@'%' IDENTIFIED BY '1234';
CREATE USER IF NOT EXISTS 'point_user'@'%'   IDENTIFIED BY '1234';
CREATE USER IF NOT EXISTS 'payment_user'@'%' IDENTIFIED BY '1234';
CREATE USER IF NOT EXISTS 'operation_user'@'%' IDENTIFIED BY '1234';

GRANT ALL PRIVILEGES ON `order`.*   TO 'order_user'@'%';
GRANT ALL PRIVILEGES ON `product`.* TO 'product_user'@'%';
GRANT ALL PRIVILEGES ON `point`.*   TO 'point_user'@'%';
GRANT ALL PRIVILEGES ON `payment`.* TO 'payment_user'@'%';
GRANT ALL PRIVILEGES ON `operation`.* TO 'operation_user'@'%';

CREATE TABLE IF NOT EXISTS `order`.`outbox` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT,
    `message_id`   VARCHAR(36)  NOT NULL,
    `topic`        VARCHAR(100) NOT NULL,
    `message_key`  VARCHAR(50)  NOT NULL,
    `saga_id`      VARCHAR(36)  NOT NULL,
    `message_type` VARCHAR(50)  NOT NULL,
    `payload`      TEXT         NOT NULL,
    `status`       VARCHAR(20)  NOT NULL,
    `fail_count`   INT          NOT NULL DEFAULT 0,
    `occurred_at`  DATETIME(6)  NOT NULL,
    `claimed_at`   DATETIME(6)  NULL,
    `published_at` DATETIME(6)  NULL,
    `failed_at`    DATETIME(6)  NULL,
    `last_error`   VARCHAR(255) NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_outbox_message_id` (`message_id`),
    INDEX `idx_outbox_status_id` (`status`, `id`)
);

CREATE TABLE IF NOT EXISTS `product`.`outbox` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT,
    `message_id`   VARCHAR(36)  NOT NULL,
    `topic`        VARCHAR(100) NOT NULL,
    `message_key`  VARCHAR(50)  NOT NULL,
    `saga_id`      VARCHAR(36)  NOT NULL,
    `message_type` VARCHAR(50)  NOT NULL,
    `payload`      TEXT         NOT NULL,
    `status`       VARCHAR(20)  NOT NULL,
    `fail_count`   INT          NOT NULL DEFAULT 0,
    `occurred_at`  DATETIME(6)  NOT NULL,
    `claimed_at`   DATETIME(6)  NULL,
    `published_at` DATETIME(6)  NULL,
    `failed_at`    DATETIME(6)  NULL,
    `last_error`   VARCHAR(255) NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_outbox_message_id` (`message_id`),
    INDEX `idx_outbox_status_id` (`status`, `id`)
);

CREATE TABLE IF NOT EXISTS `point`.`outbox` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT,
    `message_id`   VARCHAR(36)  NOT NULL,
    `topic`        VARCHAR(100) NOT NULL,
    `message_key`  VARCHAR(50)  NOT NULL,
    `saga_id`      VARCHAR(36)  NOT NULL,
    `message_type` VARCHAR(50)  NOT NULL,
    `payload`      TEXT         NOT NULL,
    `status`       VARCHAR(20)  NOT NULL,
    `fail_count`   INT          NOT NULL DEFAULT 0,
    `occurred_at`  DATETIME(6)  NOT NULL,
    `claimed_at`   DATETIME(6)  NULL,
    `published_at` DATETIME(6)  NULL,
    `failed_at`    DATETIME(6)  NULL,
    `last_error`   VARCHAR(255) NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_outbox_message_id` (`message_id`),
    INDEX `idx_outbox_status_id` (`status`, `id`)
);

CREATE TABLE IF NOT EXISTS `payment`.`outbox` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT,
    `message_id`   VARCHAR(36)  NOT NULL,
    `topic`        VARCHAR(100) NOT NULL,
    `message_key`  VARCHAR(50)  NOT NULL,
    `saga_id`      VARCHAR(36)  NOT NULL,
    `message_type` VARCHAR(50)  NOT NULL,
    `payload`      TEXT         NOT NULL,
    `status`       VARCHAR(20)  NOT NULL,
    `fail_count`   INT          NOT NULL DEFAULT 0,
    `occurred_at`  DATETIME(6)  NOT NULL,
    `claimed_at`   DATETIME(6)  NULL,
    `published_at` DATETIME(6)  NULL,
    `failed_at`    DATETIME(6)  NULL,
    `last_error`   VARCHAR(255) NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_outbox_message_id` (`message_id`),
    INDEX `idx_outbox_status_id` (`status`, `id`)
);

GRANT SELECT, UPDATE, DELETE ON `order`.`outbox`   TO 'operation_user'@'%';
GRANT SELECT, UPDATE, DELETE ON `product`.`outbox` TO 'operation_user'@'%';
GRANT SELECT, UPDATE, DELETE ON `point`.`outbox`   TO 'operation_user'@'%';
GRANT SELECT, UPDATE, DELETE ON `payment`.`outbox` TO 'operation_user'@'%';

FLUSH PRIVILEGES;
