package com.ideas.contracts.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CheckRunQueryTest {
  @Test
  void fromAppliesDefaultsAndNormalizesFields() {
    CheckRunQuery query = CheckRunQuery.from(" orders.created ", " abc123 ", "pass", null, null);

    assertEquals("orders.created", query.contractId());
    assertEquals("abc123", query.commitSha());
    assertEquals("PASS", query.status());
    assertEquals(CheckRunQuery.DEFAULT_LIMIT, query.limit());
    assertEquals(CheckRunQuery.DEFAULT_OFFSET, query.offset());
  }

  @Test
  void fromAllowsBlankFiltersAsNull() {
    CheckRunQuery query = CheckRunQuery.from("  ", " ", null, 20, 1);
    assertNull(query.contractId());
    assertNull(query.commitSha());
    assertNull(query.status());
  }

  @Test
  void fromRejectsInvalidLimitAndOffset() {
    assertThrows(IllegalArgumentException.class, () -> CheckRunQuery.from(null, null, null, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> CheckRunQuery.from(null, null, null, 201, 0));
    assertThrows(IllegalArgumentException.class, () -> CheckRunQuery.from(null, null, null, 1, -1));
  }

  @Test
  void fromRejectsInvalidStatusCharacters() {
    assertThrows(IllegalArgumentException.class, () -> CheckRunQuery.from(null, null, "pass-fail", 10, 0));
  }
}
