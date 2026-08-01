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
@RequestMapping("/demo/payments")
class PaymentController {
  @PostMapping
  @ValidateContract(contractId = "payments.authorized", version = "v1")
  Map<String, Object> authorizePayment(@RequestBody Map<String, Object> payment) {
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("accepted", true);
    response.put("event", "payments.authorized");
    response.put("receivedAt", Instant.now().toString());
    response.put("payment", payment);
    return response;
  }
}
