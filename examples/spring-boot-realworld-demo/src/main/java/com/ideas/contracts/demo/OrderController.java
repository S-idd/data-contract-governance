package com.ideas.contracts.demo;

import com.ideas.contracts.starter.ValidateContract;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orders")
class OrderController {
  @PostMapping
  @ValidateContract(contractId = "orders.created", version = "v1")
  public org.springframework.http.ResponseEntity<Map<String, Object>> createOrder(
      @RequestBody OrderCreated order) {
    return org.springframework.http.ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
        "orderId", order.orderId(),
        "status", order.status(),
        "accepted", true));
  }
}
