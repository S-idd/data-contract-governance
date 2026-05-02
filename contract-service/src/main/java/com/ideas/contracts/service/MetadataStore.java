package com.ideas.contracts.service;

/**
 * Stable metadata persistence port for check runs, logs, and audit events.
 *
 * <p>This extends the existing CheckRunRepository contract so we can migrate callers
 * to the explicit MetadataStore name without breaking current integrations.
 */
public interface MetadataStore extends CheckRunRepository {}
