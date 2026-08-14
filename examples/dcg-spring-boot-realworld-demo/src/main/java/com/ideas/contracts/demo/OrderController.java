package com.ideas.contracts.demo;

import com.ideas.contracts.starter.ValidateContract;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/demo/orders")
class OrderController {
  @PostMapping
  @ValidateContract(contractId = "orders.created", version = "v1")
  Map<String, Object> createOrder(@RequestBody Map<String, Object> order) {
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("accepted", true);
    response.put("event", "orders.created");
    response.put("receivedAt", Instant.now().toString());
    response.put("order", order);
    return response;
  }
}
