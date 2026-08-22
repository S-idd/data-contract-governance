ALTER TABLE check_evidence ADD COLUMN auth_scheme VARCHAR(32) NOT NULL DEFAULT 'BASIC';
ALTER TABLE check_evidence ADD COLUMN oidc_issuer VARCHAR(2048) NULL;
ALTER TABLE check_evidence ADD COLUMN oidc_subject VARCHAR(1024) NULL;
ALTER TABLE check_evidence ADD COLUMN oidc_audience VARCHAR(2048) NULL;
ALTER TABLE check_evidence ADD COLUMN oidc_repository VARCHAR(512) NULL;
ALTER TABLE check_evidence ADD COLUMN oidc_ref VARCHAR(512) NULL;

CREATE INDEX idx_check_evidence_oidc_provenance
  ON check_evidence (oidc_repository, oidc_ref, imported_at);
