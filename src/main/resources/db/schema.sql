-- 商品基础信息表：保存商品的编码、名称、销售价格和状态。
CREATE TABLE products (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '商品主键',
    sku VARCHAR(32) NOT NULL COMMENT '商品唯一编码',
    name VARCHAR(100) NOT NULL COMMENT '商品名称',
    price DECIMAL(12, 2) NOT NULL COMMENT '商品销售价格',
    status VARCHAR(20) NOT NULL COMMENT '商品状态，例如ON_SALE、OFF_SALE',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    -- 数据库兜底：商品价格必须大于0。
    CONSTRAINT chk_products_price CHECK (price > 0),
    -- SKU用于业务识别商品，数据库唯一约束防止重复数据。
    UNIQUE KEY uk_products_sku (sku)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_general_ci
  COMMENT='商品基础信息表';

-- 商品库存表：每个商品只保存一条当前可用库存记录。
CREATE TABLE product_inventory (
    product_id BIGINT PRIMARY KEY COMMENT '商品ID，同时作为库存表主键',
    stock INT NOT NULL COMMENT '当前可用库存数量',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    -- 库存允许为0，但不能出现负数。
    CONSTRAINT chk_stock CHECK (stock >= 0),
    -- 通过主键和外键共同保证一个商品最多对应一条库存记录。
    CONSTRAINT fk_inventory_product FOREIGN KEY (product_id) REFERENCES products(id)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_general_ci
  COMMENT='商品库存表';

-- 订单主表：保存购买用户、订单状态、总金额及生命周期时间。
CREATE TABLE orders(
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '订单主键',
    order_no VARCHAR(32) NOT NULL COMMENT '业务订单号，由服务端生成，唯一',
    buyer_id BIGINT NOT NULL COMMENT '购买用户ID，由已认证身份取得',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING_PAYMENT' COMMENT '订单状态：PENDING_PAYMENT待支付、PAID已支付、CANCELLED已取消、CLOSED超时关闭',
    total_amount DECIMAL(18,2) NOT NULL COMMENT '订单总金额，由服务端汇总明细金额',
    expires_at DATETIME NOT NULL COMMENT '未支付订单的到期时间',
    paid_at DATETIME COMMENT '支付完成时间，未支付时为空',
    cancelled_at DATETIME COMMENT '主动取消时间，未取消时为空',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    -- 业务订单号不可重复；用户ID和订单总金额必须为正数。
    UNIQUE KEY uk_order_no (order_no),
    CONSTRAINT ck_order_buy_id check ( buyer_id > 0 ),
    CONSTRAINT ck_order_total_amount check (total_amount>0  ),
    -- 支持按用户筛选订单，并按创建时间、ID稳定排序。
    KEY idx_buyer_created_id (buyer_id,created_at,id),
    -- 支持扫描指定状态下已到期的订单。
    KEY idx_status_expires (status,expires_at)
)ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='订单主表';

-- 订单明细表：保存购买数量以及下单时的商品名称、SKU和单价快照。
CREATE TABLE order_items(
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '订单明细主键',
    order_id BIGINT NOT NULL COMMENT '所属订单ID，关联订单主表',
    product_id BIGINT NOT NULL COMMENT '购买商品ID，关联商品表',
    sku_snapshot VARCHAR(32) NOT NULL COMMENT '下单时的商品SKU快照',
    name_snapshot VARCHAR(100) NOT NULL COMMENT '下单时的商品名称快照',
    unit_price DECIMAL(12,2) NOT NULL COMMENT '下单时的商品单价，由服务端读取商品价格',
    quantity INT NOT NULL COMMENT '购买数量，必须大于0',
    line_amount DECIMAL(18,2) NOT NULL COMMENT '该商品行金额，由服务端按单价乘数量计算',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    -- 数据库兜底：金额、数量和单价必须为正数。
    CONSTRAINT chk_line_amount CHECK ( line_amount>0 ),
    CONSTRAINT chk_quantity CHECK ( quantity>0 ),
    CONSTRAINT chk_unit_price CHECK ( unit_price>0 ),
    -- 明细必须关联存在的订单和商品，不设置删除级联。
    CONSTRAINT fk_order_item FOREIGN KEY (order_id) REFERENCES orders(id),
    CONSTRAINT fk_order_items_product FOREIGN KEY (product_id) REFERENCES products(id),
    -- 同一订单的同一商品最多保存一条明细。
    UNIQUE KEY uk_order_items_order_product (order_id, product_id)

)ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='订单明细及商品快照表';


CREATE TABLE users
(
    id         BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '用户主键',
    username   VARCHAR(32)  NOT NULL COMMENT '登录用户名，唯一',
    password   VARChAR(255) NOT NULL COMMENT '密码编码值，由PasswordEncoder生成',
    role       VARCHAR(20)  NOT NULL DEFAULT 'USER' COMMENT '用户角色：USER普通用户、ADMIN管理员',
    enabled    TINYINT               DEFAULT 1 COMMENT '账号启用状态：1启用、0禁用',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    UNIQUE KEY uk_users_username (username)
)ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户表';