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
