ALTER TABLE check_evidence ADD COLUMN auth_scheme TEXT NOT NULL DEFAULT 'BASIC';
ALTER TABLE check_evidence ADD COLUMN oidc_issuer TEXT;
ALTER TABLE check_evidence ADD COLUMN oidc_subject TEXT;
ALTER TABLE check_evidence ADD COLUMN oidc_audience TEXT;
ALTER TABLE check_evidence ADD COLUMN oidc_repository TEXT;
ALTER TABLE check_evidence ADD COLUMN oidc_ref TEXT;

CREATE INDEX IF NOT EXISTS idx_check_evidence_oidc_provenance
  ON check_evidence (oidc_repository, oidc_ref, imported_at DESC);
