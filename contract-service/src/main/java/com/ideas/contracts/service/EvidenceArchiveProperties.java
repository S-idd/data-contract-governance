package com.ideas.contracts.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Configuration for the independent evidence archive target. */
@Component
@ConfigurationProperties(prefix = "checks.evidence.archive")
public class EvidenceArchiveProperties {
  public enum Mode { DISABLED, FILESYSTEM, S3_WORM }

  private Mode mode = Mode.DISABLED;
  private String filesystemRoot = "evidence-archive-rehearsal";
  private S3 s3 = new S3();

  public Mode getMode() { return mode; }
  public void setMode(Mode mode) { this.mode = mode == null ? Mode.DISABLED : mode; }
  public String getFilesystemRoot() { return filesystemRoot; }
  public void setFilesystemRoot(String filesystemRoot) { this.filesystemRoot = filesystemRoot; }
  public S3 getS3() { return s3; }
  public void setS3(S3 s3) { this.s3 = s3 == null ? new S3() : s3; }

  public static class S3 {
    private String bucket;
    private String prefix = "evidence";
    private String region = "us-east-1";
    private String endpoint;
    private boolean pathStyle;
    private String accessKey;
    private String secretKey;
    private String expectedBucketOwner;
    private int retentionDays = 2555;

    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }
    public String getPrefix() { return prefix; }
    public void setPrefix(String prefix) { this.prefix = prefix; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public boolean isPathStyle() { return pathStyle; }
    public void setPathStyle(boolean pathStyle) { this.pathStyle = pathStyle; }
    public String getAccessKey() { return accessKey; }
    public void setAccessKey(String accessKey) { this.accessKey = accessKey; }
    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
    public String getExpectedBucketOwner() { return expectedBucketOwner; }
    public void setExpectedBucketOwner(String expectedBucketOwner) { this.expectedBucketOwner = expectedBucketOwner; }
    public int getRetentionDays() { return retentionDays; }
    public void setRetentionDays(int retentionDays) { this.retentionDays = retentionDays; }
  }
}
