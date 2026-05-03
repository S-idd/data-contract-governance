CREATE TABLE IF NOT EXISTS audit_logs (
  audit_id VARCHAR(64) PRIMARY KEY,
  action VARCHAR(128) NOT NULL,
  actor VARCHAR(255) NOT NULL,
  actor_roles VARCHAR(255) NOT NULL,
  source VARCHAR(255) NOT NULL,
  request_id VARCHAR(128),
  http_method VARCHAR(16) NOT NULL,
  path VARCHAR(1024) NOT NULL,
  resource_type VARCHAR(128) NOT NULL,
  resource_id VARCHAR(255),
  status VARCHAR(32) NOT NULL,
  detail LONGTEXT,
  created_at VARCHAR(40) NOT NULL
) ENGINE=InnoDB;

CREATE INDEX idx_audit_logs_created_at
  ON audit_logs (created_at);

CREATE INDEX idx_audit_logs_action
  ON audit_logs (action);

CREATE INDEX idx_audit_logs_resource
  ON audit_logs (resource_type, resource_id);
