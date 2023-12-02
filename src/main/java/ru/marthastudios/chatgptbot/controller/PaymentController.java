package ru.marthastudios.chatgptbot.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import ru.marthastudios.chatgptbot.api.OpenaiApi;
import ru.marthastudios.chatgptbot.service.PaymentService;

@RestController
@RequestMapping("/api/pay")
@RequiredArgsConstructor
@Slf4j
public class PaymentController {
    private final PaymentService paymentService;
    private final OpenaiApi openaiApi;
    @PostMapping
    public void handlePayment(@RequestParam(value = "merchant_id") String merchantId,
                              @RequestParam(value = "amount") double amount,
                              @RequestParam(value = "order_id") String orderId,
                              @RequestParam(value = "currency") String currency,
                              @RequestParam(value = "sign") String sign){
        log.info("Received from /api/pay with orderId: " + orderId);

        paymentService.handlePayment(merchantId, amount, orderId, currency, sign);
    }
}
