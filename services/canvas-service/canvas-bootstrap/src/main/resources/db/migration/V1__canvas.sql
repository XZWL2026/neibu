CREATE TABLE canvas_document (
  id VARCHAR(128) NOT NULL PRIMARY KEY,
  owner_id VARCHAR(128) NOT NULL,
  name VARCHAR(200) NOT NULL,
  content_json JSON NOT NULL,
  version BIGINT NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  deleted BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT canvas_positive_version CHECK (version > 0),
  INDEX canvas_owner (owner_id, deleted, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE canvas_version (
  id VARCHAR(36) NOT NULL PRIMARY KEY,
  canvas_id VARCHAR(128) NOT NULL,
  version BIGINT NOT NULL,
  content_json JSON NOT NULL,
  deleted BOOLEAN NOT NULL,
  saved_at TIMESTAMP(6) NOT NULL,
  UNIQUE KEY canvas_version_number (canvas_id, version),
  CONSTRAINT canvas_version_document FOREIGN KEY (canvas_id) REFERENCES canvas_document(id),
  CONSTRAINT canvas_history_positive_version CHECK (version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE canvas_media_reference (
  id VARCHAR(36) NOT NULL PRIMARY KEY,
  canvas_id VARCHAR(128) NOT NULL,
  version BIGINT NOT NULL,
  node_id VARCHAR(128) NOT NULL,
  node_kind VARCHAR(128) NOT NULL,
  property_name VARCHAR(128) NOT NULL,
  reference_value VARCHAR(2048) NOT NULL,
  UNIQUE KEY canvas_node_reference (canvas_id, version, node_id, property_name),
  CONSTRAINT canvas_reference_version FOREIGN KEY (canvas_id, version) REFERENCES canvas_version(canvas_id, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
