package com.ideas.contracts.service;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "notifications")
public class NotificationProperties {
  private boolean enabled;
  private List<String> sinks = List.of("log");
  private final Webhook webhook = new Webhook();
  private final Retry retry = new Retry();
  private final Dispatch dispatch = new Dispatch();
  private final Payload payload = new Payload();

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public List<String> getSinks() {
    return sinks;
  }

  public void setSinks(List<String> sinks) {
    this.sinks = sinks == null || sinks.isEmpty() ? List.of("log") : List.copyOf(sinks);
  }

  public Webhook getWebhook() {
    return webhook;
  }

  public Retry getRetry() {
    return retry;
  }

  public Dispatch getDispatch() {
    return dispatch;
  }

  public Payload getPayload() {
    return payload;
  }

  public Set<String> normalizedSinks() {
    LinkedHashSet<String> normalized = new LinkedHashSet<>();
    for (String sink : sinks) {
      if (sink != null && !sink.isBlank()) {
        normalized.add(sink.trim().toLowerCase(Locale.ROOT));
      }
    }
    if (normalized.isEmpty()) {
      normalized.add("log");
    }
    return normalized;
  }

  public static class Webhook {
    private boolean enabled;
    private String url = "";
    private String urlEnv = "";
    private String authHeaderEnv = "";
    private Duration timeout = Duration.ofSeconds(3);

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public String getUrl() {
      return url;
    }

    public void setUrl(String url) {
      this.url = url;
    }

    public String getUrlEnv() {
      return urlEnv;
    }

    public void setUrlEnv(String urlEnv) {
      this.urlEnv = urlEnv;
    }

    public String getAuthHeaderEnv() {
      return authHeaderEnv;
    }

    public void setAuthHeaderEnv(String authHeaderEnv) {
      this.authHeaderEnv = authHeaderEnv;
    }

    public Duration getTimeout() {
      return timeout;
    }

    public void setTimeout(Duration timeout) {
      this.timeout = timeout;
    }
  }

  public static class Retry {
    private int maxAttempts = 3;
    private Duration initialDelay = Duration.ofSeconds(5);

    public int getMaxAttempts() {
      return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
      this.maxAttempts = maxAttempts;
    }

    public Duration getInitialDelay() {
      return initialDelay;
    }

    public void setInitialDelay(Duration initialDelay) {
      this.initialDelay = initialDelay;
    }
  }

  public static class Payload {
    private int maxBreakingChanges = 10;

    public int getMaxBreakingChanges() {
      return maxBreakingChanges;
    }

    public void setMaxBreakingChanges(int maxBreakingChanges) {
      this.maxBreakingChanges = maxBreakingChanges;
    }
  }

  public static class Dispatch {
    private Duration claimTimeout = Duration.ofMinutes(1);
    private int maxPerPoll = 10;

    public Duration getClaimTimeout() {
      return claimTimeout;
    }

    public void setClaimTimeout(Duration claimTimeout) {
      this.claimTimeout = claimTimeout;
    }

    public int getMaxPerPoll() {
      return maxPerPoll;
    }

    public void setMaxPerPoll(int maxPerPoll) {
      this.maxPerPoll = maxPerPoll;
    }
  }
}
